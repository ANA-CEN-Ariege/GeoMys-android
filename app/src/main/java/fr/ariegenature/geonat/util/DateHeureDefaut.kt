/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Valeurs par défaut « date du jour / heure actuelle » pour les champs date/heure des
 *  formulaires, appliquées seulement quand le serveur GeoNature ne fournit pas de défaut
 *  propre. Les formats correspondent à ce qu'attendent les renderers et le backend :
 *   - date     → "yyyy-MM-dd"          (ISO, accepté en entrée par creerChampDate)
 *   - heure    → "HH:mm"               (cf. creerChampTime)
 *   - datetime → "yyyy-MM-dd HH:mm:ss" (cf. creerChampDateTime / formatDateTime serveur)
 */
object DateHeureDefaut {
    fun dateDuJour(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun heureActuelle(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    fun dateHeureActuelle(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
