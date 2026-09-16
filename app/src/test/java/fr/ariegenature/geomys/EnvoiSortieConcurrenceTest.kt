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
import fr.ariegenature.geomys.network.EnvoisNonPersistes
import fr.ariegenature.geomys.network.envoyerSortieVersGeoNature
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NomValeur
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.SortieStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * ENVOI CONCURRENT D'UNE MÊME SAISIE OCCTAX — anti-doublon serveur.
 *
 * RÉGRESSION COUVERTE (audit 2026-09-14, instruction du 2026-09-16) : les cinq points d'appel d'un
 * envoi Occtax n'avaient pour garde que des drapeaux `envoiEnCours` de FRAGMENT, aveugles d'un écran
 * à l'autre. Cas réel : l'envoi est lancé depuis l'écran de détail, l'utilisateur revient à la liste
 * — dont l'instance n'a jamais posé son drapeau —, la saisie y est toujours « À envoyer » avec sa
 * flèche, et un second envoi part sans même un toast. Chaque envoi crée son PROPRE relevé, et rien
 * ne les arrête côté serveur : `unique_id_occurence_occtax` n'a aucune contrainte d'unicité dans
 * GeoNature, et `unique_id_sinp_occtax`, qui l'a, n'est jamais envoyé par l'application.
 *
 * Deux moitiés indissociables sont testées ici. Le VERROU process-wide, qui empêche les deux envois
 * de se chevaucher — et la RELECTURE SOUS VERROU, sans laquelle le second envoi, une fois sérialisé,
 * repartirait de l'instantané chargé par son écran et reposterait tout ce que le premier vient de
 * créer : le doublon serait seulement décalé.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvoiSortieConcurrenceTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig
    private lateinit var store: SortieStore
    private val relevesPostes = AtomicInteger(0)
    private val occurrencesPostees = AtomicInteger(0)

    private val typesNomenclature = listOf(
        "METH_OBS", "STATUT_OBS", "SEXE", "STADE_VIE", "STATUT_BIO", "ETA_BIO",
        "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN", "TYPE_MEDIA",
    )

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        server = MockWebServer().apply { start() }
        NomenclatureCache.init(ctx)
        NomenclatureCache.setAll(typesNomenclature.associateWith { listOf(NomValeur(1, "x")) })
        // Caches PROCESS-WIDE : sans ces remises à zéro, un test hérite de l'état du précédent.
        SortieStore.reinitialiserCacheMemoire()
        EnvoisNonPersistes.vider()
        store = SortieStore(ctx)
        store.charger().forEach { store.supprimer(it.id) }
        config = GeoNatureConfig(ctx).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"; idDataset = "12"
        }
        relevesPostes.set(0)
        occurrencesPostees.set(0)
    }

    @After fun tearDown() { server.shutdown() }

    private fun obs(id: String) = Observation(
        id = id, espece = "Merle", cdNom = 4001, latitude = 42.9, longitude = 1.4,
        nombre = 1, releveId = "grp1", date = 1_700_000_000_000L,
    )

    /** Serveur nominal, mais LENT sur le POST de relevé : c'est ce délai qui ouvre la fenêtre
     *  pendant laquelle l'utilisateur revient en arrière et relance un envoi. */
    private fun routerLent(delaiMs: Long = 300) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.contains("only/releve") -> {
                        val n = relevesPostes.incrementAndGet()
                        json.setResponseCode(200)
                            .setBodyDelay(delaiMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .setBody("""{"id":$n,"properties":{}}""")
                    }
                    path.contains("/occurrence") -> {
                        occurrencesPostees.incrementAndGet()
                        json.setResponseCode(200).setBody("""{"id":900}""")
                    }
                    else -> json.setResponseCode(200).setBody("{}")
                }
            }
        }
    }

    @Test
    fun deux_envois_concurrents_de_la_meme_saisie_ne_creent_qu_un_seul_releve() {
        val sortie = Sortie(id = "s1", observations = listOf(obs("o1"), obs("o2")))
        store.ajouter(sortie)
        routerLent()

        val (r1, r2) = runBlocking {
            listOf(
                async { envoyerSortieVersGeoNature(sortie, store, config) },
                async { envoyerSortieVersGeoNature(sortie, store, config) },
            ).awaitAll()
        }

        assertEquals(
            "les deux ecrans ont poste leur propre releve : doublon en base regionale",
            1, relevesPostes.get(),
        )
        assertEquals(
            "chaque observation ne doit partir qu'une fois",
            2, occurrencesPostees.get(),
        )
        // L'un des deux passe, l'autre est refuse explicitement — jamais deux succes silencieux.
        val refuses = listOf(r1, r2).filter { !it.succes }
        assertEquals("exactement un envoi doit etre refuse", 1, refuses.size)
        assertTrue(
            "le refus doit DIRE pourquoi : message obtenu = ${refuses[0].message}",
            refuses[0].message.contains("déjà en cours"),
        )
    }

    @Test
    fun un_envoi_sequentiel_sur_un_instantane_perime_ne_reposte_rien() {
        // Le second ecran a charge sa copie AVANT le premier envoi : ses observations y sont encore
        // « a envoyer ». Sans relecture sous verrou, il les reposterait toutes.
        val sortie = Sortie(id = "s1", observations = listOf(obs("o1"), obs("o2")))
        store.ajouter(sortie)
        val instantanePerime = sortie.copy()
        routerLent(delaiMs = 0)

        runBlocking { envoyerSortieVersGeoNature(sortie, store, config) }
        assertEquals(1, relevesPostes.get())
        assertEquals(2, occurrencesPostees.get())

        val r = runBlocking { envoyerSortieVersGeoNature(instantanePerime, store, config) }

        assertEquals("aucun releve supplementaire ne doit partir", 1, relevesPostes.get())
        assertEquals("aucune occurrence ne doit repartir", 2, occurrencesPostees.get())
        assertTrue("l'envoi doit se clore proprement, pas echouer", r.succes)
    }

    @Test
    fun les_marquages_du_disque_survivent_a_une_reecriture_par_un_ecran_d_edition() {
        // « Continuer la saisie » reecrit la sortie avec la liste d'observations de son ViewModel,
        // instantanee a la reprise. Sans fusion preservante, les observations acquittees entre-temps
        // par un envoi en cours repassaient a « a envoyer » SUR LE DISQUE : le doublon survivait au
        // redemarrage et l'anti-doublon restait desarme pour tous les envois suivants.
        val sortie = Sortie(id = "s1", observations = listOf(obs("o1"), obs("o2")))
        store.ajouter(sortie)
        assertTrue(store.marquerObservationsEnvoyees("s1", listOf("o1")))

        // L'ecran d'edition reecrit avec SON instantane, pris avant le marquage.
        store.remplacer("s1", sortie.copy(observations = listOf(obs("o1"), obs("o2"), obs("o3"))))

        val relue = store.charger().first { it.id == "s1" }
        assertTrue(
            "o1 avait ete transmise a GeoNature : son marquage ne doit pas etre efface",
            relue.observations.first { it.id == "o1" }.envoyeeServeur,
        )
        assertTrue(
            "les observations non transmises restent a envoyer",
            !relue.observations.first { it.id == "o2" }.envoyeeServeur,
        )
        assertEquals("l'edition doit bien avoir ajoute la nouvelle observation", 3, relue.observations.size)
    }
}
