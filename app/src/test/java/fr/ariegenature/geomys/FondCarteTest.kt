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

import fr.ariegenature.geomys.ui.FondCarte
import fr.ariegenature.geomys.ui.suivant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cycle de rotation du bouton « fond de carte » (OSM → Topo → Scan25 → Ortho → OSM). */
class FondCarteTest {

    @Test
    fun suivant_parcourt_les_quatre_fonds_en_boucle() {
        assertEquals(FondCarte.TOPO, FondCarte.OSM.suivant())
        assertEquals(FondCarte.SCAN25, FondCarte.TOPO.suivant())
        assertEquals(FondCarte.ORTHO, FondCarte.SCAN25.suivant())
        assertEquals(FondCarte.OSM, FondCarte.ORTHO.suivant())
    }

    @Test
    fun quatre_suivant_consecutifs_reviennent_au_point_de_depart() {
        var f = FondCarte.OSM
        repeat(4) { f = f.suivant() }
        assertEquals(FondCarte.OSM, f)
    }

    @Test
    fun chaque_fond_a_un_suivant_distinct_de_lui_meme() {
        FondCarte.entries.forEach { assertEquals(false, it == it.suivant()) }
    }
}
