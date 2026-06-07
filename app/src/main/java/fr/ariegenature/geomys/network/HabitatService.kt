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
 *  Tolérant : terme trop court, erreur réseau ou JSON inattendu → liste vide (le champ reste
 *  simplement sans suggestion, jamais de crash). */
object HabitatService {

    suspend fun rechercher(base: String, terme: String, limite: Int = 20): List<HabitatSuggestion> =
        withContext(Dispatchers.IO) {
            val t = terme.trim()
            if (t.length < 2) return@withContext emptyList()
            val urlBase = base.trim().trimEnd('/')
            val url = URL(
                "$urlBase/api/habref/habitats/autocomplete" +
                    "?search_name=${URLEncoder.encode(t, "UTF-8")}&limit=$limite"
            )
            val conn = HttpClient.get(url, timeoutMs = 8000)
            try {
                if (conn.responseCode != 200) return@withContext emptyList()
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val cd = o.optInt("cd_hab", -1)
                    if (cd <= 0) return@mapNotNull null
                    val libelle = o.optString("search_name").ifBlank { o.optString("lb_code") }
                        .trim().ifBlank { cd.toString() }
                    HabitatSuggestion(cd, libelle)
                }
            } catch (_: Exception) {
                emptyList()
            } finally {
                conn.disconnect()
            }
        }
}
