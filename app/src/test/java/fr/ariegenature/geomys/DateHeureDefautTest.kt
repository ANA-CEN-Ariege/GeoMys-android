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

package fr.ariegenature.geomys

import fr.ariegenature.geomys.util.DateHeureDefaut
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vérifie que les défauts date/heure produisent les formats exacts attendus par les
 *  renderers et le backend GeoNature (cf. creerChampDate / creerChampTime / creerChampDateTime). */
class DateHeureDefautTest {

    @Test
    fun dateDuJour_formatISO_aaaa_mm_jj() {
        assertTrue(
            "format attendu yyyy-MM-dd, obtenu ${DateHeureDefaut.dateDuJour()}",
            DateHeureDefaut.dateDuJour().matches(Regex("""\d{4}-\d{2}-\d{2}""")),
        )
    }

    @Test
    fun heureActuelle_format24h_hh_mm() {
        val h = DateHeureDefaut.heureActuelle()
        assertTrue("format attendu HH:mm, obtenu $h", h.matches(Regex("""\d{2}:\d{2}""")))
        val (hh, mm) = h.split(":").map { it.toInt() }
        assertTrue("heure 0..23", hh in 0..23)
        assertTrue("minute 0..59", mm in 0..59)
    }

    @Test
    fun dateHeureActuelle_format_complet_avec_secondes() {
        assertTrue(
            "format attendu yyyy-MM-dd HH:mm:ss, obtenu ${DateHeureDefaut.dateHeureActuelle()}",
            DateHeureDefaut.dateHeureActuelle().matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")),
        )
    }

    @Test
    fun dateHeure_commence_par_la_date_du_jour() {
        // Les deux helpers lisent l'horloge à des instants différents : on tolère un
        // changement de jour improbable en comparant juste le préfixe date du datetime.
        assertTrue(DateHeureDefaut.dateHeureActuelle().startsWith(DateHeureDefaut.dateDuJour()))
    }
}
