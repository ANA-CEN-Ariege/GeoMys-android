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
import fr.ariegenature.geomys.network.GeoNatureAuth
import fr.ariegenature.geomys.network.GeoNatureSync
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NomenclatureCache
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Garde-fous de compatibilité serveur : version de l'instance GeoNature relevée au test
 *  de connexion (`/api/gn_commons/config`, best-effort — absent des vieilles versions) et
 *  avertissement sur les types de nomenclatures obligatoires absents après sync (sans quoi
 *  les envois omettent ces champs sans aucun signal utilisateur). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompatibiliteServeurTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig

    private val typesObligatoires = listOf(
        "METH_OBS", "STATUT_OBS", "SEXE", "STADE_VIE", "STATUT_BIO", "ETA_BIO",
        "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN", "TYPE_MEDIA",
    )

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        NomenclatureCache.init(ApplicationProvider.getApplicationContext())
        NomenclatureCache.vider()
        config = GeoNatureConfig(ApplicationProvider.getApplicationContext()).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"; idDataset = "12"
            versionGeoNatureServeur = ""
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setResponseCode(200).setBody(body)

    /** Routes login + config serveur. [configServeur] null = endpoint absent (404, vieux GeoNature). */
    private fun router(configServeur: String?, taxonomy: String? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/api/auth/login") ->
                        json("""{"access_token":"t","user":{"id_role":1}}""")
                    path.startsWith("/api/gn_commons/config") ->
                        configServeur?.let { json(it) } ?: MockResponse().setResponseCode(404)
                    path.contains("/nomenclatures/") && path.contains("taxonomy") ->
                        taxonomy?.let { json(it) } ?: MockResponse().setResponseCode(404)
                    path.contains("/defaultNomenclatures") -> json("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    /** Payload taxonomy pour les [types] donnés (1 valeur par type). */
    private fun payloadTaxonomy(types: List<String>): String = types.joinToString(",", "[", "]") {
        """{"mnemonique":"$it","nomenclatures":[{"id_nomenclature":1,"label_default":"x"}]}"""
    }

    // ── Version de l'instance GeoNature ────────────────────────────────────────────

    @Test
    fun test_connexion_releve_et_memorise_la_version_du_serveur() {
        router(configServeur = """{"GEONATURE_VERSION":"2.14.2"}""")
        val (ok, msg) = runBlocking { GeoNatureAuth.testerConnexion(config) }
        assertTrue(ok)
        assertTrue("le message doit afficher la version : $msg", msg.contains("GeoNature v2.14.2"))
        assertEquals("2.14.2", config.versionGeoNatureServeur)
    }

    @Test
    fun vieux_serveur_sans_endpoint_config_reste_un_succes_sans_version() {
        router(configServeur = null)  // 404 — endpoint absent des vieilles versions
        val (ok, msg) = runBlocking { GeoNatureAuth.testerConnexion(config) }
        assertTrue("l'absence de version ne doit pas faire échouer le test de connexion", ok)
        assertEquals("Connexion réussie", msg)
        assertEquals("", config.versionGeoNatureServeur)
    }

    @Test
    fun config_html_ou_cle_inconnue_ne_casse_pas_le_test_de_connexion() {
        router(configServeur = """{"autre_cle":"valeur"}""")
        val (ok, msg) = runBlocking { GeoNatureAuth.testerConnexion(config) }
        assertTrue(ok)
        assertFalse(msg.contains("GeoNature v"))
    }

    // ── Types de nomenclatures obligatoires ────────────────────────────────────────

    @Test
    fun sync_complete_des_12_types_sans_avertissement() {
        router(configServeur = null, taxonomy = payloadTaxonomy(typesObligatoires))
        val (total, msg) = runBlocking { GeoNatureSync.synchroniserNomenclatures(config) }
        assertEquals(12, total)
        assertFalse("aucun avertissement attendu : $msg", msg.contains("⚠"))
    }

    @Test
    fun types_manquants_apres_sync_sont_signales_explicitement() {
        // Serveur incomplet : seuls SEXE et STATUT_OBS exposés.
        router(configServeur = null, taxonomy = payloadTaxonomy(listOf("SEXE", "STATUT_OBS")))
        val (total, msg) = runBlocking { GeoNatureSync.synchroniserNomenclatures(config) }
        assertEquals(2, total)
        assertTrue("avertissement attendu : $msg", msg.contains("⚠"))
        assertTrue("les types manquants doivent être listés : $msg",
            msg.contains("METH_OBS") && msg.contains("TYPE_MEDIA"))
        assertTrue("le risque doit être explicité : $msg", msg.contains("omis"))
        assertFalse("les types présents ne sont pas dans la liste des manquants",
            msg.substringAfter("⚠").contains("SEXE,"))
    }
}