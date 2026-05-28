package fr.ariegenature.geonat.network

import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.OutboxMonitoring
import fr.ariegenature.geonat.store.SaisieEnAttente
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Orchestrateur de l'envoi des saisies monitoring en attente.
 *
 *  Stratégie :
 *  1. Détermine l'ordre d'envoi en respectant les dépendances parent → enfant : une
 *     obs qui pointe sur une visite locale ([SaisieEnAttente.parentUuidLocal]) doit
 *     être envoyée APRÈS sa visite parente, sinon on n'a pas d'id serveur à utiliser
 *     comme FK. Tri topologique simple : on émet d'abord les saisies sans
 *     `parentUuidLocal`, puis on les rajoute itérativement quand leur parent est SENT.
 *  2. Pour chaque saisie PENDING/ERROR : POST via [MonitoringApi.envoyerVisite]. Sur
 *     succès → marque SENT + stocke l'idServeur. Sur échec → marque ERROR + stocke le
 *     message.
 *  3. Au fur et à mesure, met à jour les enfants qui pointaient vers le parent local :
 *     leur `parentUuidLocal` est null et `parentIdServeur` reçoit l'id serveur résolu.
 *
 *  Callback `progression(envoyees, total, dernierMessage)` pour piloter une UI de
 *  progression côté écran "Saisies en attente". */
object OutboxEnvoi {

    data class Resultat(val succes: Int, val echecs: Int, val messages: List<String>)

    /** Envoie un sous-ensemble de la file : la saisie [uuidRacine] + tous ses descendants
     *  locaux. Délègue à [envoyerSelection]. Utilisé par le bouton "Envoyer ce groupe"
     *  d'une visite locale (= envoie la visite + ses obs sans toucher au reste de la file). */
    suspend fun envoyerGroupe(
        config: GeoNatureConfig,
        uuidRacine: String,
        progression: (envoyees: Int, total: Int, message: String) -> Unit,
    ): Resultat {
        val groupe = (OutboxMonitoring.descendants(uuidRacine) + uuidRacine).toSet()
        return envoyerSelection(config, groupe, progression)
    }

    suspend fun envoyerTout(
        config: GeoNatureConfig,
        progression: (envoyees: Int, total: Int, message: String) -> Unit,
    ): Resultat = envoyerSelection(
        config = config,
        uuidsACibler = null,  // null = toute la file
        progression = progression,
    )

    /** Cœur de l'algo : envoie toutes les saisies PENDING dont l'uuid est dans [uuidsACibler],
     *  ou toute la file si null. Ordre parent → enfant via tri topologique itératif. */
    private suspend fun envoyerSelection(
        config: GeoNatureConfig,
        uuidsACibler: Set<String>?,
        progression: (envoyees: Int, total: Int, message: String) -> Unit,
    ): Resultat = withContext(Dispatchers.IO) {
        val initiales = OutboxMonitoring.enAttente()
            .filter { uuidsACibler == null || it.uuid in uuidsACibler }
        if (initiales.isEmpty()) return@withContext Resultat(0, 0, emptyList())

        val total = initiales.size
        var envoyees = 0
        var succes = 0
        var echecs = 0
        val messages = mutableListOf<String>()

        // Boucle : à chaque tour on ne traite que les PENDING (= jamais tentées dans cet
        // envoi). Les ERROR du tour courant restent ERROR — pour les rejouer il faut le
        // "Réessayer" explicite côté UI. On itère tant qu'on a réussi à envoyer au moins
        // une saisie au tour précédent : ça débloque les enfants qui attendaient leur
        // parent local. Quand un tour n'envoie rien de nouveau → fini.
        var nouvelleVagueSucces = true
        while (nouvelleVagueSucces) {
            nouvelleVagueSucces = false
            val restantes = OutboxMonitoring.tout()
                .filter { it.etat == SaisieEnAttente.Etat.PENDING }
            if (restantes.isEmpty()) break

            for (saisie in restantes) {
                // Parent local pas encore envoyé → on saute, sera repris quand le parent
                // passera en SENT au tour suivant.
                if (saisie.parentUuidLocal != null) {
                    val parent = OutboxMonitoring.tout().firstOrNull { it.uuid == saisie.parentUuidLocal }
                    if (parent?.etat != SaisieEnAttente.Etat.SENT) continue
                    if (parent.idServeur != null) {
                        OutboxMonitoring.mettreAJour(saisie.uuid) {
                            it.copy(parentIdServeur = parent.idServeur, parentUuidLocal = null)
                        }
                    }
                }
                OutboxMonitoring.mettreAJour(saisie.uuid) {
                    it.copy(etat = SaisieEnAttente.Etat.SENDING, messageErreur = null)
                }
                progression(envoyees, total, "Envoi de ${saisie.objectType}…")

                val saisieAJour = OutboxMonitoring.tout().firstOrNull { it.uuid == saisie.uuid } ?: continue
                val res = envoyerUne(config, saisieAJour)
                envoyees++
                res.fold(
                    onSuccess = { idServeur ->
                        succes++
                        // Une SENT débloque potentiellement ses enfants → on permet un
                        // nouveau tour de boucle.
                        nouvelleVagueSucces = true
                        OutboxMonitoring.mettreAJour(saisie.uuid) {
                            it.copy(etat = SaisieEnAttente.Etat.SENT, idServeur = idServeur, messageErreur = null)
                        }
                        messages.add("✅ ${saisie.objectType} #${idServeur}")
                    },
                    onFailure = { e ->
                        echecs++
                        val msg = humaniserErreurReseau(e).take(200)
                        OutboxMonitoring.mettreAJour(saisie.uuid) {
                            it.copy(etat = SaisieEnAttente.Etat.ERROR, messageErreur = msg)
                        }
                        messages.add("⚠ ${saisie.objectType} : $msg")
                    },
                )
                progression(envoyees, total, "")
            }
        }
        // Nettoyage final : purge les saisies SENT pour libérer la liste. Les enfants
        // qui pointaient vers ces parents locaux ont déjà résolu leur parentIdServeur
        // au tour précédent, donc plus besoin de garder l'entrée parent.
        OutboxMonitoring.purgerSent()
        Resultat(succes, echecs, messages)
    }

    /** Envoie une saisie isolée. Lit les valeurs depuis valeursJson, reconstitue la Map
     *  attendue par [MonitoringApi.envoyerVisite] et POST. En cas de succès, déclenche
     *  l'upload du média éventuel (mediaPathLocal) vers gn_commons. Un échec d'upload média
     *  ne fait pas basculer la saisie en ERROR — l'objet est créé côté serveur, on garde
     *  SENT avec un message d'avertissement. */
    private suspend fun envoyerUne(config: GeoNatureConfig, s: SaisieEnAttente): Result<Int> {
        return try {
            val valeurs = mutableMapOf<String, Any?>()
            val obj = org.json.JSONObject(s.valeursJson)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                valeurs[k] = if (obj.isNull(k)) null else obj.opt(k)
            }
            val resVisite = MonitoringApi.envoyerVisite(
                config = config,
                moduleCode = s.moduleCode,
                objectType = s.objectType,
                parentIdField = s.parentIdField,
                parentId = s.parentIdServeur,
                valeurs = valeurs,
                nomsChampsSchema = s.nomsChampsSchema,
                uuidClient = s.uuidPayload,
                uuidFieldName = s.uuidFieldName,
            )
            // Upload média (single-file MVP) après création réussie. La saisie reste SENT
            // même si l'upload échoue — l'objet est en base côté serveur, c'est juste la
            // pièce jointe qui manque. On log via android.util.Log pour diagnostic.
            if (resVisite.isSuccess && s.mediaPathLocal != null &&
                s.mediaSchemaDotTable != null && s.uuidPayload != null
            ) {
                val (ok, err) = fr.ariegenature.geonat.network.GeoNatureUpload.uploaderMediaMonitoring(
                    config = config,
                    mediaPath = s.mediaPathLocal,
                    schemaDotTable = s.mediaSchemaDotTable,
                    uuidAttachedRow = s.uuidPayload,
                    titre = "${s.objectType} ${s.uuidPayload.take(8)}",
                    author = config.nomUtilisateur.ifEmpty { config.login },
                )
                if (!ok) {
                    android.util.Log.w("OutboxEnvoi",
                        "Upload média échoué pour ${s.uuid} (objet créé OK) : $err")
                }
            }
            resVisite
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
