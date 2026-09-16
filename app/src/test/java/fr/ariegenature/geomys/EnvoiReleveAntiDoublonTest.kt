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
import fr.ariegenature.geomys.network.GeoNatureUpload
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * ANTI-DOUBLON DU RELEVÉ OCCTAX par uuid client (audit 2026-09-14, R1-M1).
 *
 * Le relevé ne portait aucune identité client, et trois chemins laissaient un relevé
 * potentiellement créé sans la moindre trace locale (réseau coupé, réponse non-JSON, id absent de
 * la réponse). Au ré-envoi, l'application en créait un second.
 *
 * Pour un relevé ordinaire, le surcoût restait un relevé VIDE — que l'application sait déjà
 * détecter et supprimer. Mais pour un RELEVÉ SANS ESPÈCE — un passage sans observation, donnée
 * d'absence parfaitement légitime que la saisie multi-taxons produit dès qu'on enregistre sans
 * espèce — le relevé EST la donnée : le re-créer produit un doublon franc d'absence en base
 * régionale, qu'aucune contrainte serveur n'arrête.
 *
 * L'identité est `unique_id_sinp_grp`, colonne de `TRelevesOccurrence` que le filtre générique du
 * backend sait exploiter, et elle est DÉTERMINISTE (dérivée de la clé de regroupement locale) :
 * l'application peut interroger le serveur à son sujet même après un redémarrage, sans avoir eu à
 * persister l'uuid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvoiReleveAntiDoublonTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig
    private lateinit var store: SortieStore
    private val relevesCrees = AtomicInteger(0)
    private val occurrencesPostees = AtomicInteger(0)
    private val relevesCibles = mutableListOf<String>()
    private var corpsReleve: String? = null

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
        relevesCrees.set(0); occurrencesPostees.set(0)
        relevesCibles.clear(); corpsReleve = null
    }

    @After fun tearDown() { server.shutdown() }

    private fun obs(id: String, cdNom: Int? = 4001, tente: Boolean = false) = Observation(
        id = id, espece = "Merle", cdNom = cdNom, latitude = 42.9, longitude = 1.4,
        nombre = 1, releveId = "grp1", date = 1_700_000_000_000L,
        releveSansEspece = cdNom == null, releveTente = tente,
    )

    /** L'uuid que l'application va dériver pour le groupe « grp1 ». */
    private fun uuidDuGroupe() = GeoNatureUpload.uuidReleveGroupe("grp1")

    /** [reponseRecherche] = corps renvoyé par GET /releves (la recherche par uuid). */
    private fun router(reponseRecherche: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.contains("/releves?") -> json.setResponseCode(200).setBody(reponseRecherche)
                    path.endsWith("/only/releve") -> {
                        corpsReleve = request.body.readUtf8()
                        val n = 700 + relevesCrees.incrementAndGet()
                        json.setResponseCode(200).setBody("""{"id":$n}""")
                    }
                    path.contains("/occurrence") -> {
                        occurrencesPostees.incrementAndGet()
                        relevesCibles.add(path.substringAfter("/releve/").substringBefore("/"))
                        json.setResponseCode(200).setBody("{}")
                    }
                    else -> json.setResponseCode(200).setBody("{}")
                }
            }
        }
    }

    private fun reponseTrouve(uuid: String, idReleve: Int = 555) =
        """{"total":1,"items":{"type":"FeatureCollection","features":[""" +
            """{"type":"Feature","properties":{"id_releve_occtax":$idReleve,"unique_id_sinp_grp":"$uuid"}}]}}"""

    private val reponseVide = """{"total":0,"items":{"type":"FeatureCollection","features":[]}}"""

    @Test
    fun le_releve_part_avec_son_uuid_client() {
        store.ajouter(Sortie(id = "s1", observations = listOf(obs("o1"))))
        router(reponseVide)

        runBlocking { envoyerSortieVersGeoNature(store.charger().first(), store, config) }

        assertTrue(
            "le POST du relevé doit porter unique_id_sinp_grp — sans identité client, un relevé " +
                "dont la réponse se perd est irrattrapable. Corps : $corpsReleve",
            corpsReleve!!.contains("\"unique_id_sinp_grp\":\"${uuidDuGroupe()}\""),
        )
    }

    @Test
    fun un_releve_deja_cree_est_REUTILISE_au_lieu_d_etre_recree() {
        // Envoi précédent interrompu : le relevé a été tenté (et créé côté serveur, id 555), mais
        // l'appareil n'a jamais vu la réponse.
        store.ajouter(Sortie(id = "s1", observations = listOf(obs("o1", tente = true))))
        router(reponseTrouve(uuidDuGroupe()))

        runBlocking { envoyerSortieVersGeoNature(store.charger().first(), store, config) }

        assertEquals("aucun nouveau relevé ne doit être créé", 0, relevesCrees.get())
        assertEquals("l'occurrence doit être postée dans le relevé RETROUVÉ", listOf("555"), relevesCibles)
    }

    @Test
    fun releve_sans_espece_deja_cree_n_est_pas_duplique() {
        // LE cas qui compte : le relevé d'absence EST la donnée, il n'a aucune occurrence pour le
        // rattraper. Le re-créer produit un doublon franc dans la base régionale.
        store.ajouter(Sortie(id = "s1", observations = listOf(obs("o1", cdNom = null, tente = true))))
        router(reponseTrouve(uuidDuGroupe()))

        val res = runBlocking { envoyerSortieVersGeoNature(store.charger().first(), store, config) }

        assertEquals("le relevé d'absence ne doit pas être re-créé", 0, relevesCrees.get())
        assertTrue("l'envoi doit se clore normalement", res.succes)
    }

    @Test
    fun verification_impossible_ne_cree_RIEN() {
        // Le serveur IGNORE le filtre (version ancienne) : il renvoie des relevés quelconques.
        // Conclure « absent » ferait créer un doublon — l'invariant du projet impose de ne rien
        // poster quand la vérification anti-doublon échoue.
        store.ajouter(Sortie(id = "s1", observations = listOf(obs("o1", tente = true))))
        router(reponseTrouve("11111111-2222-3333-4444-555555555555", idReleve = 42))

        val res = runBlocking { envoyerSortieVersGeoNature(store.charger().first(), store, config) }

        assertEquals("aucun relevé ne doit être créé", 0, relevesCrees.get())
        assertEquals("aucune occurrence ne doit partir", 0, occurrencesPostees.get())
        assertFalse("l'envoi doit être signalé en échec, pas passer pour un succès", res.succes)
        assertTrue(
            "le message doit expliquer, message = ${res.message}",
            res.message.contains("vérifier") || res.message.contains("réessayez"),
        )
    }

    @Test
    fun un_premier_envoi_jamais_tente_n_interroge_pas_le_serveur() {
        // Pas de vérification inutile : le coût d'un aller-retour par groupe ne se paie que lorsque
        // la création a réellement déjà été tentée.
        store.ajouter(Sortie(id = "s1", observations = listOf(obs("o1"))))
        val recherches = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.contains("/releves?") -> {
                        recherches.incrementAndGet()
                        json.setResponseCode(200).setBody(reponseVide)
                    }
                    path.endsWith("/only/releve") -> json.setResponseCode(200).setBody("""{"id":701}""")
                    else -> json.setResponseCode(200).setBody("{}")
                }
            }
        }

        runBlocking { envoyerSortieVersGeoNature(store.charger().first(), store, config) }

        assertEquals("aucune recherche par uuid sur un premier envoi", 0, recherches.get())
    }
}
