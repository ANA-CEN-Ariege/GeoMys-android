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
import fr.ariegenature.geomys.network.GeoNatureSync
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.TaxRefCache
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synchro TaxRef paginée ([GeoNatureSync.synchroniserTaxRef]) face aux interruptions :
 *  une liste dont la pagination est coupée à mi-parcours (réseau, 500) ne doit JAMAIS être
 *  marquée synchronisée — sinon le cache passe pour exhaustif et l'autocomplétion hors-ligne
 *  perd silencieusement des taxons. La page size de l'implémentation est 1000 : les pages
 *  pleines font donc 1000 items générés. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxRefSyncPaginationTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
        TaxRefCache.vider()
        config = GeoNatureConfig(ApplicationProvider.getApplicationContext()).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"; idDataset = "12"
            // Biblistes lues depuis ce cache (peuplé par SyncRunner en conditions réelles) —
            // pas d'appel /biblistes. Datasets vides → pas de liste « privée » supplémentaire.
            listesCacheJson = """[{"id":7,"nom":"Liste test"}]"""
            datasetsCacheJson = """[]"""
        }
    }

    @After fun tearDown() { server.shutdown() }

    /** Page JSON de [nb] taxons, cd_nom consécutifs à partir de [depuis]. */
    private fun page(depuis: Int, nb: Int): String {
        val items = (depuis until depuis + nb).joinToString(",") {
            """{"cd_nom":$it,"lb_nom":"Taxon $it","nom_vern":"Vern $it","group2_inpn":"Oiseaux","regne":"Animalia"}"""
        }
        return """{"items":[$items]}"""
    }

    /** Route /taxref par numéro de page : reponsesParPage[n] sert la page n (1-indexée). */
    private fun router(reponsesParPage: Map<Int, MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/api/taxhub/api/taxref/version") ->
                        MockResponse().setResponseCode(404)
                    path.contains("/api/taxhub/api/taxref") -> {
                        val numPage = Regex("page=(\\d+)").find(path)?.groupValues?.get(1)?.toInt() ?: 1
                        reponsesParPage[numPage] ?: MockResponse().setResponseCode(404)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setResponseCode(200).setBody(body)

    private fun sync(): Pair<Int, String> =
        runBlocking { GeoNatureSync.synchroniserTaxRef(config) { _, _, _ -> } }

    @Test
    fun pagination_complete_marque_la_liste_synchronisee() {
        // Page 1 pleine (1000) + page 2 partielle (5) = fin normale de pagination.
        router(mapOf(1 to json(page(1, 1000)), 2 to json(page(1001, 5))))
        val (nb, msg) = sync()
        assertEquals("1005 taxons × 2 clés (nom scientifique + vernaculaire)", 2010, nb)
        // Le message ne contient plus le détail « X/Y listes » (déplacé dans l'écran Paramètres) ;
        // la liste synchronisée se vérifie directement, et aucun avertissement n'est attendu.
        assertFalse("aucun avertissement attendu : $msg", msg.contains("⚠"))
        assertEquals(listOf(7), TaxRefCache.listesSynchronisees)
    }

    @Test
    fun coupure_reseau_en_page_2_ne_marque_pas_la_liste_synchronisee() {
        router(mapOf(
            1 to json(page(1, 1000)),
            2 to MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        ))
        val (nb, msg) = sync()
        // Les 1000 taxons de la page 1 restent utiles (autocomplétion) et sont conservés…
        assertEquals(2000, nb)
        // …mais la liste TRONQUÉE ne doit pas passer pour exhaustive : avertissement + pas
        // d'inscription dans listesSynchronisees (le prochain sync la retentera en entier).
        assertTrue("avertissement de liste partielle attendu : $msg", msg.contains("⚠"))
        assertTrue(msg.contains("partiellement"))
        assertTrue("liste 7 non marquée synchronisée", TaxRefCache.listesSynchronisees.isEmpty())
    }

    @Test
    fun erreur_500_en_page_2_ne_marque_pas_la_liste_synchronisee() {
        router(mapOf(1 to json(page(1, 1000)), 2 to MockResponse().setResponseCode(500)))
        val (_, msg) = sync()
        assertTrue("avertissement attendu : $msg", msg.contains("⚠"))
        assertTrue(TaxRefCache.listesSynchronisees.isEmpty())
    }

    @Test
    fun echec_total_premiere_page_ne_touche_pas_au_cache_existant() {
        // Cache pré-existant d'un sync précédent : un sync en zone blanche ne doit pas l'écraser.
        TaxRefCache.listesSynchronisees = listOf(7)
        router(mapOf(1 to MockResponse().setResponseCode(500)))
        val (nb, msg) = sync()
        assertEquals(0, nb)
        assertTrue("message d'échec attendu : $msg", msg.contains("Aucun taxon"))
        assertEquals("le marqueur du sync précédent doit survivre", listOf(7), TaxRefCache.listesSynchronisees)
    }
}