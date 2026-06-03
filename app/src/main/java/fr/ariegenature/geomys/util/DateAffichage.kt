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

/** Formatage pour AFFICHAGE des dates ISO renvoyées par GeoNature (ex. `2026-05-21`,
 *  `2026-05-21T11:12:06`). Heuristique volontairement légère (test des séparateurs en
 *  positions 4 et 7) plutôt qu'un parse complet : les valeurs serveur sont toujours en ISO,
 *  et toute autre chaîne est rendue telle quelle. */
object DateAffichage {

    /** Vrai si [v] commence par une date ISO `YYYY-MM-DD`. */
    private fun estIso(v: String): Boolean =
        v.length >= 10 && v[4] == '-' && v[7] == '-'

    /** `YYYY-MM-DD[...]` → `JJ/MM/AAAA`. Toute autre chaîne est renvoyée telle quelle. */
    fun isoVersFr(v: String): String =
        if (estIso(v)) "${v.substring(8, 10)}/${v.substring(5, 7)}/${v.substring(0, 4)}" else v

    /** Tronque une date-heure ISO à sa seule partie date `YYYY-MM-DD`. Toute autre chaîne est
     *  renvoyée telle quelle. */
    fun isoTronquerDate(v: String): String =
        if (estIso(v)) v.substring(0, 10) else v
}
