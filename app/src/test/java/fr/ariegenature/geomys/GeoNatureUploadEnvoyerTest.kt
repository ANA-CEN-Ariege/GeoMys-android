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
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.network.GNErreur
import fr.ariegenature.geomys.network.GeoNatureUpload
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NomValeur
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

/** Flux d'envoi OccTax de bout en bout (POST relevé → POST occurrence(s) → rollback) via
 *  MockWebServer. Valide l'orchestration multi-étapes et le contrat HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoNatureUploadEnvoyerTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig

    private val typesNomenclature = listOf(
        "METH_OBS", "STATUT_OBS", "SEXE", "STADE_VIE", "STATUT_BIO", "ETA_BIO",
        "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN", "TYPE_MEDIA",
    )

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        NomenclatureCache.init(ApplicationProvider.getApplicationContext())
        // Cache non vide pour TOUS les types → évite tout appel réseau de résolution de
        // nomenclatures pendant l'envoi (on se concentre sur le flux relevé/occurrence).
        NomenclatureCache.setAll(typesNomenclature.associateWith { listOf(NomValeur(1, "x")) })
        config = GeoNatureConfig(ApplicationProvider.getApplicationContext()).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"; idDataset = "12"
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun obs(id: String, releveId: String? = null, cdNom: Int? = 4001) = Observation(
        id = id, espece = "Merle", cdNom = cdNom, latitude = 42.9, longitude = 1.4,
        nombre = 1, releveId = releveId, date = 1_700_000_000_000L,
    )

    /** Route auth + relevé + occurrence + DELETE selon des codes paramétrables. */
    private fun router(
        codeReleve: Int = 200,
        codeOccurrence: Int = 200,
        codeDelete: Int = 200,
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/only/releve") ->
                        json.setResponseCode(codeReleve).setBody("""{"id":100}""")
                    path.contains("/occurrence") ->
                        json.setResponseCode(codeOccurrence).setBody("{}")
                    request.method == "DELETE" ->
                        json.setResponseCode(codeDelete).setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @Test
    fun envoi_mono_taxon_cree_un_releve_et_une_occurrence() {
        router()
        val res = runBlocking { GeoNatureUpload.envoyer(Sortie(observations = listOf(obs("o1"))), config) }
        assertEquals(1, res.nbCrees)
        assertEquals(1, res.nbTotal)
        assertEquals(100, res.premierIdReleve)
        assertTrue(res.relevesOrphelins.isEmpty())
    }

    @Test
    fun envoi_multi_taxons_un_releve_plusieurs_occurrences() {
        router()
        val obs = listOf(obs("a", releveId = "R1"), obs("b", releveId = "R1"))
        val res = runBlocking { GeoNatureUpload.envoyer(Sortie(observations = obs), config) }
        assertEquals("2 occurrences sur 1 relevé", 2, res.nbCrees)
        assertEquals(2, res.nbTotal)
    }

    @Test
    fun occurrence_en_echec_declenche_rollback_et_leve_une_erreur() {
        router(codeReleve = 200, codeOccurrence = 500, codeDelete = 200)
        try {
            runBlocking { GeoNatureUpload.envoyer(Sortie(observations = listOf(obs("o1"))), config) }
            throw AssertionError("une GNErreur.EnvoiEchoue était attendue")
        } catch (e: GNErreur.EnvoiEchoue) {
            // Relevé supprimé avec succès (DELETE 200) → pas d'orphelin signalé dans le message.
            assertTrue(true)
        }
    }

    @Test
    fun rollback_impossible_signale_un_releve_orphelin() {
        router(codeReleve = 200, codeOccurrence = 500, codeDelete = 500)
        try {
            runBlocking { GeoNatureUpload.envoyer(Sortie(observations = listOf(obs("o1"))), config) }
            throw AssertionError("attendu : GNErreur.EnvoiEchoue")
        } catch (e: GNErreur.EnvoiEchoue) {
            assertTrue("doit mentionner le relevé orphelin 100", e.message!!.contains("100"))
        }
    }

    @Test
    fun aucune_obs_avec_cd_nom_leve_une_erreur() {
        router()
        try {
            runBlocking { GeoNatureUpload.envoyer(Sortie(observations = listOf(obs("o1", cdNom = null))), config) }
            throw AssertionError("attendu : AucuneObservationCompatible")
        } catch (e: GNErreur.AucuneObservationCompatible) {
            assertTrue(true)
        }
    }

    @Test
    fun dataset_invalide_leve_une_erreur() {
        router()
        config.idDataset = "0"
        try {
            runBlocking { GeoNatureUpload.envoyer(Sortie(observations = listOf(obs("o1"))), config) }
            throw AssertionError("attendu : GNErreur.EnvoiEchoue (dataset)")
        } catch (e: GNErreur.EnvoiEchoue) {
            assertTrue(e.message!!.contains("id_dataset"))
        }
    }
}
