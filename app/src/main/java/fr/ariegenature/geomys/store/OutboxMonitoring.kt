/*
 * GeoMys-Android — application Android de saisie naturaliste pour GeoNature.
 * Copyright (C) 2026 ANA - CEN Ariège
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package fr.ariegenature.geomys.store

import android.content.Context
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type
import java.util.UUID

/** Une saisie monitoring (visite, observation, occurrence…) stockée localement en
 *  attendant d'être poussée vers le serveur. L'envoi est exclusivement à la demande
 *  utilisateur (depuis l'écran [SaisiesEnAttenteFragment]) — aucun envoi automatique
 *  au retour réseau.
 *
 *  Le lien parent → enfant utilise [parentUuidLocal] tant que le parent n'est pas envoyé
 *  (= uuid d'une autre entrée outbox), et [parentIdServeur] dès que le parent est SENT.
 *  L'ordonnancement à l'envoi résout les parentUuidLocal en id serveur avant le POST
 *  des enfants. */
data class SaisieEnAttente(
    val uuid: String = UUID.randomUUID().toString(),
    val moduleCode: String,
    val objectType: String,
    val parentObjectType: String? = null,
    val parentIdServeur: Int? = null,
    val parentUuidLocal: String? = null,
    val parentIdField: String? = null,
    val nomsChampsSchema: List<String> = emptyList(),
    /** Codes des champs texte-libre (widgets TEXT/TEXTAREA) du schéma. Sert à l'envoi à
     *  NE PAS coercer en Int une valeur numérique saisie dans un commentaire (ex. "42")
     *  — sinon Marshmallow rejette un Int sur un champ typé `string` côté serveur (audit B6).
     *  Vide pour les saisies legacy → comportement historique (coercition de tout). */
    val champsTexteLibre: List<String> = emptyList(),
    /** JSON sérialisé du payload des valeurs collectées par le form renderer. Stocké en
     *  String pour éviter les pertes de type Gson (Int → Double, etc.). Reparsé via
     *  JSONObject au moment de l'envoi. */
    val valeursJson: String,
    val dateLocale: Long = System.currentTimeMillis(),
    val etat: Etat = Etat.PENDING,
    val messageErreur: String? = null,
    /** Id serveur attribué après envoi réussi — utile pour le drill-down ou pour purger
     *  cette saisie de la file plus tard. */
    val idServeur: Int? = null,
    /** true dès que l'objet a été CRÉÉ côté serveur (POST 2xx), même si l'upload des médias
     *  a échoué ensuite. Un « Réessayer » sur une saisie objetCree ne re-POSTe PAS l'objet
     *  (doublon sinon) : il ne renvoie que les médias. Permet aussi de débloquer les enfants
     *  d'un parent dont seuls les médias ont échoué. */
    val objetCree: Boolean = false,
    /** true dès qu'un POST a été TENTÉ (passage en SENDING), même sans confirmation. Si la
     *  réponse s'est perdue (coupure pendant le transfert), l'objet peut exister côté serveur
     *  sans que [objetCree] le sache : avant un re-POST, l'envoi vérifie alors par
     *  [uuidPayload] que l'objet n'existe pas déjà (anti-doublon, cf. OutboxEnvoi). */
    val dejaTentee: Boolean = false,
    /** uuid pré-généré côté client à la création de la saisie, injecté dans le payload POST
     *  au champ [uuidFieldName] (ex. `uuid_base_visit`). Sert ensuite à rattacher un média
     *  uploadé sur gn_commons à l'objet créé via `uuid_attached_row`. Null si le schéma ne
     *  déclare pas d'uuid_field_name (vieux protocole) ou pour les saisies sans média. */
    val uuidPayload: String? = null,
    /** Nom du champ uuid du schéma (cf. [uuidPayload]) — recopié du MonitoringSchemaObjet. */
    val uuidFieldName: String? = null,
    /** LEGACY (mono-fichier) : ancien champ unique, conservé en lecture seule pour ne pas perdre
     *  les brouillons créés avant le passage au multi-fichiers. Les nouvelles saisies écrivent
     *  [mediaPathsLocal]. Cf. [mediasLocaux]. */
    val mediaPathLocal: String? = null,
    /** Chemins locaux des fichiers médias à uploader après création de l'objet. Format URI String
     *  pointant vers filesDir/medias/… (copie locale faite à la sélection pour survivre au cycle
     *  de vie de l'Uri ACTION_GET_CONTENT). Vide = pas de média. */
    val mediaPathsLocal: List<String> = emptyList(),
    /** `schema_dot_table` du champ media (ex. `gn_monitoring.t_base_visits`), résolu en
     *  id_table_location côté envoi. Null si pas de média. */
    val mediaSchemaDotTable: String? = null,
    /** LIBELLÉS des champs OBLIGATOIRES encore vides au dernier enregistrement — saisie
     *  « à compléter » (demande terrain 2026-09-03). Certains protocoles imposent des infos
     *  qu'on ne connaît qu'À LA FIN de la visite (heure de fin, température de fin) : la
     *  saisie est donc enregistrable incomplète, mais elle NE PART PAS tant qu'il reste des
     *  manquants ([OutboxEnvoi] l'écarte, ses enfants restent bloqués avec elle). Recalculé à
     *  chaque enregistrement du formulaire — jamais évalué ailleurs (les règles `required`
     *  dynamiques ont besoin du rendu). Nullable : les saisies écrites avant ce champ le
     *  relisent à null (Gson par réflexion, cf. [manquants]). */
    val champsManquants: List<String>? = null,
) {
    enum class Etat { PENDING, SENDING, SENT, ERROR }

    /** Libellés des champs obligatoires manquants (liste vide si la saisie est complète). */
    fun manquants(): List<String> = champsManquants ?: emptyList()

    /** true = saisie CONSERVÉE localement mais non envoyable tant qu'elle n'est pas complétée. */
    val aCompleter: Boolean get() = !champsManquants.isNullOrEmpty()

    /** Liste effective des médias : [mediaPathsLocal] si renseignée, sinon repli sur l'ancien
     *  champ mono-fichier [mediaPathLocal] (brouillons d'avant la multi-pj). */
    fun mediasLocaux(): List<String> =
        mediaPathsLocal.ifEmpty { listOfNotNull(mediaPathLocal?.takeIf { it.isNotEmpty() }) }
}

/** Store JSON local des saisies monitoring en attente d'envoi, sur [JsonCollectionStore].
 *  Persistance dans `filesDir/monitoring_outbox.json` (backend FICHIER : écriture durable tmp +
 *  fsync + rename atomique, car ces saisies sont irremplaçables). Objet singleton → cache et
 *  verrou naturellement process-wide. La file reste petite (quelques dizaines à centaines max). */
object OutboxMonitoring : JsonCollectionStore<SaisieEnAttente>() {
    private lateinit var fichier: File

    override val nom = "OutboxMonitoring"
    override val verrou = Any()
    override var cache: List<SaisieEnAttente>? = null
    override val typeListe: Type = object : TypeToken<MutableList<SaisieEnAttente?>>() {}.type

    fun init(context: Context) {
        synchronized(verrou) {
            fichier = File(context.filesDir, "monitoring_outbox.json")
            cache = null  // ré-init = démarrage à froid : la prochaine lecture repart du disque
        }
    }

    override fun lireBrut(): String? {
        if (!::fichier.isInitialized || !fichier.exists()) return null
        return fichier.readText()
    }

    /** Écriture DURABLE : tmp + fsync + rename atomique. Retourne false si la persistance a échoué
     *  (l'appelant DOIT le vérifier pour une création : sinon la visite que l'utilisateur croit
     *  enregistrée n'existe nulle part). Le cache n'est mis à jour qu'après (cf. base). */
    override fun ecrireBrut(json: String): Boolean {
        if (!::fichier.isInitialized) {
            android.util.Log.w(nom, "sauvegarder : fichier non initialisé")
            return false
        }
        return try {
            val tmp = File(fichier.parentFile, fichier.name + ".tmp")
            java.io.FileOutputStream(tmp).use { fos ->
                fos.write(json.toByteArray(Charsets.UTF_8))
                // Sans fsync, une coupure d'alimentation brutale peut committer le rename sans les
                // blocs de données → fichier cible vide/tronqué.
                fos.fd.sync()
            }
            var ok = tmp.renameTo(fichier)
            if (!ok) {
                if (fichier.exists()) fichier.delete()
                ok = tmp.renameTo(fichier)
            }
            if (ok) android.util.Log.i(nom, "sauvegarde OK (path=${fichier.absolutePath})")
            else android.util.Log.w(nom, "sauvegarder ECHEC rename (disque conservé)")
            ok
        } catch (e: Exception) {
            android.util.Log.w(nom, "sauvegarder ECHEC : ${e.message}", e)
            false
        }
    }

    /** Quarantaine = copie fidèle du FICHIER fautif (premier incident) avant qu'il soit écrasé. */
    override fun quarantaine(json: String) {
        try {
            val q = File(fichier.parentFile, fichier.name + ".corrupt")
            if (!q.exists() && fichier.exists()) fichier.copyTo(q)
        } catch (_: Exception) {}
    }

    /** Reconstruit une entrée sûre après désérialisation. Gson instancie sans passer par le
     *  constructeur (Unsafe) : les champs absents du JSON restent à null, y compris les listes
     *  NON-NULLABLES ajoutées par des versions plus récentes (ex. [SaisieEnAttente.mediaPathsLocal]
     *  en 0.10.4 — un brouillon écrit avant crashe au premier copy()/accès). Retourne null si un
     *  champ obligatoire manque (entrée écartée → quarantaine côté base). */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS") // Gson viole la non-nullabilité Kotlin
    /** ⚠ RECONSTRUCTION EXHAUSTIVE : tout champ ajouté à [SaisieEnAttente] DOIT être recopié
     *  ici, sinon il est silencieusement remis à sa valeur par défaut à chaque relecture du
     *  disque — la saisie paraît correcte tant qu'elle est en cache mémoire, puis « perd » le
     *  champ au redémarrage (bug terrain 2026-09-03 : une visite « à compléter » redevenait
     *  complète et partait telle quelle). `OutboxRoundTripTest` fait échouer tout oubli. */
    override fun normaliser(item: SaisieEnAttente): SaisieEnAttente? {
        val e = item
        if (e.uuid == null || e.moduleCode == null || e.objectType == null ||
            e.valeursJson == null || e.etat == null
        ) return null
        return SaisieEnAttente(
            uuid = e.uuid, moduleCode = e.moduleCode, objectType = e.objectType,
            parentObjectType = e.parentObjectType, parentIdServeur = e.parentIdServeur,
            parentUuidLocal = e.parentUuidLocal, parentIdField = e.parentIdField,
            nomsChampsSchema = e.nomsChampsSchema ?: emptyList(),
            champsTexteLibre = e.champsTexteLibre ?: emptyList(),
            valeursJson = e.valeursJson, dateLocale = e.dateLocale, etat = e.etat,
            messageErreur = e.messageErreur, idServeur = e.idServeur, objetCree = e.objetCree,
            dejaTentee = e.dejaTentee,
            uuidPayload = e.uuidPayload, uuidFieldName = e.uuidFieldName,
            mediaPathLocal = e.mediaPathLocal,
            mediaPathsLocal = e.mediaPathsLocal ?: emptyList(),
            mediaSchemaDotTable = e.mediaSchemaDotTable,
            champsManquants = e.champsManquants,
        )
    }

    /** Ajoute une saisie. Retourne false si la persistance disque a échoué (disque plein, I/O) :
     *  la saisie n'existe alors NULLE PART — l'UI doit prévenir l'utilisateur. */
    fun ajouter(saisie: SaisieEnAttente): Boolean {
        android.util.Log.i(nom,
            "ajouter : uuid=${saisie.uuid}, type=${saisie.objectType}, module=${saisie.moduleCode}")
        return muter { it.add(saisie) }
    }

    fun mettreAJour(uuid: String, transform: (SaisieEnAttente) -> SaisieEnAttente): Boolean =
        muter { liste -> for (i in liste.indices) if (liste[i].uuid == uuid) liste[i] = transform(liste[i]) }

    fun supprimer(uuid: String) { muter { liste -> liste.removeAll { it.uuid == uuid } } }

    /** Retourne récursivement la liste des UUIDs descendants d'une saisie (obs rattachées à une
     *  visite locale, etc.) — utile pour supprimer en cascade ou envoyer un groupe. BFS. */
    fun descendants(uuid: String): List<String> = descendantsDe(charger(), uuid)

    private fun descendantsDe(tous: List<SaisieEnAttente>, uuid: String): List<String> {
        val resultat = mutableListOf<String>()
        val aExplorer = ArrayDeque<String>().apply { add(uuid) }
        while (aExplorer.isNotEmpty()) {
            val courant = aExplorer.removeFirst()
            tous.filter { it.parentUuidLocal == courant }.forEach {
                resultat.add(it.uuid)
                aExplorer.add(it.uuid)
            }
        }
        return resultat
    }

    /** Supprime la saisie [uuid] ET tous ses descendants locaux. Retourne le nombre total
     *  de saisies supprimées (1 + descendants). */
    fun supprimerCascade(uuid: String): Int = muterCalcul { liste ->
        val aRetirer = (descendantsDe(liste, uuid) + uuid).toSet()
        liste.removeAll { it.uuid in aRetirer }
        aRetirer.size
    }

    fun tout(): List<SaisieEnAttente> = charger()

    /** Saisies qui restent à envoyer (PENDING + ERROR). Les SENT sont gardées dans la
     *  liste pour info mais filtrées du compteur "en attente". */
    fun enAttente(): List<SaisieEnAttente> =
        charger().filter { it.etat == SaisieEnAttente.Etat.PENDING || it.etat == SaisieEnAttente.Etat.ERROR }

    /** Compteur affiché dans le badge de l'écran d'accueil / menu Suivis. */
    fun countEnAttente(): Int = enAttente().size

    /** Purge les saisies déjà envoyées (utile pour limiter la croissance du fichier après
     *  quelques mois d'usage) — SAUF un parent SENT encore référencé par un enfant NON envoyé
     *  (parentUuidLocal) : une visite partiellement envoyée doit rester affichée dans « Mes
     *  visites » comme groupe portant ses obs restantes (et sa flèche d'envoi), jusqu'à ce que
     *  tout soit parti. Les références d'enfants eux-mêmes SENT ne comptent pas : quand tout
     *  le groupe est envoyé, parent et enfants sont purgés ensemble. */
    fun purgerSent() {
        muter { liste ->
            val referencees = liste
                .filterNot { it.etat == SaisieEnAttente.Etat.SENT }
                .mapNotNull { it.parentUuidLocal }
                .toSet()
            liste.removeAll { it.etat == SaisieEnAttente.Etat.SENT && it.uuid !in referencees }
        }
    }

    fun vider() { muter { it.clear() } }
}
