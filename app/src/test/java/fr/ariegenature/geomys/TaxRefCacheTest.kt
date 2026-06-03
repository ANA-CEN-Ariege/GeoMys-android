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

import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.store.TaxRefCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cache TaxRef : appartenance d'un cd_nom aux listes UsersHub (alimente la visibilité des
 *  champs additionnels par taxon) + comptes par groupe (alimente la branche PLANTE). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxRefCacheTest {

    @Before
    fun setup() {
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
        TaxRefCache.vider()
    }

    @Test
    fun listes_pour_cd_nom_roundtrip() {
        TaxRefCache.ajouterListesParCdNom(mapOf(60585 to listOf(100, 200), 4001 to listOf(100)))
        assertEquals(listOf(100, 200), TaxRefCache.listesPourCdNom(60585))
        assertEquals(listOf(100), TaxRefCache.listesPourCdNom(4001))
    }

    @Test
    fun cd_nom_inconnu_renvoie_liste_vide() {
        assertTrue(TaxRefCache.listesPourCdNom(999999).isEmpty())
    }

    @Test
    fun cd_noms_dans_liste_inverse_l_index() {
        TaxRefCache.ajouterListesParCdNom(mapOf(1 to listOf(100), 2 to listOf(100, 200), 3 to listOf(200)))
        assertEquals(setOf(1, 2), TaxRefCache.cdNomsDansListe(100))
        assertEquals(setOf(2, 3), TaxRefCache.cdNomsDansListe(200))
        assertTrue(TaxRefCache.cdNomsDansListe(999).isEmpty())
    }

    @Test
    fun comptes_groupes_roundtrip() {
        TaxRefCache.comptesGroupes = mapOf("Oiseaux" to 320, "Angiospermes" to 1500)
        assertEquals(320, TaxRefCache.comptesGroupes["Oiseaux"])
        assertEquals(1500, TaxRefCache.comptesGroupes["Angiospermes"])
    }

    @Test
    fun vider_remet_a_zero() {
        TaxRefCache.ajouterListesParCdNom(mapOf(1 to listOf(100)))
        TaxRefCache.comptesGroupes = mapOf("X" to 1)
        TaxRefCache.vider()
        assertTrue(TaxRefCache.listesPourCdNom(1).isEmpty())
        assertTrue(TaxRefCache.comptesGroupes.isEmpty())
    }
}
