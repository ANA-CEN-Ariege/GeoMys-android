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
import fr.ariegenature.geomys.network.GNErreur
import fr.ariegenature.geomys.network.OccHabEnvoiResult
import fr.ariegenature.geomys.network.envoyerSaisieOccHabVersGeoNature
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OccHabStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Envoi d'une SAISIE OccHab ([envoyerSaisieOccHabVersGeoNature]) : envoi partiel SANS PERTE +
 * anti-doublon. Le POST réseau est injecté par un stub (seam `envoyer`) pour couvrir les cas de
 * fiabilité identifiés à l'audit : 5xx après commit et échec de persistance → statut INCERTAIN
 * (le ré-envoi vérifiera l'existence par UUID), 4xx → échec net.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvoiOccHabTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: OccHabStore
    private lateinit var config: GeoNatureConfig

    @Before
    fun setup() {
        OccHabStore.reinitialiserCacheMemoire()
        ctx.getSharedPreferences("occhab_store", Context.MODE_PRIVATE).edit().clear().commit()
        store = OccHabStore(ctx)
        config = GeoNatureConfig(ctx)
        store.upsertStation("sa", station("A"))
        store.upsertStation("sa", station("B"))
    }

    private fun station(id: String) = OccHabStation(
        id = id, habitats = listOf(OccHabHabitat(cdHab = 1, habitatLabel = "Habitat $id")),
    )

    private fun saisie() = store.charger().first { it.id == "sa" }
    private fun stationB() = store.stationsDeSaisie("sa").first { it.id == "B" }

    @Test
    fun envoi_complet_marque_la_saisie_envoyee() = runBlocking {
        val res = envoyerSaisieOccHabVersGeoNature(saisie(), store, config) { _, _ ->
            OccHabEnvoiResult(idStationServeur = 100, nbHabitats = 1)
        }
        assertTrue(res.succes)
        assertTrue(saisie().envoyeGeoNature)
        assertTrue(store.stationsDeSaisie("sa").all { it.envoyeGeoNature })
    }

    @Test
    fun echec_4xx_de_la_2e_station_saisie_reste_a_envoyer_sans_perte() = runBlocking {
        val res = envoyerSaisieOccHabVersGeoNature(saisie(), store, config) { st, _ ->
            if (st.id == "B") throw GNErreur.EnvoiEchoue(400, "rejet")
            OccHabEnvoiResult(idStationServeur = 100, nbHabitats = 1)
        }
        assertFalse(res.succes)
        assertFalse("saisie pas envoyée (partiel)", saisie().envoyeGeoNature)
        assertTrue("A conservée envoyée", store.stationsDeSaisie("sa").first { it.id == "A" }.envoyeGeoNature)
        assertFalse(stationB().envoyeGeoNature)
        assertFalse("4xx = échec NET, pas incertain", stationB().envoiIncertain)
    }

    @Test
    fun erreur_5xx_apres_commit_serveur_passe_la_station_en_incertain() = runBlocking {
        envoyerSaisieOccHabVersGeoNature(saisie(), store, config) { st, _ ->
            if (st.id == "B") throw GNErreur.EnvoiEchoue(500, "boom")
            OccHabEnvoiResult(idStationServeur = 100, nbHabitats = 1)
        }
        assertFalse(stationB().envoyeGeoNature)
        assertTrue("5xx (peut-être créée) = incertain → ré-envoi vérifiera par UUID", stationB().envoiIncertain)
    }

    @Test
    fun envoi_incertain_reseau_propage_l_etat() = runBlocking {
        envoyerSaisieOccHabVersGeoNature(saisie(), store, config) { st, _ ->
            if (st.id == "B") throw GNErreur.EnvoiIncertain("réseau coupé")
            OccHabEnvoiResult(idStationServeur = 100, nbHabitats = 1)
        }
        assertTrue(stationB().envoiIncertain)
    }

    @Test
    fun re_envoi_ne_repousse_que_les_stations_restantes() = runBlocking {
        // A déjà envoyée, B en attente : un ré-envoi ne doit POSTer que B (anti-doublon de A).
        store.marquerStationEnvoyee("sa", "A", 100)
        val appelees = mutableListOf<String>()
        val res = envoyerSaisieOccHabVersGeoNature(saisie(), store, config) { st, _ ->
            appelees.add(st.id)
            OccHabEnvoiResult(idStationServeur = 200, nbHabitats = 1)
        }
        assertEquals("seule B est (re)postée", listOf("B"), appelees)
        assertTrue(res.succes)
        assertTrue(saisie().envoyeGeoNature)
    }
}
