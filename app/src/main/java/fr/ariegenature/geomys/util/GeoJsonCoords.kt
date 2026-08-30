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

package fr.ariegenature.geomys.util

import org.json.JSONArray
import org.osmdroid.util.GeoPoint

/**
 * Sommets d'une géométrie au format interne `geometryCoordsJson` = `[[lon, lat], …]` (même
 * convention pour Occtax et OccHab, parsée à l'envoi par `GeoNatureUpload.construireGeometrie`).
 * Point unique de parsing / sérialisation / centroïde : ce code était copié 12 fois dans les
 * fragments carte, sans test (audit 2026-08-27).
 */
object GeoJsonCoords {

    /** `[[lon,lat], …]` → GeoPoints. Vide si null, illisible, ou sans paire exploitable ; une paire
     *  malformée est ignorée, les autres conservées. */
    fun parse(json: String?): List<GeoPoint> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONArray(i)?.takeIf { it.length() >= 2 }?.let { c ->
                    try { GeoPoint(c.getDouble(1), c.getDouble(0)) } catch (_: Exception) { null }
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    /** `[[lon,lat], …]` → paires `[lon, lat]` (DoubleArray, modifiables en place — édition
     *  topologique). Null si le JSON est illisible. */
    fun parsePaires(json: String?): MutableList<DoubleArray>? {
        if (json.isNullOrEmpty()) return null
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { i ->
                val c = arr.getJSONArray(i)
                doubleArrayOf(c.getDouble(0), c.getDouble(1))
            }
        } catch (_: Exception) { null }
    }

    /** GeoPoints → `[[lon,lat], …]`. */
    fun format(points: List<GeoPoint>): String = JSONArray().apply {
        points.forEach { put(JSONArray().put(it.longitude).put(it.latitude)) }
    }.toString()

    /** Paires `[lon, lat]` → `[[lon,lat], …]`. */
    fun formatPaires(paires: List<DoubleArray>): String = JSONArray().apply {
        paires.forEach { put(JSONArray().put(it[0]).put(it[1])) }
    }.toString()

    /** Centroïde = moyenne arithmétique des sommets (la convention historique de l'appli pour
     *  `latitude`/`longitude` d'un polygone : affichage carte, pas un calcul géodésique).
     *  Null si aucun sommet. */
    fun centroide(points: List<GeoPoint>): GeoPoint? {
        if (points.isEmpty()) return null
        return GeoPoint(points.map { it.latitude }.average(), points.map { it.longitude }.average())
    }
}
