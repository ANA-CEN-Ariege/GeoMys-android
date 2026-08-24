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

package fr.ariegenature.geomys.network

import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** Droits de l'utilisateur sur le module OccHab (issus du cruved de `gn_commons/modules`). */
data class OccHabAcces(
    /** Module installé sur le serveur ET visible par l'utilisateur (au moins un droit). */
    val disponible: Boolean,
    /** cruved C > 0 : peut créer des stations. */
    val peutCreer: Boolean,
    /** cruved R > 0 : peut lire les stations. */
    val peutLire: Boolean,
) {
    companion object {
        val ABSENT = OccHabAcces(disponible = false, peutCreer = false, peutLire = false)
    }
}

/**
 * Accès réseau au module OccHab : détection du module (droits), et lecture des stations
 * existantes côté serveur (consultation lecture seule). L'envoi vit dans [OccHabUpload].
 */
object OccHabApi {

    const val MODULE_CODE = "OCCHAB"

    // Codes mnémoniques des nomenclatures OccHab (contraintes CHECK du module gn_module_occhab).
    // Utilisés pour restreindre la synchro (GeoNatureSync.synchroniserNomenclatures) aux listes
    // pertinentes. NAT_OBJ_GEO est partagé avec Occtax (déjà synchronisé).
    val MNEMONIQUES_STATION = listOf(
        "EXPOSITION",             // id_nomenclature_exposure
        "NAT_OBJ_GEO",            // id_nomenclature_geographic_object
        "METHOD_CALCUL_SURFACE",  // id_nomenclature_area_surface_calculation
        "TYPE_SOL",               // id_nomenclature_type_sol
        "MOSAIQUE_HAB",           // id_nomenclature_type_mosaique_habitat
    )
    val MNEMONIQUES_HABITAT = listOf(
        "DETERMINATION_TYP_HAB",  // id_nomenclature_determination_type
        "TECHNIQUE_COLLECT_HAB",  // id_nomenclature_collection_technique
        "ABONDANCE_HAB",          // id_nomenclature_abundance
        "SENSIBILITE",            // id_nomenclature_sensitivity
        "HAB_INTERET_COM",        // id_nomenclature_community_interest
    )
    val MNEMONIQUES: Set<String> = (MNEMONIQUES_STATION + MNEMONIQUES_HABITAT).toSet()

    /**
     * Altitudes min/max (MNT serveur) d'une géométrie via `POST /api/geo/info` — le même
     * appel que le web quand on dessine une station (form-service.ts `patchGeoValue` →
     * `getGeoInfo`). BEST-EFFORT : null si hors-ligne, non authentifié, erreur ou réponse
     * sans bloc `altitude` — l'appelant laisse alors les champs vides (saisie manuelle).
     * [geometryGeoJson] = objet GeoJSON *geometry* ({"type":"Point"/"Polygon","coordinates":…}),
     * enveloppé ici en Feature comme le fait le client web.
     */
    suspend fun altitudesPourGeometrie(
        config: GeoNatureConfig,
        geometryGeoJson: JSONObject,
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: return@withContext null
            val feature = JSONObject()
                .put("type", "Feature")
                .put("geometry", geometryGeoJson)
                .put("properties", JSONObject())
            val conn = HttpClient.postJson(URL("$base/api/geo/info"), token, cookies)
            conn.outputStream.use { it.write(feature.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return@withContext null
            val texte = conn.inputStream.bufferedReader().use { it.readText() }
            val alt = JSONObject(texte).optJSONObject("altitude") ?: return@withContext null
            val min = alt.opt("altitude_min")?.toString()?.toDoubleOrNull()?.toInt()
            val max = alt.opt("altitude_max")?.toString()?.toDoubleOrNull()?.toInt()
            if (min == null || max == null) null else min to max
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Détection GÉNÉRIQUE des droits par module via `GET /api/gn_commons/modules` (UN seul
     * appel pour tous les [codes]). N'inclut dans la map QUE les modules trouvés dans la
     * réponse : un module absent (pas installé / aucun droit) n'y figure pas — l'appelant
     * choisit son défaut (ABSENT pour OccHab, « autorisé » pour Occtax afin de ne pas
     * verrouiller l'app sur un serveur qui ne publie pas ce endpoint). Best-effort : map
     * vide sur toute erreur.
     */
    suspend fun detecterModules(
        config: GeoNatureConfig,
        codes: Set<String>,
    ): Map<String, OccHabAcces> = withContext(Dispatchers.IO) {
        val base = config.urlServeur.trim().trimEnd('/')
        val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
            ?: return@withContext emptyMap()
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = HttpClient.get(URL("$base/api/gn_commons/modules"), token, cookies, 15000)
            if (conn.responseCode != 200) return@withContext emptyMap()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            val resultat = mutableMapOf<String, OccHabAcces>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val code = m.optString("module_code")
                if (code !in codes) continue
                val cruved = m.optJSONObject("cruved")
                val c = cruved?.optInt("C", 0) ?: 0
                val r = cruved?.optInt("R", 0) ?: 0
                resultat[code] = OccHabAcces(disponible = true, peutCreer = c > 0, peutLire = r > 0)
            }
            resultat
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // ne PAS avaler l'annulation (sinon un sync annulé réinitialise les drapeaux).
        } catch (_: Exception) {
            emptyMap()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Valeurs par défaut des nomenclatures OccHab (`GET /api/occhab/defaultNomenclatures`) :
     * map mnémonique→id_nomenclature (ex. `TECHNIQUE_COLLECT_HAB` → « In situ »). Pré-remplit les
     * sélecteurs d'un nouvel habitat, comme le web. Best-effort : map vide si échec.
     */
    suspend fun chargerDefautsNomenclatures(config: GeoNatureConfig): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: return@withContext emptyMap()
            try {
                val conn = HttpClient.get(URL("$base/api/occhab/defaultNomenclatures"), token, cookies, 15000)
                if (conn.responseCode != 200) return@withContext emptyMap()
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                val out = HashMap<String, Int>()
                val it = obj.keys()
                while (it.hasNext()) {
                    val mnem = it.next()
                    val id = obj.optJSONObject(mnem)?.optInt("id_nomenclature", -1)?.takeIf { v -> v > 0 }
                    if (id != null) out[mnem] = id
                }
                out
            } catch (_: Exception) { emptyMap() }
        }

    // NB : la consultation des stations SERVEUR (chargerStations + parsing FeatureCollection),
    // prevue au MVP OccHab mais jamais branchee dans l'UI, a ete supprimee (code mort,
    // audit 2026-08-23) — l'historique git la conserve si le besoin revient.
}
