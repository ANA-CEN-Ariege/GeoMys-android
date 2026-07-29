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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.store.OccHabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Store des SAISIES OccHab : sauvegarde au fil de l'eau d'une station dans sa saisie
 * ([OccHabStore.upsertStation]), état DÉRIVÉ de la saisie (envoyée ssi toutes ses stations le
 * sont), suppression cascade / par station.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OccHabSaisieStoreTest {

    private lateinit var store: OccHabStore

    @Before
    fun setup() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        OccHabStore.reinitialiserCacheMemoire()
        ctx.getSharedPreferences("occhab_store", Context.MODE_PRIVATE).edit().clear().commit()
        store = OccHabStore(ctx)
    }

    private fun station(id: String) = OccHabStation(
        id = id, habitats = listOf(OccHabHabitat(cdHab = 1, habitatLabel = "Habitat $id")),
    )

    @Test
    fun upsert_cree_la_saisie_puis_ajoute_les_stations() {
        store.upsertStation("sa", station("A"))
        store.upsertStation("sa", station("B"))
        val saisies = store.charger()
        assertEquals(1, saisies.size)
        assertEquals("sa", saisies.single().id)
        assertEquals(listOf("A", "B"), store.stationsDeSaisie("sa").map { it.id })
    }

    @Test
    fun upsert_remplace_une_station_existante_par_son_id() {
        store.upsertStation("sa", station("A").copy(stationName = "v1"))
        store.upsertStation("sa", station("A").copy(stationName = "v2"))
        assertEquals(1, store.stationsDeSaisie("sa").size)
        assertEquals("v2", store.stationsDeSaisie("sa").single().stationName)
    }

    @Test
    fun saisie_envoyee_seulement_quand_toutes_les_stations_le_sont() {
        store.upsertStation("sa", station("A"))
        store.upsertStation("sa", station("B"))
        store.marquerStationEnvoyee("sa", "A", 100)
        assertFalse("1/2 envoyée → saisie pas envoyée", store.charger().single().envoyeGeoNature)
        store.marquerStationEnvoyee("sa", "B", 101)
        assertTrue("2/2 envoyées → saisie envoyée", store.charger().single().envoyeGeoNature)
        assertNull(store.charger().single().derniereErreurEnvoi)
        assertTrue(store.stationsDeSaisie("sa").all { it.envoyeGeoNature })
        assertEquals(100, store.stationsDeSaisie("sa").first { it.id == "A" }.idStationServeur)
    }

    @Test
    fun erreur_station_remonte_au_niveau_saisie() {
        store.upsertStation("sa", station("A"))
        store.upsertStation("sa", station("B"))
        store.marquerStationEnvoyee("sa", "A", 100)
        store.marquerStationErreur("sa", "B", "Réseau interrompu")
        val saisie = store.charger().single()
        assertFalse(saisie.envoyeGeoNature)
        assertEquals("Réseau interrompu", saisie.derniereErreurEnvoi)
    }

    @Test
    fun supprimer_station_retire_puis_supprime_la_saisie_vide() {
        store.upsertStation("sa", station("A"))
        store.upsertStation("sa", station("B"))
        store.supprimerStation("sa", "A")
        assertEquals(listOf("B"), store.stationsDeSaisie("sa").map { it.id })
        store.supprimerStation("sa", "B")
        assertTrue("saisie vide supprimée", store.charger().isEmpty())
    }

    @Test
    fun supprimer_saisie_cascade() {
        store.upsertStation("sa", station("A"))
        store.upsertStation("sb", station("C"))
        store.supprimer("sa")
        assertEquals(listOf("sb"), store.charger().map { it.id })
    }

    @Test
    fun persistance_relecture_a_froid() {
        store.upsertStation("sa", station("A"))
        store.marquerStationEnvoyee("sa", "A", 100)
        OccHabStore.reinitialiserCacheMemoire() // force la relecture disque
        val relu = OccHabStore(ApplicationProvider.getApplicationContext())
        assertEquals(1, relu.charger().size)
        assertTrue(relu.charger().single().envoyeGeoNature)
        assertEquals("A", relu.stationsDeSaisie("sa").single().id)
    }
}
