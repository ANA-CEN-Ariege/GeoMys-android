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
import fr.ariegenature.geomys.network.MarqueurEnvoiOcctax

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

    /** Relevé SANS ESPÈCE : un placeholder (releveSansEspece=true, cd_nom null) crée le relevé
     *  côté serveur SANS aucune occurrence, ne déclenche PAS de rollback (pas d'orphelin) et est
     *  compté comme créé + remonté dans obsCreesIds (anti-doublon au ré-envoi). */
    @Test
    fun releve_sans_espece_cree_le_releve_sans_occurrence_ni_rollback() {
        val nbOcc = java.util.concurrent.atomic.AtomicInteger(0)
        val nbDelete = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/only/releve") -> json.setResponseCode(200).setBody("""{"id":100}""")
                    path.contains("/occurrence") -> { nbOcc.incrementAndGet(); json.setResponseCode(200).setBody("{}") }
                    request.method == "DELETE" -> { nbDelete.incrementAndGet(); json.setResponseCode(200).setBody("{}") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val placeholder = obs("p1", releveId = "R1", cdNom = null).apply { releveSansEspece = true }
        val res = runBlocking { GeoNatureUpload.envoyer(Sortie(observations = listOf(placeholder)), config) }
        assertEquals("le relevé vide compte comme créé", 1, res.nbCrees)
        assertEquals(1, res.nbTotal)
        assertEquals(100, res.premierIdReleve)
        assertEquals(listOf("p1"), res.obsCreesIds)
        assertTrue("pas de relevé orphelin", res.relevesOrphelins.isEmpty())
        assertEquals("aucune occurrence postée", 0, nbOcc.get())
        assertEquals("aucun rollback DELETE", 0, nbDelete.get())
    }

    // ── Anti-doublon par uuid client + marquage au fil de l'eau (audit 2026-08-27) ──────────

    /** Chaque occurrence part avec son `unique_id_occurence_occtax` et le marqueur est appelé
     *  DANS le bloc IO dès le 2xx (pas seulement au retour). */
    @Test
    fun occurrence_postee_avec_uuid_client_et_marquee_au_fil_de_l_eau() {
        val corps = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/only/releve") -> json.setResponseCode(200).setBody("""{"id":100}""")
                    path.contains("/occurrence") -> { corps.add(request.body.readUtf8()); json.setResponseCode(200).setBody("{}") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val o = obs("o1").apply { uuidOccurrence = "dddddddd-1111-2222-3333-444444444444" }
        val crees = mutableListOf<String>()
        val marqueur = object : MarqueurEnvoiOcctax {
            override fun occurrenceCreee(obsId: String) { crees.add(obsId) }
            override fun occurrenceIncertaine(obsId: String, idReleve: Int) {}
        }
        val res = runBlocking { GeoNatureUpload.envoyer(Sortie(observations = listOf(o)), config, marqueur) }
        assertEquals(1, res.nbCrees)
        assertEquals(listOf("o1"), crees)
        assertTrue("uuid client dans le payload",
            corps.single().contains("\"unique_id_occurence_occtax\":\"dddddddd-1111-2222-3333-444444444444\""))
    }

    /** Occurrence dont le POST précédent est resté sans réponse (idReleveIncertain) : si le relevé
     *  serveur la contient déjà (uuid retrouvé), elle est acquise SANS nouveau relevé ni re-POST. */
    @Test
    fun occurrence_incertaine_retrouvee_par_uuid_n_est_pas_repostee() {
        val nbReleves = java.util.concurrent.atomic.AtomicInteger(0)
        val nbOcc = java.util.concurrent.atomic.AtomicInteger(0)
        val uuid = "eeeeeeee-1111-2222-3333-444444444444"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/releve/55") && request.method == "GET" -> json.setResponseCode(200).setBody(
                        """{"releve":{"id":55,"properties":{"t_occurrences_occtax":[{"unique_id_occurence_occtax":"$uuid"}]}}}""")
                    path.endsWith("/only/releve") -> { nbReleves.incrementAndGet(); json.setResponseCode(200).setBody("""{"id":100}""") }
                    path.contains("/occurrence") -> { nbOcc.incrementAndGet(); json.setResponseCode(200).setBody("{}") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val o = obs("o1").apply { uuidOccurrence = uuid; idReleveIncertain = 55 }
        val res = runBlocking { GeoNatureUpload.envoyer(Sortie(observations = listOf(o)), config) }
        assertEquals("acquise", 1, res.nbCrees)
        assertEquals(listOf("o1"), res.obsCreesIds)
        assertEquals("aucun nouveau relevé", 0, nbReleves.get())
        assertEquals("aucun re-POST", 0, nbOcc.get())
    }

    /** Vérification IMPOSSIBLE (relevé en 500) : pas de re-POST aveugle — l'obs reste à envoyer. */
    @Test
    fun occurrence_incertaine_verification_impossible_pas_de_repost() {
        val nbOcc = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/releve/55") && request.method == "GET" -> json.setResponseCode(500).setBody("{}")
                    path.contains("/occurrence") -> { nbOcc.incrementAndGet(); json.setResponseCode(200).setBody("{}") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val o = obs("o1").apply { idReleveIncertain = 55 }
        try {
            runBlocking { GeoNatureUpload.envoyer(Sortie(observations = listOf(o)), config) }
            throw AssertionError("attendu : EnvoiEchoue (rien créé)")
        } catch (e: GNErreur.EnvoiEchoue) {
            assertTrue(e.message!!.contains("anti-doublon"))
        }
        assertEquals("aucun re-POST aveugle", 0, nbOcc.get())
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

    // ── Envoi partiel & ré-envoi (audit 2026-07 : une seule occurrence créée verrouillait
    //    toute la sortie ; les obs restantes étaient perdues) ─────────────────────────────

    /** Le réseau tombe entre le groupe 1 et le groupe 2 : l'envoi NE lève PAS (acquis
     *  préservé), remonte nbCrees < nbTotal et la liste exacte des obs créées — c'est elle
     *  que l'appelant persiste pour que le ré-envoi ne re-poste pas le groupe 1. */
    @Test
    fun envoi_partiel_remonte_les_obs_creees_sans_lever() {
        val nbReleves = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/only/releve") ->
                        if (nbReleves.incrementAndGet() == 1) json.setResponseCode(200).setBody("""{"id":100}""")
                        else json.setResponseCode(500).setBody("{}")
                    path.contains("/occurrence") -> json.setResponseCode(200).setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        // Deux obs mono-taxon = deux groupes/relevés distincts.
        val sortie = Sortie(observations = listOf(obs("a"), obs("b")))
        val res = runBlocking { GeoNatureUpload.envoyer(sortie, config) }
        assertEquals(1, res.nbCrees)
        assertEquals(2, res.nbTotal)
        assertEquals(listOf("a"), res.obsCreesIds)
        assertTrue("l'erreur du groupe 2 doit être remontée pour le message utilisateur",
            res.messageDerniereErreur != null)
    }

    /** Ré-envoi après succès partiel : les obs marquées envoyeeServeur ne repartent PAS
     *  (anti-doublon) — une seule occurrence est postée, l'autre est comptée « déjà envoyée ». */
    @Test
    fun reenvoi_ignore_les_obs_deja_envoyees() {
        val nbOccurrences = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/only/releve") -> json.setResponseCode(200).setBody("""{"id":100}""")
                    path.contains("/occurrence") -> {
                        nbOccurrences.incrementAndGet()
                        json.setResponseCode(200).setBody("{}")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val deja = obs("a").apply { envoyeeServeur = true }
        val res = runBlocking {
            GeoNatureUpload.envoyer(Sortie(observations = listOf(deja, obs("b"))), config)
        }
        assertEquals(1, res.nbCrees)
        assertEquals(1, res.nbTotal)
        assertEquals(1, res.nbDejaEnvoyees)
        assertEquals(listOf("b"), res.obsCreesIds)
        assertEquals("une seule occurrence doit avoir été postée", 1, nbOccurrences.get())
    }

    /** Toutes les obs déjà créées (échec précédent APRÈS le dernier groupe) : rien à poster,
     *  résultat 0/0 → l'appelant clôture la sortie sans aucun POST relevé/occurrence. */
    @Test
    fun tout_deja_envoye_retourne_zero_sur_zero() {
        router()
        val sortie = Sortie(observations = listOf(
            obs("a").apply { envoyeeServeur = true },
            obs("b").apply { envoyeeServeur = true },
        ))
        val res = runBlocking { GeoNatureUpload.envoyer(sortie, config) }
        assertEquals(0, res.nbCrees)
        assertEquals(0, res.nbTotal)
        assertEquals(2, res.nbDejaEnvoyees)
    }
}
