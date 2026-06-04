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
import fr.ariegenature.geomys.network.OutboxEnvoi
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OutboxMonitoring
import fr.ariegenature.geomys.store.SaisieEnAttente
import kotlinx.coroutines.async
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Orchestration de l'envoi monitoring ([OutboxEnvoi]) contre un vrai serveur HTTP mock :
 *  idempotence (envois concurrents, saisie SENDING orpheline d'un crash), dépendances
 *  parent → enfant (résolution d'id serveur, blocage sur parent en échec) et retry ciblé.
 *  Complète [OutboxMonitoringTest] (état pur) en couvrant la couche réseau réelle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxEnvoiTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig

    /** Corps des POST de saisie reçus par le serveur, dans l'ordre (hors login). */
    private val postsVisite = Collections.synchronizedList(mutableListOf<String>())
    private val prochainIdServeur = AtomicInteger(41)

    /** Codes HTTP à servir aux POST de saisie successifs (vide = toujours 200). */
    private val codesVisite = Collections.synchronizedList(mutableListOf<Int>())

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        postsVisite.clear()
        codesVisite.clear()
        prochainIdServeur.set(41)
        OutboxMonitoring.init(ApplicationProvider.getApplicationContext())
        OutboxMonitoring.vider()
        config = GeoNatureConfig(ApplicationProvider.getApplicationContext()).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"; idDataset = "12"
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    request.method == "POST" && path.startsWith("/api/monitorings/object/") -> {
                        postsVisite.add(request.body.readUtf8())
                        val code = synchronized(codesVisite) {
                            if (codesVisite.isEmpty()) 200 else codesVisite.removeAt(0)
                        }
                        if (code in 200..299) {
                            json.setResponseCode(code)
                                .setBody("""{"id":${prochainIdServeur.getAndIncrement()}}""")
                        } else {
                            json.setResponseCode(code).setBody("""{"description":"boom"}""")
                        }
                    }
                    // Résolution de dataset du module, etc. — sans objet pour ces tests.
                    else -> json.setResponseCode(200).setBody("""{"data":[]}""")
                }
            }
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun saisie(
        uuid: String,
        parentUuidLocal: String? = null,
        etat: SaisieEnAttente.Etat = SaisieEnAttente.Etat.PENDING,
    ) = SaisieEnAttente(
        uuid = uuid, moduleCode = "STOC", objectType = "visite",
        parentUuidLocal = parentUuidLocal, parentIdField = "id_base_site",
        valeursJson = """{"comments":"test $uuid"}""", etat = etat,
    )

    private fun envoyerTout(): OutboxEnvoi.Resultat =
        runBlocking { OutboxEnvoi.envoyerTout(config) { _, _, _ -> } }

    // ── Idempotence ────────────────────────────────────────────────────────────────

    @Test
    fun deux_envois_concurrents_ne_postent_qu_une_fois() {
        OutboxMonitoring.ajouter(saisie("u1"))
        runBlocking {
            val a = async { OutboxEnvoi.envoyerTout(config) { _, _, _ -> } }
            val b = async { OutboxEnvoi.envoyerTout(config) { _, _, _ -> } }
            a.await(); b.await()
        }
        assertEquals("la saisie ne doit partir qu'une seule fois", 1, postsVisite.size)
        assertTrue("file vide après envoi (SENT purgé)", OutboxMonitoring.tout().isEmpty())
    }

    @Test
    fun saisie_sending_orpheline_d_un_crash_est_requalifiee_en_erreur_sans_reenvoi() {
        // Simule un crash en plein envoi : l'entrée est restée SENDING sur le disque.
        OutboxMonitoring.ajouter(saisie("u1", etat = SaisieEnAttente.Etat.SENDING))
        envoyerTout()
        val s = OutboxMonitoring.tout().single { it.uuid == "u1" }
        // Pas de ré-envoi automatique (le POST interrompu a pu aboutir côté serveur → doublon) :
        // la saisie doit redevenir visible en ERROR pour un « Réessayer » explicite.
        assertEquals(SaisieEnAttente.Etat.ERROR, s.etat)
        assertTrue("le message doit expliquer l'interruption : ${s.messageErreur}",
            s.messageErreur!!.contains("interrompu"))
        assertEquals("aucun POST ne doit partir automatiquement", 0, postsVisite.size)
        // Après « Réessayer » (PENDING), la saisie repart normalement.
        OutboxMonitoring.mettreAJour("u1") { it.copy(etat = SaisieEnAttente.Etat.PENDING, messageErreur = null) }
        val res = envoyerTout()
        assertEquals(1, res.succes)
        assertEquals(1, postsVisite.size)
    }

    @Test
    fun saisie_sending_orpheline_ne_bloque_plus_ses_enfants_indefiniment() {
        // Crash pendant l'envoi du parent : parent SENDING, enfant PENDING. Avant le correctif,
        // l'enfant restait PENDING à vie (parent jamais SENT, jamais ERROR → jamais débloqué).
        OutboxMonitoring.ajouter(saisie("parent", etat = SaisieEnAttente.Etat.SENDING))
        OutboxMonitoring.ajouter(saisie("enfant", parentUuidLocal = "parent"))
        envoyerTout()
        val enfant = OutboxMonitoring.tout().single { it.uuid == "enfant" }
        assertEquals("l'enfant doit sortir de PENDING (parent en échec)",
            SaisieEnAttente.Etat.ERROR, enfant.etat)
    }

    // ── Dépendances parent → enfant ────────────────────────────────────────────────

    @Test
    fun enfant_envoye_apres_son_parent_avec_id_serveur_resolu() {
        OutboxMonitoring.ajouter(saisie("parent"))
        OutboxMonitoring.ajouter(saisie("enfant", parentUuidLocal = "parent"))
        val res = envoyerTout()
        assertEquals(2, res.succes)
        assertEquals(2, postsVisite.size)
        // Le parent reçoit l'id serveur 41 ; le POST de l'enfant doit le porter en FK.
        assertTrue("le POST enfant doit référencer l'id serveur du parent : ${postsVisite[1]}",
            postsVisite[1].contains("\"id_base_site\":41"))
    }

    @Test
    fun parent_en_echec_bascule_l_enfant_en_erreur_sans_le_poster() {
        codesVisite.add(500)  // le POST du parent échoue
        OutboxMonitoring.ajouter(saisie("parent"))
        OutboxMonitoring.ajouter(saisie("enfant", parentUuidLocal = "parent"))
        val res = envoyerTout()
        assertEquals(0, res.succes)
        assertEquals(2, res.echecs)
        assertEquals("seul le parent a été posté", 1, postsVisite.size)
        val enfant = OutboxMonitoring.tout().single { it.uuid == "enfant" }
        assertEquals(SaisieEnAttente.Etat.ERROR, enfant.etat)
        assertTrue(enfant.messageErreur!!.contains("parent en échec"))
    }

    @Test
    fun retry_apres_echec_de_l_enfant_ne_reposte_que_l_enfant() {
        // 1er run : parent OK (id 41), enfant 500 → ERROR avec parentIdServeur résolu.
        codesVisite.add(200); codesVisite.add(500)
        OutboxMonitoring.ajouter(saisie("parent"))
        OutboxMonitoring.ajouter(saisie("enfant", parentUuidLocal = "parent"))
        envoyerTout()
        assertEquals(2, postsVisite.size)
        val enfant = OutboxMonitoring.tout().single { it.uuid == "enfant" }
        assertEquals(SaisieEnAttente.Etat.ERROR, enfant.etat)
        assertEquals("l'id serveur du parent doit être conservé pour le retry",
            41, enfant.parentIdServeur)
        // « Réessayer » : seul l'enfant repart — le parent (purgé après SENT) n'est pas re-posté.
        OutboxMonitoring.mettreAJour("enfant") { it.copy(etat = SaisieEnAttente.Etat.PENDING, messageErreur = null) }
        val res = envoyerTout()
        assertEquals(1, res.succes)
        assertEquals("un seul POST de plus (l'enfant)", 3, postsVisite.size)
        assertTrue("le re-POST de l'enfant garde la FK parent : ${postsVisite[2]}",
            postsVisite[2].contains("\"id_base_site\":41"))
        assertTrue("file vide après succès", OutboxMonitoring.tout().isEmpty())
    }
}