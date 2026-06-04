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
import fr.ariegenature.geomys.store.RelevesOrphelins
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Flux d'envoi OccTax face à un réseau DÉGRADÉ : coupures socket en plein POST (mode avion,
 *  zone blanche), réponses serveur inattendues (HTML au lieu de JSON, id absent), token
 *  expiré. Vérifie qu'aucun scénario ne laisse un relevé vide non signalé côté GeoNature et
 *  que l'erreur remonte toujours en [GNErreur] humanisable — jamais en IOException brute. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoNatureUploadReseauDegradeTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig
    private val deletes = AtomicInteger(0)

    private val typesNomenclature = listOf(
        "METH_OBS", "STATUT_OBS", "SEXE", "STADE_VIE", "STATUT_BIO", "ETA_BIO",
        "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN", "TYPE_MEDIA",
    )

    /** Code sentinelle : coupe la socket sans répondre (IOException côté client). */
    private val PANNE = -1

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        deletes.set(0)
        RelevesOrphelins.init(ApplicationProvider.getApplicationContext())
        NomenclatureCache.init(ApplicationProvider.getApplicationContext())
        NomenclatureCache.setAll(typesNomenclature.associateWith { listOf(NomValeur(1, "x")) })
        config = GeoNatureConfig(ApplicationProvider.getApplicationContext()).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"; idDataset = "12"
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun obs(id: String, releveId: String? = null) = Observation(
        id = id, espece = "Merle", cdNom = 4001, latitude = 42.9, longitude = 1.4,
        nombre = 1, releveId = releveId, date = 1_700_000_000_000L,
    )

    private fun reponse(code: Int, body: String): MockResponse =
        // DISCONNECT_AFTER_REQUEST : la socket est coupée après lecture de la requête, avant
        // toute réponse → IOException côté client. (DISCONNECT_AT_START est inopérant avec un
        // Dispatcher custom : la politique est lue via peek() avant dispatch.)
        if (code == PANNE) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        else MockResponse().setHeader("Content-Type", "application/json")
            .setResponseCode(code).setBody(body)

    /** Route auth + relevé + occurrence + DELETE. [PANNE] sur un code = coupure réseau. */
    private fun router(
        codeReleve: Int = 200,
        bodyReleve: String = """{"id":100}""",
        codeOccurrence: Int = 200,
        codeDelete: Int = 200,
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/api/auth/login") ->
                        reponse(200, """{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/only/releve") -> reponse(codeReleve, bodyReleve)
                    path.contains("/occurrence") -> reponse(codeOccurrence, "{}")
                    request.method == "DELETE" -> {
                        deletes.incrementAndGet()
                        reponse(codeDelete, "{}")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun envoyerEtAttendreEchec(sortie: Sortie): GNErreur.EnvoiEchoue =
        try {
            runBlocking { GeoNatureUpload.envoyer(sortie, config) }
            throw AssertionError("une GNErreur.EnvoiEchoue était attendue")
        } catch (e: GNErreur.EnvoiEchoue) { e }

    // ── Coupures réseau en plein POST ──────────────────────────────────────────────

    @Test
    fun coupure_reseau_pendant_le_releve_remonte_une_GNErreur_pas_une_IOException() {
        router(codeReleve = PANNE)
        val e = envoyerEtAttendreEchec(Sortie(observations = listOf(obs("o1"))))
        assertTrue("le message doit expliquer la coupure : ${e.message}",
            e.message!!.contains("Réseau interrompu"))
        // Aucun relevé créé côté serveur → rien à rollback, pas d'orphelin.
        assertEquals(0, deletes.get())
    }

    @Test
    fun coupure_reseau_pendant_l_occurrence_rollback_le_releve() {
        router(codeOccurrence = PANNE, codeDelete = 200)
        val e = envoyerEtAttendreEchec(Sortie(observations = listOf(obs("o1"))))
        assertTrue(e.message!!.contains("Réseau interrompu"))
        // Le relevé 100 créé puis resté vide doit avoir été supprimé (DELETE reçu).
        assertEquals("le relevé vide doit être rollback", 1, deletes.get())
        // DELETE accepté → pas d'orphelin signalé.
        assertTrue("pas d'orphelin attendu : ${e.message}", !e.message!!.contains("orphelin")
            && !e.message!!.contains("à supprimer manuellement"))
    }

    @Test
    fun coupure_reseau_sur_occurrence_puis_rollback_signale_un_orphelin() {
        router(codeOccurrence = PANNE, codeDelete = PANNE)
        val e = envoyerEtAttendreEchec(Sortie(observations = listOf(obs("o1"))))
        // DELETE impossible (réseau toujours coupé) → le relevé 100 doit être signalé.
        assertTrue("doit signaler le relevé orphelin 100 : ${e.message}", e.message!!.contains("100"))
    }

    @Test
    fun orphelin_memorise_puis_supprime_automatiquement_a_l_envoi_suivant() {
        // Scénario terrain (mode avion en plein transfert) : relevé créé, occurrence ET
        // rollback en échec → « relevé sans taxon » resté sur le serveur.
        router(codeOccurrence = PANNE, codeDelete = PANNE)
        envoyerEtAttendreEchec(Sortie(observations = listOf(obs("o1"))))
        assertEquals("l'orphelin doit être mémorisé pour nettoyage différé",
            listOf(100), RelevesOrphelins.liste(config.urlServeur))
        val deletesApresPanne = deletes.get()

        // Réseau rétabli, l'utilisateur renvoie (nouvelle sortie ou la même) : l'envoi
        // commence par purger les orphelins mémorisés.
        router()  // tout passe désormais
        val res = runBlocking { GeoNatureUpload.envoyer(Sortie(observations = listOf(obs("o2"))), config) }
        assertEquals(1, res.nbCrees)
        assertEquals("le DELETE de l'orphelin doit être passé", 1, deletes.get() - deletesApresPanne)
        assertTrue("plus d'orphelin mémorisé après nettoyage",
            RelevesOrphelins.liste(config.urlServeur).isEmpty())
    }

    // ── Réponses serveur dégradées ─────────────────────────────────────────────────

    @Test
    fun reponse_releve_200_sans_id_remonte_une_erreur_claire() {
        router(bodyReleve = """{"statut":"ok"}""")
        val e = envoyerEtAttendreEchec(Sortie(observations = listOf(obs("o1"))))
        assertTrue("doit mentionner l'id absent : ${e.message}", e.message!!.contains("id absent"))
    }

    @Test
    fun reponse_releve_200_html_remonte_une_erreur_claire() {
        // Cas réel : reverse-proxy/SPA qui renvoie une page HTML avec un code 200.
        router(bodyReleve = "<html><body>maintenance</body></html>")
        val e = envoyerEtAttendreEchec(Sortie(observations = listOf(obs("o1"))))
        assertTrue("doit mentionner la réponse non-JSON : ${e.message}",
            e.message!!.contains("non-JSON"))
    }

    @Test
    fun erreur_500_avec_corps_html_ne_crashe_pas_le_parse() {
        router(codeReleve = 500, bodyReleve = "<html>Internal Server Error</html>")
        val e = envoyerEtAttendreEchec(Sortie(observations = listOf(obs("o1"))))
        assertEquals(500, e.code)
        assertTrue(e.message!!.contains("500"))
    }

    @Test
    fun occurrence_401_token_expire_rollback_et_remonte_le_code() {
        router(codeOccurrence = 401)
        val e = envoyerEtAttendreEchec(Sortie(observations = listOf(obs("o1"))))
        assertEquals("le code 401 doit remonter pour le message « reconnecte-toi »", 401, e.code)
        assertEquals("le relevé vide doit être rollback", 1, deletes.get())
    }

    // ── Envoi partiel multi-relevés ────────────────────────────────────────────────

    @Test
    fun envoi_partiel_seul_le_groupe_en_echec_est_rollback() {
        // Deux relevés distincts (releveId R1/R2) : la 1re occurrence passe, la 2e échoue.
        val nbOccurrences = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/api/auth/login") ->
                        reponse(200, """{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/only/releve") -> reponse(200, """{"id":100}""")
                    path.contains("/occurrence") ->
                        if (nbOccurrences.incrementAndGet() == 1) reponse(200, "{}")
                        else reponse(500, """{"description":"boom"}""")
                    request.method == "DELETE" -> {
                        deletes.incrementAndGet()
                        reponse(200, "{}")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val sortie = Sortie(observations = listOf(obs("a", releveId = "R1"), obs("b", releveId = "R2")))
        val res = runBlocking { GeoNatureUpload.envoyer(sortie, config) }
        assertEquals("1 occurrence créée sur 2", 1, res.nbCrees)
        assertEquals(2, res.nbTotal)
        assertEquals("seul le relevé du groupe en échec est rollback", 1, deletes.get())
        assertTrue(res.relevesOrphelins.isEmpty())
    }
}