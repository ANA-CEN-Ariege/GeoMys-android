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

package fr.ariegenature.geonat

import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geonat.model.Sortie
import fr.ariegenature.geonat.store.SortieStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Persistance des sorties OccTax (SharedPreferences + Gson). Test Robolectric : un vrai
 *  Context Android en JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SortieStoreTest {

    private lateinit var store: SortieStore

    @Before
    fun setup() {
        store = SortieStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun store_vide_au_depart() {
        assertTrue(store.charger().isEmpty())
    }

    @Test
    fun ajouter_puis_charger_roundtrip() {
        store.ajouter(Sortie(id = "s1", distanceTotale = 120.0))
        store.ajouter(Sortie(id = "s2"))
        val sorties = store.charger()
        assertEquals(2, sorties.size)
        // ajouter insère en tête → s2 d'abord.
        assertEquals("s2", sorties[0].id)
        assertEquals(120.0, sorties.first { it.id == "s1" }.distanceTotale, 1e-9)
    }

    @Test
    fun marquer_envoyee_met_a_jour_le_flag() {
        store.ajouter(Sortie(id = "s1"))
        assertFalse(store.charger().first().envoyeGeoNature)
        store.marquerEnvoyee("s1")
        assertTrue(store.charger().first { it.id == "s1" }.envoyeGeoNature)
    }

    @Test
    fun remplacer_preserve_la_position() {
        store.ajouter(Sortie(id = "a"))
        store.ajouter(Sortie(id = "b")) // ordre : [b, a]
        store.remplacer("a", Sortie(id = "a", distanceTotale = 999.0))
        val sorties = store.charger()
        assertEquals(listOf("b", "a"), sorties.map { it.id })
        assertEquals(999.0, sorties.first { it.id == "a" }.distanceTotale, 1e-9)
    }

    @Test
    fun remplacer_id_inexistant_ajoute_en_tete() {
        store.ajouter(Sortie(id = "a"))
        store.remplacer("zzz", Sortie(id = "zzz"))
        assertEquals("zzz", store.charger()[0].id)
    }

    @Test
    fun supprimer_retire_la_sortie() {
        store.ajouter(Sortie(id = "a"))
        store.ajouter(Sortie(id = "b"))
        store.supprimer("a")
        val sorties = store.charger()
        assertEquals(1, sorties.size)
        assertEquals("b", sorties[0].id)
    }
}
