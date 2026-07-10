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
import fr.ariegenature.geomys.ui.libelle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Libellés des fonds de carte, affichés dans le MENU de choix (le cyclage `suivant()` a été
 *  remplacé par un menu à choix unique en v1.2.14). */
class FondCarteTest {

    @Test
    fun chaque_fond_a_un_libelle_non_vide() {
        FondCarte.entries.forEach { assertTrue("libellé vide pour $it", it.libelle().isNotBlank()) }
    }

    @Test
    fun les_libelles_sont_tous_distincts() {
        val libelles = FondCarte.entries.map { it.libelle() }
        assertEquals("libellés en double dans le menu", libelles.size, libelles.toSet().size)
    }
}
