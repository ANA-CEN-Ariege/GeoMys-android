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
import fr.ariegenature.geomys.network.GNErreur
import fr.ariegenature.geomys.network.MonitoringDatalists
import fr.ariegenature.geomys.network.MonitoringEnvoi
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.MonitoringCache
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Envoi d'une saisie monitoring ([MonitoringEnvoi.envoyerVisite]) : contrat HTTP et payload
 * (parent, typage des valeurs, texte libre préservé, uuid client, id_digitiser, champs du schéma
 * absents → null / medias → []), échec HTTP → Result.failure ; et cache hors-ligne des
 * observateurs ([MonitoringDatalists.chargerObservateursDeListe]) : live → cache, panne → cache,
 * portail captif → cache PRÉSERVÉ. Trous de couverture relevés par l'audit 2026-08-27.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitoringEnvoiTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig

    @Before
    fun setup() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        MonitoringCache.init(ctx)
        MonitoringCache.vider()
        server = MockWebServer().apply { start() }
        config = GeoNatureConfig(ctx).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"; idDataset = "12"
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun login(json: MockResponse) =
        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")

    @Test
    fun envoyer_visite_payload_et_id_serveur() {
        val corps = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") -> login(json)
                    path == "/api/monitorings/object/stom/visit" && request.method == "POST" -> {
                        corps.add(request.body.readUtf8()); json.setResponseCode(200).setBody("""{"id":77}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val res = runBlocking {
            MonitoringEnvoi.envoyerVisite(
                config, moduleCode = "stom", objectType = "visit",
                parentIdField = "id_base_site", parentId = 5,
                valeurs = mapOf("comments" to "42", "nb" to "3", "id_dataset" to 12, "vide" to ""),
                nomsChampsSchema = listOf("comments", "nb", "visit_date_min", "medias"),
                champsTexteLibre = listOf("comments"),
                uuidClient = "u-1", uuidFieldName = "uuid_base_visit",
            )
        }
        assertEquals(77, res.getOrThrow())
        val props = JSONObject(corps.single()).getJSONObject("properties")
        assertEquals(5, props.getInt("id_base_site"))
        assertEquals("texte libre préservé", "42", props.getString("comments"))
        assertEquals("numérique coercé", 3, props.getInt("nb"))
        assertEquals("u-1", props.getString("uuid_base_visit"))
        assertEquals("id_digitiser = id_role du login", 1, props.getInt("id_digitiser"))
        assertEquals(12, props.getInt("id_dataset"))
        assertTrue("champ du schéma absent → null explicite", props.isNull("visit_date_min"))
        assertEquals("medias absent → tableau vide", 0, props.getJSONArray("medias").length())
        assertTrue("valeur vide retirée", !props.has("vide"))
    }

    @Test
    fun envoyer_visite_echec_http_est_un_failure_type() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return if (path.startsWith("/api/auth/login")) login(json)
                else json.setResponseCode(500).setBody("""{"description":"boom"}""")
            }
        }
        val res = runBlocking {
            MonitoringEnvoi.envoyerVisite(config, "stom", "visit", null, null, mapOf("id_dataset" to 12))
        }
        assertTrue(res.isFailure)
        val e = res.exceptionOrNull()
        assertTrue(e is GNErreur.EnvoiEchoue)
        assertEquals(500, (e as GNErreur.EnvoiEchoue).code)
    }

    @Test
    fun observateurs_live_puis_cache_hors_ligne_et_portail_captif_ignore() {
        var reponse: () -> MockResponse = {
            MockResponse().setHeader("Content-Type", "application/json").setResponseCode(200)
                .setBody("""[{"id_role":7,"nom_complet":"DUPONT jean"}]""")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return if (path.startsWith("/api/auth/login")) login(json)
                else if (path.startsWith("/api/users/menu/9")) reponse()
                else MockResponse().setResponseCode(404)
            }
        }
        val key = MonitoringCache.keyObservateurs(9)
        val live = runBlocking { MonitoringDatalists.chargerObservateursDeListe(config, 9) }
        assertNotNull(live)
        assertEquals(7, live!!.getJSONObject(0).getInt("id_role"))
        assertNotNull("écrit dans le cache hors-ligne", MonitoringCache.getJson(key))

        reponse = { MockResponse().setResponseCode(500) }
        val horsLigne = runBlocking { MonitoringDatalists.chargerObservateursDeListe(config, 9) }
        assertEquals("panne serveur → cache", 1, horsLigne!!.length())

        reponse = { MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>wifi de la gare</html>") }
        val captif = runBlocking { MonitoringDatalists.chargerObservateursDeListe(config, 9) }
        assertEquals("200 non-JSON → cache servi, jamais écrasé", 1, captif!!.length())
        assertTrue(MonitoringCache.getJson(key)!!.trimStart().startsWith("["))
    }
}
