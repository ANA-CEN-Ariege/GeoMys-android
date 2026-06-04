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
import fr.ariegenature.geomys.network.MonitoringApi
import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** Datalist `dataset` ([MonitoringApi.chargerOptionsDatalist]) : le filtre `create=` (CRUVED
 *  action C) renvoie vide sur certaines instances alors que des jeux de données sont bien
 *  rattachés au module — bug terrain « rien dans le champ jeu de données » (Point écoute
 *  avifaune), qui bloquait toute saisie (champ requis). On retente alors sans `create`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitoringDatalistDatasetTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig

    /** Corps des POST /meta/datasets reçus, dans l'ordre. */
    private val corpsRecus = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        corpsRecus.clear()
        config = GeoNatureConfig(ApplicationProvider.getApplicationContext()).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"; idDataset = "12"
            datasetsCacheJson = ""
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.startsWith("/api/meta/datasets") -> {
                        val corps = request.body.readUtf8()
                        corpsRecus.add(corps)
                        // Le filtre `create` renvoie VIDE (objet de permission absent du
                        // protocole sur cette instance) ; sans lui, le dataset du module sort.
                        if (corps.contains("\"create\""))
                            json.setResponseCode(200).setBody("[]")
                        else
                            json.setResponseCode(200)
                                .setBody("""[{"id_dataset":12,"dataset_name":"Suivi avifaune ANA"}]""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun propDataset() = MonitoringApi.MonitoringPropertySchema(
        nom = "id_dataset",
        typeWidget = "dataset",
        label = "Jeu de données",
        obligatoire = true,
        apiUrl = "meta/datasets?module_code=pt_ecoute_avifaune&active=true&create=pt_ecoute_avifaune.MONITORINGS_VISITES",
        keyLabel = "dataset_name",
        keyValue = "id_dataset",
    )

    @Test
    fun dataset_vide_avec_create_est_retente_sans_create() {
        val options = runBlocking { MonitoringApi.chargerOptionsDatalist(config, propDataset()) }
        assertEquals("le dataset du module doit sortir via le repli sans create",
            listOf("12" to "Suivi avifaune ANA"), options?.map { it.value to it.label })
        assertEquals("2 tentatives : avec create (vide) puis sans", 2, corpsRecus.size)
        assertEquals("la 1re porte le filtre create", true, corpsRecus[0].contains("\"create\""))
        assertEquals("la 2e ne le porte plus", false, corpsRecus[1].contains("\"create\""))
    }

    @Test
    fun dataset_non_vide_avec_create_ne_retente_pas() {
        // Le serveur honore le filtre create : une seule tentative suffit.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.startsWith("/api/meta/datasets") -> {
                        corpsRecus.add(request.body.readUtf8())
                        json.setResponseCode(200)
                            .setBody("""[{"id_dataset":12,"dataset_name":"Suivi avifaune ANA"}]""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val options = runBlocking { MonitoringApi.chargerOptionsDatalist(config, propDataset()) }
        assertEquals(1, options?.size)
        assertEquals("une seule tentative", 1, corpsRecus.size)
    }
}