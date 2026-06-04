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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
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
) {
    enum class Etat { PENDING, SENDING, SENT, ERROR }

    /** Liste effective des médias : [mediaPathsLocal] si renseignée, sinon repli sur l'ancien
     *  champ mono-fichier [mediaPathLocal] (brouillons d'avant la multi-pj). */
    fun mediasLocaux(): List<String> =
        mediaPathsLocal.ifEmpty { listOfNotNull(mediaPathLocal?.takeIf { it.isNotEmpty() }) }
}

/** Store JSON local des saisies monitoring en attente d'envoi. Persistance dans
 *  `filesDir/monitoring_outbox.json`. Toutes les opérations sont synchrones — la taille
 *  de la file reste petite (quelques dizaines à centaines max). */
object OutboxMonitoring {
    private lateinit var fichier: File
    private val gson = Gson()
    @Volatile private var mem: List<SaisieEnAttente>? = null

    fun init(context: Context) {
        fichier = File(context.filesDir, "monitoring_outbox.json")
        mem = null  // ré-init = démarrage à froid : la prochaine lecture repart du disque
    }

    private fun charger(): List<SaisieEnAttente> {
        mem?.let { return it }
        if (!::fichier.isInitialized || !fichier.exists()) return emptyList()
        return try {
            val json = fichier.readText()
            val type = object : TypeToken<List<SaisieEnAttente?>>() {}.type
            (gson.fromJson<List<SaisieEnAttente?>>(json, type) ?: emptyList())
                .mapNotNull { it?.let(::normaliser) }
                .also { mem = it }
        } catch (_: Exception) { emptyList() }
    }

    /** Reconstruit une entrée sûre après désérialisation. Gson instancie sans passer par le
     *  constructeur (Unsafe) : les champs absents du JSON restent à null, y compris les listes
     *  NON-NULLABLES ajoutées par des versions plus récentes de l'app (ex. [SaisieEnAttente
     *  .mediaPathsLocal] en 0.10.4 — un brouillon écrit avant crashe au premier copy()/accès).
     *  Même chose pour un JSON corrompu-mais-parsable (champ obligatoire manquant, etat
     *  inconnu → null). Retourne null si un champ obligatoire manque (entrée écartée). */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS") // Gson viole la non-nullabilité Kotlin
    private fun normaliser(e: SaisieEnAttente): SaisieEnAttente? {
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
            uuidPayload = e.uuidPayload, uuidFieldName = e.uuidFieldName,
            mediaPathLocal = e.mediaPathLocal,
            mediaPathsLocal = e.mediaPathsLocal ?: emptyList(),
            mediaSchemaDotTable = e.mediaSchemaDotTable,
        )
    }

    private fun sauvegarder(liste: List<SaisieEnAttente>) {
        if (!::fichier.isInitialized) {
            android.util.Log.w("OutboxMonitoring", "sauvegarder : fichier non initialisé")
            return
        }
        try {
            val tmp = File(fichier.parentFile, fichier.name + ".tmp")
            tmp.writeText(gson.toJson(liste))
            // rename atomique (écrase la cible). Si le rename échoue, on retombe sur une copie
            // explicite ; la mémoire n'est mise à jour QU'APRÈS une persistance disque confirmée,
            // sinon on risquerait de perdre silencieusement des saisies (mem ≠ disque).
            var ok = tmp.renameTo(fichier)
            if (!ok) {
                if (fichier.exists()) fichier.delete()
                ok = tmp.renameTo(fichier)
            }
            if (ok) {
                mem = liste
                android.util.Log.i("OutboxMonitoring",
                    "sauvegarder OK (${liste.size} entrées, path=${fichier.absolutePath})")
            } else {
                android.util.Log.w("OutboxMonitoring",
                    "sauvegarder ECHEC rename — mémoire NON mise à jour (disque conservé)")
            }
        } catch (e: Exception) {
            android.util.Log.w("OutboxMonitoring", "sauvegarder ECHEC : ${e.message}", e)
        }
    }

    fun ajouter(saisie: SaisieEnAttente) {
        android.util.Log.i("OutboxMonitoring",
            "ajouter : uuid=${saisie.uuid}, type=${saisie.objectType}, module=${saisie.moduleCode}")
        sauvegarder(charger() + saisie)
    }

    fun mettreAJour(uuid: String, transform: (SaisieEnAttente) -> SaisieEnAttente) {
        sauvegarder(charger().map { if (it.uuid == uuid) transform(it) else it })
    }

    fun supprimer(uuid: String) {
        sauvegarder(charger().filterNot { it.uuid == uuid })
    }

    /** Retourne récursivement la liste des UUIDs descendants d'une saisie (obs rattachées
     *  à une visite locale, etc.) — utile pour supprimer en cascade ou envoyer un groupe.
     *  Parcours BFS pour gérer les structures à plusieurs niveaux. */
    fun descendants(uuid: String): List<String> {
        val tous = charger()
        val resultat = mutableListOf<String>()
        val aExplorer = ArrayDeque<String>().apply { add(uuid) }
        while (aExplorer.isNotEmpty()) {
            val courant = aExplorer.removeFirst()
            val enfants = tous.filter { it.parentUuidLocal == courant }
            enfants.forEach {
                resultat.add(it.uuid)
                aExplorer.add(it.uuid)
            }
        }
        return resultat
    }

    /** Supprime la saisie [uuid] ET tous ses descendants locaux. Retourne le nombre total
     *  de saisies supprimées (1 + descendants). */
    fun supprimerCascade(uuid: String): Int {
        val aRetirer = (descendants(uuid) + uuid).toSet()
        sauvegarder(charger().filterNot { it.uuid in aRetirer })
        return aRetirer.size
    }

    fun tout(): List<SaisieEnAttente> = charger()

    /** Saisies qui restent à envoyer (PENDING + ERROR). Les SENT sont gardées dans la
     *  liste pour info mais filtrées du compteur "en attente". */
    fun enAttente(): List<SaisieEnAttente> =
        charger().filter { it.etat == SaisieEnAttente.Etat.PENDING || it.etat == SaisieEnAttente.Etat.ERROR }

    /** Compteur affiché dans le badge de l'écran d'accueil / menu Suivis. */
    fun countEnAttente(): Int = enAttente().size

    /** Purge les saisies déjà envoyées (utile pour limiter la croissance du fichier
     *  après quelques mois d'usage). */
    fun purgerSent() {
        sauvegarder(charger().filterNot { it.etat == SaisieEnAttente.Etat.SENT })
    }

    fun vider() {
        sauvegarder(emptyList())
    }
}
