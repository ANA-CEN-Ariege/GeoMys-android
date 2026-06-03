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

import org.json.JSONArray
import org.json.JSONObject

/** Parse une réponse JSON GeoNature en tableau, en absorbant l'hétérogénéité du serveur : la
 *  réponse est soit un **array direct**, soit un **objet enveloppant** dont l'une des
 *  [clesPossibles] (ex. `data`, `items`, `results`, `values`, `features`…) porte le tableau.
 *  Retourne `null` si le texte n'est ni un array ni un objet contenant l'une de ces clés —
 *  l'appelant décide alors (throw, fallback cache, liste vide…).
 *
 *  L'ordre des clés reflète la priorité ; passe-les dans l'ordre attendu du endpoint visé. */
internal fun String.parserTableauJson(vararg clesPossibles: String): JSONArray? =
    try {
        JSONArray(this)
    } catch (_: Exception) {
        val obj = try { JSONObject(this) } catch (_: Exception) { return null }
        clesPossibles.firstNotNullOfOrNull { obj.optJSONArray(it) }
    }
