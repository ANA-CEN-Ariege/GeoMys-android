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
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.MonitoringCache
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

/** Liste des protocoles monitoring via MockWebServer : parsing + filtrage CRUVED (on n'expose
 *  que les modules avec au moins un droit > 0), gestion des codes 403/404 → liste vide. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargerModulesTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        MonitoringCache.init(ApplicationProvider.getApplicationContext())
        MonitoringCache.vider()
        config = GeoNatureConfig(ApplicationProvider.getApplicationContext()).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun router(modulesResponse: () -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/auth/login") == true ->
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                        .setBody("""{"access_token":"t","user":{"id_role":1}}""")
                request.path?.startsWith("/api/monitorings/modules") == true -> modulesResponse()
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun filtre_cruved_garde_seulement_les_modules_avec_un_droit() {
        router {
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """
                [{"id_module":1,"module_code":"STOM","module_label":"STOM","cruved":{"R":1,"U":0}},
                 {"id_module":2,"module_code":"SANS_DROIT","module_label":"X","cruved":{"R":0,"U":0,"C":0}},
                 {"id_module":3,"module_code":"LEGACY","module_label":"Legacy"}]
                """.trimIndent(),
            )
        }
        val modules = runBlocking { MonitoringApi.chargerModules(config) }
        val codes = modules.map { it.moduleCode }.toSet()
        assertTrue("STOM (droit R=1) conservé", "STOM" in codes)
        assertTrue("LEGACY (cruved absent) conservé", "LEGACY" in codes)
        assertTrue("SANS_DROIT (tous droits nuls) masqué", "SANS_DROIT" !in codes)
        assertEquals(2, modules.size)
    }

    @Test
    fun http_403_renvoie_liste_vide() {
        router { MockResponse().setResponseCode(403) }
        assertTrue(runBlocking { MonitoringApi.chargerModules(config) }.isEmpty())
    }

    @Test
    fun http_404_module_absent_renvoie_liste_vide() {
        router { MockResponse().setResponseCode(404) }
        assertTrue(runBlocking { MonitoringApi.chargerModules(config) }.isEmpty())
    }

    @Test
    fun reponse_enveloppee_dans_modules() {
        router {
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"modules":[{"id_module":9,"module_code":"A","module_label":"A","cruved":{"R":2}}]}""")
        }
        val modules = runBlocking { MonitoringApi.chargerModules(config) }
        assertEquals(listOf("A"), modules.map { it.moduleCode })
    }
}
