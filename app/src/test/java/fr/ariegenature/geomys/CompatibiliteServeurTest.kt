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
import fr.ariegenature.geomys.network.VERSION_GEONATURE_MINIMALE
import fr.ariegenature.geomys.network.comparerVersions
import fr.ariegenature.geomys.network.versionGeoNatureSupportee
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
        "NATURALITE",
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
        router(configServeur = """{"GEONATURE_VERSION":"2.16.4"}""")
        val (ok, msg) = runBlocking { GeoNatureAuth.testerConnexion(config) }
        assertTrue(ok)
        assertTrue("le message doit afficher la version : $msg", msg.contains("GeoNature v2.16.4"))
        assertEquals("2.16.4", config.versionGeoNatureServeur)
    }

    @Test
    fun vieux_serveur_sans_endpoint_config_reste_un_succes_avec_note() {
        router(configServeur = null)  // 404 — endpoint absent des vieilles versions / proxy filtrant
        val (ok, msg) = runBlocking { GeoNatureAuth.testerConnexion(config) }
        assertTrue("l'absence de version ne doit pas faire échouer le test (bénéfice du doute)", ok)
        assertTrue("une note doit signaler la version non détectée : $msg",
            msg.contains("non détectée"))
        assertEquals("", config.versionGeoNatureServeur)
        assertTrue("bénéfice du doute : la config reste utilisable", config.serveurCompatible)
    }

    @Test
    fun config_html_ou_cle_inconnue_ne_casse_pas_le_test_de_connexion() {
        router(configServeur = """{"autre_cle":"valeur"}""")
        val (ok, msg) = runBlocking { GeoNatureAuth.testerConnexion(config) }
        assertTrue(ok)
        assertFalse(msg.contains("GeoNature v2"))
    }

    // ── Version minimale supportée ─────────────────────────────────────────────────

    @Test
    fun version_trop_ancienne_fait_echouer_le_test_et_invalide_la_config() {
        router(configServeur = """{"GEONATURE_VERSION":"2.10.5"}""")
        val (ok, msg) = runBlocking { GeoNatureAuth.testerConnexion(config) }
        assertFalse("une version sous le minimum doit faire échouer le test", ok)
        assertTrue("le message doit citer la version détectée et le minimum : $msg",
            msg.contains("2.10.5") && msg.contains(VERSION_GEONATURE_MINIMALE))
        assertFalse(config.serveurCompatible)
        // Invalidation effective : toutes les gardes de config tombent.
        assertFalse("connexionConfiguree doit être invalidée", config.connexionConfiguree)
        assertFalse("estConfiguree doit être invalidée", config.estConfiguree)
        // La version reste mémorisée pour l'affichage diagnostique dans Paramètres.
        assertEquals("2.10.5", config.versionGeoNatureServeur)
    }

    @Test
    fun nouveau_test_contre_un_serveur_a_jour_retablit_la_config() {
        // 1er test : serveur trop ancien → config invalidée.
        router(configServeur = """{"GEONATURE_VERSION":"2.10.5"}""")
        runBlocking { GeoNatureAuth.testerConnexion(config) }
        assertFalse(config.connexionConfiguree)
        // Serveur mis à jour → un nouveau test réussi rétablit tout.
        router(configServeur = """{"GEONATURE_VERSION":"${VERSION_GEONATURE_MINIMALE}.1"}""")
        val (ok, _) = runBlocking { GeoNatureAuth.testerConnexion(config) }
        assertTrue(ok)
        assertTrue(config.serveurCompatible)
        assertTrue(config.connexionConfiguree)
    }

    @Test
    fun comparaison_de_versions_numerique_pas_lexicographique() {
        assertTrue("2.9 < 2.15 (numérique, pas alphabétique)", comparerVersions("2.9", "2.15") < 0)
        assertTrue(comparerVersions("2.15", "2.15.0") == 0)
        assertTrue(comparerVersions("2.15.1", "2.15") > 0)
        assertTrue(comparerVersions("3.0", "2.99") > 0)
        assertTrue("suffixe non numérique ignoré", comparerVersions("2.15.0-rc1", "2.15") == 0)
        assertTrue(versionGeoNatureSupportee("2.15"))
        assertTrue(versionGeoNatureSupportee("2.16.4"))
        assertFalse(versionGeoNatureSupportee("2.14.2"))
    }

    // ── Types de nomenclatures obligatoires ────────────────────────────────────────

    @Test
    fun sync_complete_des_types_sans_avertissement() {
        router(configServeur = null, taxonomy = payloadTaxonomy(typesObligatoires))
        val (total, msg) = runBlocking { GeoNatureSync.synchroniserNomenclatures(config) }
        assertEquals(typesObligatoires.size, total)
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