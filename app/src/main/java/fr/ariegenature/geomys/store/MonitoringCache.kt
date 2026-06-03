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

    fun init(context: Context) {
        dir = File(context.filesDir, "monitoring").apply { mkdirs() }
    }

    /** Normalise une clé arbitraire en nom de fichier safe (préserve les caractères
     *  alphanumériques + .-_, remplace tout le reste par _). Évite les ennuis avec
     *  des module_code contenant des espaces ou des accents. */
    private fun safeKey(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun fichier(nom: String) = File(dir, nom)

    fun getJson(name: String): String? = try {
        val f = fichier(name)
        if (f.exists()) f.readText() else null
    } catch (_: Exception) { null }

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
        } catch (_: Exception) {}
    }

    /** Invalide une entrée précise du cache (utile après une création/modification serveur
     *  pour forcer le re-fetch à la prochaine ouverture de l'objet parent). */
    fun deleteJson(name: String) {
        runCatching { fichier(name).delete() }
    }

    fun vider() {
        try {
            if (::dir.isInitialized) dir.listFiles()?.forEach { it.delete() }
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
}
