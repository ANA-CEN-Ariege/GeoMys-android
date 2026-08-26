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
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.network.OccHabUpload
import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Anti-doublon OccHab : la station porte un `unique_id_sinp_station` stable ; après un envoi
 * INCERTAIN (réponse perdue), le ré-envoi retrouve la station déjà créée par cet UUID au lieu
 * d'en poster une seconde. On verrouille ici la fonction de matching sur les DEUX formats que
 * le serveur OccHab peut renvoyer (`format=json` = tableau plat ; repli GeoJSON = features),
 * PUIS le chemin de MISE À JOUR (station avec id serveur → POST /stations/<id>/, id_habitat
 * dans le payload, re-POST direct après un update incertain) via MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OccHabAntiDoublonTest {

    private val uuid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

    // Réponse GET /stations/?format=json : tableau d'objets, id + unique_id_sinp_station à plat.
    private val reponseJson = """
        [
          {"id_station": 11, "unique_id_sinp_station": "aaaaaaaa-0000-0000-0000-000000000000"},
          {"id_station": 42, "unique_id_sinp_station": "$uuid"}
        ]
    """.trimIndent()

    // Repli GeoJSON (FeatureCollection) : l'UUID est dans properties.
    private val reponseGeojson = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"id_station": 42, "unique_id_sinp_station": "$uuid"}}
        ]}
    """.trimIndent()

    @Test
    fun `format json — station trouvee par UUID`() {
        assertEquals(42, OccHabUpload.trouverIdParUuid(reponseJson, uuid))
    }

    @Test
    fun `repli geojson — station trouvee par UUID`() {
        assertEquals(42, OccHabUpload.trouverIdParUuid(reponseGeojson, uuid))
    }

    @Test
    fun `comparaison UUID insensible a la casse`() {
        assertEquals(42, OccHabUpload.trouverIdParUuid(reponseJson, uuid.uppercase()))
    }

    @Test
    fun `UUID absent — aucun match (on postera)`() {
        assertNull(OccHabUpload.trouverIdParUuid(reponseJson, "99999999-9999-9999-9999-999999999999"))
    }

    @Test
    fun `liste vide — aucun match`() {
        assertNull(OccHabUpload.trouverIdParUuid("[]", uuid))
    }

    @Test
    fun `reponse illisible — aucun match plutot que crash`() {
        assertNull(OccHabUpload.trouverIdParUuid("<html>502 Bad Gateway</html>", uuid))
    }

    // ── MISE À JOUR d'une station portant un id serveur (import du serveur / rééditée) ──────

    /** Serveur factice : login + POST création/update + GET liste (comptés pour vérifier que
     *  l'update ne déclenche PAS la vérification d'existence par UUID). */
    private fun serveurOcchab(bloc: (MockWebServer, GeoNatureConfig) -> Unit) {
        val server = MockWebServer().apply { start() }
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    val json = MockResponse().setHeader("Content-Type", "application/json")
                    return when {
                        path.startsWith("/api/auth/login") ->
                            json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                        path.startsWith("/api/occhab/stations") && request.method == "POST" ->
                            json.setResponseCode(200)
                                .setBody("""{"type":"Feature","id":42,"properties":{"id_station":42}}""")
                        path.startsWith("/api/occhab/stations") && request.method == "GET" ->
                            json.setResponseCode(200).setBody("[]")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val config = GeoNatureConfig(ApplicationProvider.getApplicationContext()).apply {
                urlServeur = server.url("/").toString().trimEnd('/')
                login = "alice"; motDePasse = "pwd"; idDataset = "12"
            }
            bloc(server, config)
        } finally {
            server.shutdown()
        }
    }

    /** Toutes les requêtes /api/occhab enregistrées par le serveur factice ("METHOD chemin"). */
    private fun requetesOcchab(server: MockWebServer): List<Pair<String, String>> =
        (0 until server.requestCount).mapNotNull {
            val r = server.takeRequest()
            val chemin = r.path ?: return@mapNotNull null
            if (!chemin.startsWith("/api/occhab")) null else (r.method ?: "?") to chemin
        }

    private fun stationServeur() = OccHabStation(
        uuidStation = uuid,
        idStationServeur = 42,
        idDataset = 12,
        habitats = listOf(
            // Habitat EXISTANT côté serveur : son id_habitat + uuid doivent repartir (update).
            OccHabHabitat(idHabitatServeur = 91, uuidHabitat = "aaaaaaaa-0000-0000-0000-000000000001",
                cdHab = 629, nomCite = "Prairie de fauche"),
            // Habitat AJOUTÉ localement : pas d'id → le serveur le crée.
            OccHabHabitat(cdHab = 630, nomCite = "Pelouse"),
        ),
    )

    @Test
    fun `update nominal — POST sur stations id et id_habitat dans le payload`() = serveurOcchab { server, config ->
        val res = runBlocking { OccHabUpload.envoyer(stationServeur(), config) }
        assertEquals(42, res.idStationServeur)
        assertFalse(res.dejaPresente)

        val posts = requetesOcchabAvecCorps(server)
        assertEquals("un seul POST, sur /stations/42/", listOf("POST /api/occhab/stations/42/"),
            posts.map { "${it.first} ${it.second}" })
        val props = JSONObject(posts.single().third).getJSONObject("properties")
        assertEquals("l'UUID SINP serveur est renvoyé tel quel", uuid, props.getString("unique_id_sinp_station"))
        val habs = props.getJSONArray("habitats")
        assertEquals(2, habs.length())
        assertEquals("habitat existant → id_habitat renvoyé (mis à jour, pas dupliqué)",
            91, habs.getJSONObject(0).getInt("id_habitat"))
        assertEquals("aaaaaaaa-0000-0000-0000-000000000001",
            habs.getJSONObject(0).getString("unique_id_sinp_hab"))
        assertFalse("habitat ajouté localement → pas d'id_habitat (création côté serveur)",
            habs.getJSONObject(1).has("id_habitat"))
    }

    @Test
    fun `update apres incertain — re-POST direct sans verification par UUID`() = serveurOcchab { server, config ->
        // Un update incertain se re-POSTe tel quel : /stations/<id>/ est IDEMPOTENT (le serveur
        // recharge la même instance) — la vérification d'existence par UUID ne sert qu'à la
        // CRÉATION (où un re-POST aveugle dupliquerait).
        val res = runBlocking {
            OccHabUpload.envoyer(stationServeur().copy(envoiIncertain = true), config)
        }
        assertEquals(42, res.idStationServeur)
        assertFalse("pas de court-circuit « déjà présente » en update", res.dejaPresente)
        val reqs = requetesOcchab(server)
        assertEquals("aucun GET de vérification, un seul POST d'update",
            listOf("POST" to "/api/occhab/stations/42/"), reqs)
    }

    @Test
    fun `creation — jamais d'id_habitat dans le payload meme s'il est connu`() = serveurOcchab { server, config ->
        // Garde-fou : en CRÉATION (pas d'id station serveur), un id_habitat résiduel ne doit
        // JAMAIS partir — le serveur rejetterait un habitat appartenant à une autre station
        // (validate_habitats de StationSchema).
        val station = stationServeur().copy(idStationServeur = null)
        runBlocking { OccHabUpload.envoyer(station, config) }
        val posts = requetesOcchabAvecCorps(server)
        assertEquals(listOf("POST /api/occhab/stations/"), posts.map { "${it.first} ${it.second}" })
        val habs = JSONObject(posts.single().third).getJSONObject("properties").getJSONArray("habitats")
        for (i in 0 until habs.length()) {
            assertFalse(habs.getJSONObject(i).has("id_habitat"))
            assertFalse(habs.getJSONObject(i).has("unique_id_sinp_hab"))
        }
    }

    /** Les POST /api/occhab enregistrés, avec leur corps : (méthode, chemin, corps). */
    private fun requetesOcchabAvecCorps(server: MockWebServer): List<Triple<String, String, String>> =
        (0 until server.requestCount).mapNotNull {
            val r = server.takeRequest()
            val chemin = r.path ?: return@mapNotNull null
            if (!chemin.startsWith("/api/occhab") || r.method != "POST") null
            else Triple(r.method ?: "?", chemin, r.body.readUtf8())
        }
}
