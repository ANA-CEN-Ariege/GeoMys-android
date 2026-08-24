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
import java.io.File

/** Cache offline du module Suivis (gn_module_monitoring). Stocke les payloads JSON
 *  bruts retournés par les 4 endpoints clé : modules, config (schéma), object/<module>/module
 *  (enfants directs), object/<module>/<type>/<id> (fiche objet).
 *
 *  Stratégie d'utilisation côté [fr.ariegenature.geomys.network.MonitoringApi] :
 *  - Succès HTTP → on écrit le payload brut dans le cache (write-through).
 *  - Échec réseau (IOException, timeout) → on retombe sur le cache si présent.
 *  - HTTP non-OK (4xx/5xx) → on ne touche pas au cache et on propage l'erreur.
 *
 *  Le pré-chargement complet (tous les modules + leurs enfants + leurs fiches) est
 *  orchestré par [fr.ariegenature.geomys.network.MonitoringSync.synchroniserSuivis]. */
object MonitoringCache {
    private lateinit var dir: File

    /** Cache MÉMOIRE des payloads déjà lus (clé fichier → JSON brut). Évite de relire le disque à
     *  CHAQUE appel de getJson : les résolveurs de labels/genres/chemins (MonitoringApi) tapent le
     *  cache des dizaines de fois par écran (par ligne de « Mes visites », par enfant de fiche).
     *  Peuplé UNIQUEMENT à la lecture (audit 2026-08-23 : mémoïser aussi dans setJson faisait
     *  retenir en RAM l'intégralité de la synchro BFS — tous les modules × fiches × datalists —
     *  à vie du process ; en lecture seule, la map ne contient que ce que les écrans affichent).
     *  Seules les valeurs PRÉSENTES sont mémoïsées (une absence coûte juste un `exists()`).
     *  Cohérence : INVALIDATION par [setJson]/[vider] + garde [generation] contre la course
     *  lecture/écriture (un getJson concurrent d'un setJson ne doit pas re-mémoïser la vieille
     *  valeur par-dessus la neuve). Thread-safe (UI + IO). */
    private val memoire = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Incrémenté à chaque mutation ([setJson]/[vider]) : un getJson qui a lu le disque AVANT la
     *  mutation détecte le changement et renonce à mémoïser sa valeur (devenue périmée). */
    @Volatile private var generation = 0

    fun init(context: Context) {
        dir = File(context.filesDir, "monitoring").apply { mkdirs() }
    }

    /** Normalise une clé arbitraire en nom de fichier safe (préserve les caractères
     *  alphanumériques + .-_, remplace tout le reste par _). Évite les ennuis avec
     *  des module_code contenant des espaces ou des accents. */
    private fun safeKey(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun fichier(nom: String) = File(dir, nom)

    fun getJson(name: String): String? {
        memoire[name]?.let { return it }
        return try {
            val g = generation
            val f = fichier(name)
            if (f.exists()) f.readText().also {
                // Mémoïse seulement si AUCUNE mutation n'a eu lieu pendant la lecture disque
                // (sinon la valeur lue peut être périmée) ; putIfAbsent pour ne jamais écraser
                // une entrée posée entre-temps par un autre lecteur.
                if (g == generation) memoire.putIfAbsent(name, it)
            } else null
        } catch (_: Exception) { null }
    }

    /** Écriture atomique tmp+rename — évite un fichier tronqué si le process est tué
     *  pendant la sauvegarde (cohérent avec TaxRefCache). */
    fun setJson(name: String, json: String) {
        try {
            // Rename atomique (écrase la cible) ; delete + retry seulement si l'écrasement direct
            // échoue, pour ne pas perdre l'ancien fichier sur un kill entre delete et rename.
            val cible = fichier(name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(cible)) {
                if (cible.exists()) cible.delete()
                tmp.renameTo(cible)
            }
            // INVALIDE l'entrée mémoire au lieu de la peupler : la synchro (qui écrit tout)
            // ne doit pas charger la RAM ; le prochain getJson relira le disque et mémoïsera
            // à la demande. generation++ d'abord, pour qu'un getJson concurrent qui a lu
            // l'ANCIEN fichier renonce à re-mémoïser sa valeur périmée.
            generation++
            memoire.remove(name)
        } catch (_: Exception) {}
    }

    fun vider() {
        try {
            // Fichiers d'abord, mémoire ensuite : un getJson concurrent ne peut au pire que
            // resservir brièvement une entrée mémoire sur le point d'être purgée — jamais
            // re-mémoïser un fichier en cours de suppression (generation++ l'en empêche).
            generation++
            if (::dir.isInitialized) dir.listFiles()?.forEach { it.delete() }
            memoire.clear()
        } catch (_: Exception) {}
    }

    // ─── Clés typées ──────────────────────────────────────────────────────────
    fun keyModules(): String = "modules.json"
    fun keySchema(moduleCode: String): String = "schema_${safeKey(moduleCode)}.json"
    fun keyEnfants(moduleCode: String): String = "enfants_${safeKey(moduleCode)}.json"
    fun keyObjet(moduleCode: String, type: String, id: Int): String =
        "objet_${safeKey(moduleCode)}_${safeKey(type)}_$id.json"
    /** Cache local d'une liste UsersHub (`/api/users/menu/<id_liste>`) — utilisé pour
     *  l'autocomplétion observateurs et la résolution id_role → nom complet en offline. */
    fun keyObservateurs(idListe: Int): String = "observateurs_$idListe.json"

    /** Cache local des OPTIONS d'un widget datalist/nomenclature de formulaire monitoring,
     *  indexé par son `apiUrl` (query params compris → clé unique par filtre). Peuplé au 1er
     *  fetch live ET au prefetch de synchro ; relu hors-ligne pour ne pas bloquer la saisie
     *  d'une nouvelle visite (liste déroulante vide). */
    fun keyOptionsDatalist(apiUrl: String): String = "datalist_${safeKey(apiUrl)}.json"
}
