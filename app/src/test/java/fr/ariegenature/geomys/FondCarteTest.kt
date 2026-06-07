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

/** Cycle de rotation du bouton « fond de carte »
 *  (OSM → OpenTopoMap → IGN Topo → IGN Scan25 → IGN Ortho → Esri → OSM). */
class FondCarteTest {

    @Test
    fun suivant_parcourt_tous_les_fonds_en_boucle() {
        assertEquals(FondCarte.OPENTOPO, FondCarte.OSM.suivant())
        assertEquals(FondCarte.TOPO, FondCarte.OPENTOPO.suivant())
        assertEquals(FondCarte.SCAN25, FondCarte.TOPO.suivant())
        assertEquals(FondCarte.ORTHO, FondCarte.SCAN25.suivant())
        assertEquals(FondCarte.ESRI, FondCarte.ORTHO.suivant())
        assertEquals(FondCarte.OSM, FondCarte.ESRI.suivant())
    }

    @Test
    fun le_cycle_visite_chaque_fond_une_fois_et_reboucle() {
        // Robuste à l'ajout de fonds : N suivant() reviennent au départ en visitant chaque fond.
        val visites = mutableListOf(FondCarte.OSM)
        var f = FondCarte.OSM
        repeat(FondCarte.entries.size) { f = f.suivant(); visites.add(f) }
        assertEquals(FondCarte.OSM, f)
        assertEquals(FondCarte.entries.toSet(), visites.dropLast(1).toSet())
    }

    @Test
    fun chaque_fond_a_un_suivant_distinct_de_lui_meme() {
        FondCarte.entries.forEach { assertEquals(false, it == it.suivant()) }
    }
}
