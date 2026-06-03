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
import fr.ariegenature.geomys.network.AdditionalFieldsApi
import fr.ariegenature.geomys.network.WidgetType
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NomenclatureCache
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bout-en-bout (auth + GET) du chargement des champs additionnels OccTax via MockWebServer.
 *  Vérifie le contrat HTTP : 404 → liste vide, 200 → parsing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdditionalFieldsChargerTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        NomenclatureCache.init(ApplicationProvider.getApplicationContext())
        NomenclatureCache.vider()
        NomenclatureCache.setDefauts(emptyMap())
        config = GeoNatureConfig(ApplicationProvider.getApplicationContext()).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"
            motDePasse = "pwd"
        }
    }

    @After fun tearDown() { server.shutdown() }

    /** Répond au login pour toutes les requêtes /api/auth/login, et délègue le reste à [pourReste]. */
    private fun router(pourReste: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.startsWith("/api/auth/login") == true)
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"access_token":"t","user":{"id_role":1}}""")
                else pourReste(request)
        }
    }

    @Test
    fun http_404_renvoie_liste_vide() {
        router { MockResponse().setResponseCode(404) }
        val defs = runBlocking { AdditionalFieldsApi.charger(config, "OCCTAX") }
        assertTrue(defs.isEmpty())
    }

    @Test
    fun http_200_parse_les_champs() {
        router {
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [{"id_field":1,"field_name":"altitude","field_label":"Altitude",
                      "type_widget":"number","objects":[{"code_object":"OCCTAX_RELEVE"}]},
                     {"id_field":2,"field_name":"meteo","type_widget":"select",
                      "field_values":["soleil","pluie"]}]
                    """.trimIndent(),
                )
        }
        val defs = runBlocking { AdditionalFieldsApi.charger(config, "OCCTAX") }
        assertEquals(2, defs.size)
        assertEquals("altitude", defs[0].fieldName)
        assertEquals(WidgetType.NUMBER, defs[0].widget)
        assertTrue("OCCTAX_RELEVE" in defs[0].objectsCode)
        assertEquals(listOf("soleil", "pluie"), defs[1].fieldValues)
    }

    @Test
    fun reponse_enveloppee_dans_data() {
        router {
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id_field":3,"field_name":"x","type_widget":"text"}]}""")
        }
        val defs = runBlocking { AdditionalFieldsApi.charger(config, "OCCTAX") }
        assertEquals(1, defs.size)
        assertEquals("x", defs[0].fieldName)
    }
}
