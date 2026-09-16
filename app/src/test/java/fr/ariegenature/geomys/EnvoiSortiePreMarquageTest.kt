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
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PRÉ-MARQUAGE AVANT LE POST D'UNE OCCURRENCE OCCTAX (audit 2026-09-14, R1-C1).
 *
 * Jusqu'ici, le seul marquage persistant d'une occurrence en vol était dans le `catch (IOException)`
 * et le succès n'était écrit qu'APRÈS la réponse. Si Android tuait le processus entre l'émission et
 * la réponse — ce qui arrive précisément quand l'envoi est long, donc sur un réseau de terrain —,
 * l'occurrence existait côté serveur et l'appareil l'ignorait. Au ré-envoi elle repartait sans la
 * moindre vérification, le filtre anti-doublon étant justement indexé sur `idReleveIncertain`, qui
 * n'avait jamais été posé. Doublon dans Occtax, donc dans la synthèse nationale, sans trace locale
 * permettant seulement de le soupçonner. Le `NonCancellable` de la v1.3.18 protège de l'annulation
 * de coroutine, pas de la mort du processus.
 *
 * On ne peut pas tuer le processus dans un test JVM — mais on peut observer ce que le DISQUE
 * contient à l'instant précis où le serveur reçoit le POST : c'est exactement l'état que
 * retrouverait l'application au redémarrage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvoiSortiePreMarquageTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig
    private lateinit var store: SortieStore

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
        SortieStore.reinitialiserCacheMemoire()
        EnvoisNonPersistes.vider()
        store = SortieStore(ctx)
        store.charger().forEach { store.supprimer(it.id) }
        config = GeoNatureConfig(ctx).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"; idDataset = "12"
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun obs(id: String) = Observation(
        id = id, espece = "Merle", cdNom = 4001, latitude = 42.9, longitude = 1.4,
        nombre = 1, releveId = "grp1", date = 1_700_000_000_000L,
    )

    /** Relit la sortie DEPUIS LE DISQUE, en court-circuitant le cache mémoire — c'est ce que
     *  ferait l'application au redémarrage après avoir été tuée. */
    private fun depuisLeDisque(): Sortie {
        SortieStore.reinitialiserCacheMemoire()
        return SortieStore(ApplicationProvider.getApplicationContext()).charger().first { it.id == "s1" }
    }

    @Test
    fun l_incertitude_est_sur_le_DISQUE_avant_que_le_serveur_ne_reponde() {
        val sortie = Sortie(id = "s1", observations = listOf(obs("o1"), obs("o2")))
        store.ajouter(sortie)

        // Observé DANS le dispatcher : au moment où le serveur reçoit le POST de la 1ʳᵉ occurrence,
        // que contient le disque de l'appareil ? C'est l'état exact qu'on retrouverait si le
        // processus mourait à cette seconde.
        var incertainsAuMomentDuPost: List<Int?>? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/only/releve") -> json.setResponseCode(200).setBody("""{"id":100}""")
                    path.contains("/occurrence") -> {
                        if (incertainsAuMomentDuPost == null) {
                            incertainsAuMomentDuPost =
                                depuisLeDisque().observations.map { it.idReleveIncertain }
                        }
                        json.setResponseCode(200).setBody("{}")
                    }
                    else -> json.setResponseCode(404)
                }
            }
        }

        runBlocking { envoyerSortieVersGeoNature(store.charger().first(), store, config) }

        assertNotNull("le serveur n'a jamais reçu de POST d'occurrence", incertainsAuMomentDuPost)
        assertEquals(
            "les deux occurrences du groupe doivent être « peut-être créées » SUR LE DISQUE " +
                "avant que le serveur ne réponde — sinon la mort du processus ici produit un doublon",
            listOf(100, 100), incertainsAuMomentDuPost,
        )

        // Une fois le lot passé, plus aucune incertitude ne subsiste : chaque succès la lève.
        val finale = depuisLeDisque()
        assertEquals(
            "l'incertitude doit être levée par les succès",
            listOf(null, null), finale.observations.map { it.idReleveIncertain },
        )
        assertEquals(listOf(true, true), finale.observations.map { it.envoyeeServeur })
    }

    @Test
    fun un_rejet_franc_du_serveur_leve_l_incertitude() {
        // 422 = le serveur a REFUSÉ l'occurrence : rien n'a été créé. Laisser l'incertitude
        // coûterait une vérification par uuid à chaque envoi ultérieur, pour rien.
        val sortie = Sortie(id = "s1", observations = listOf(obs("o1")))
        store.ajouter(sortie)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.endsWith("/only/releve") -> json.setResponseCode(200).setBody("""{"id":100}""")
                    path.contains("/occurrence") -> json.setResponseCode(422).setBody("""{"msg":"refus"}""")
                    else -> json.setResponseCode(200).setBody("{}")
                }
            }
        }

        runBlocking { envoyerSortieVersGeoNature(store.charger().first(), store, config) }

        val finale = depuisLeDisque()
        assertNull(
            "un rejet 4xx ne crée rien : l'incertitude du pré-marquage doit être levée",
            finale.observations.first().idReleveIncertain,
        )
        assertEquals(false, finale.observations.first().envoyeeServeur)
    }
}
