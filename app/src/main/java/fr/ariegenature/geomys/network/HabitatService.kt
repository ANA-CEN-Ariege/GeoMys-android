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

import fr.ariegenature.geomys.store.HabitatCache
import fr.ariegenature.geomys.store.HabitatCacheOccHab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder

/** Une suggestion d'habitat issue du référentiel HABREF du serveur. */
data class HabitatSuggestion(val cdHab: Int, val libelle: String)

/** Recherche d'habitats (référentiel HABREF) pour le champ `cd_hab` du relevé Occtax.
 *
 *  Endpoint **public** (pas d'auth) `GET /api/habref/habitats/autocomplete?search_name=…&limit=…`,
 *  qui renvoie une liste de `{cd_hab, lb_code, lb_nom_typo, search_name}`. On retient `cd_hab`
 *  (valeur envoyée à l'upload) et `search_name` comme libellé d'affichage (déjà préfixé du code).
 *
 *  Tolérant : terme trop court → liste vide ; erreur réseau / serveur indisponible → repli sur le
 *  cache local [HabitatCache] (habitats déjà rencontrés en ligne → champ utilisable HORS LIGNE),
 *  jamais de crash. Chaque recherche en ligne alimente ce cache. */
object HabitatService {

    /** [idList] : restreint la recherche à une liste HABREF précise (`id_list`), comme le web.
     *  - null (Occtax) → cache complet OCCTAX si dispo (hors-ligne), sinon serveur sans filtre.
     *  - non-null (OccHab) → cache DÉDIÉ OccHab si dispo (hors-ligne, mêmes valeurs que le web),
     *    sinon serveur AVEC `id_list`. Le cache OCCTAX n'est jamais utilisé pour OccHab (liste ≠). */
    suspend fun rechercher(
        base: String, terme: String, limite: Int = 20, idList: Int? = null,
    ): List<HabitatSuggestion> =
        withContext(Dispatchers.IO) {
            val t = terme.trim()
            if (t.length < 2) return@withContext emptyList()
            val urlBase = base.trim().trimEnd('/')
            val url = URL(
                "$urlBase/api/habref/habitats/autocomplete" +
                    "?search_name=${URLEncoder.encode(t, "UTF-8")}&limit=$limite" +
                    (idList?.let { "&id_list=$it" } ?: "")
            )
            // Cache du bon périmètre : OccHab si une liste est imposée, sinon le cache complet OCCTAX.
            val cacheDispo = if (idList != null) HabitatCacheOccHab.estDisponible else HabitatCache.estDisponible
            fun depuisCache(): List<HabitatSuggestion> =
                if (idList != null) HabitatCacheOccHab.rechercher(t, limite) else HabitatCache.rechercher(t, limite)
            // Cache présent → recherche LOCALE (hors-ligne, sans round-trip par frappe).
            if (cacheDispo) return@withContext depuisCache()
            // Cache absent → requête serveur (avec id_list si OccHab).
            try {
                val conn = HttpClient.get(url, timeoutMs = 8000)
                try {
                    if (conn.responseCode != 200) {
                        return@withContext if (cacheDispo) depuisCache() else emptyList()
                    }
                    val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i)
                        val cd = o.optInt("cd_hab", -1)
                        if (cd <= 0) return@mapNotNull null
                        val libelle = o.optString("search_name").ifBlank { o.optString("lb_code") }
                            .trim().ifBlank { cd.toString() }
                        HabitatSuggestion(cd, libelle)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                if (cacheDispo) depuisCache() else emptyList()
            }
        }
}
