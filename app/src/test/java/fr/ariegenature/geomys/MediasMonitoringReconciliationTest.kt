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
import java.io.File
import java.util.Collections

/**
 * MÉDIAS MONITORING : un « Réessayer » ne doit renvoyer aucune photo déjà détenue par le serveur.
 *
 * DÉFAUT CONSTATÉ SUR LE TERRAIN (2026-09-16) : trois photos, réseau coupé pendant l'envoi ; la
 * visite part, les photos non ; réseau rétabli, ré-envoi — et UNE photo se retrouve en double sur
 * le serveur. Exactement celle qui était EN VOL au moment de la coupure.
 *
 * Mémoriser les fichiers dont la réponse est revenue ne suffit donc pas : un POST parti, reçu et
 * traité par le serveur, mais dont la RÉPONSE se perd, ne laisse aucune trace locale — et repart.
 * C'est le même défaut que R1-C1 pour les occurrences, transposé aux fichiers. La seule source de
 * vérité est le serveur lui-même : on lui demande ce qu'il détient déjà pour cet objet
 * (`GET /api/gn_commons/medias/<uuid_attached_row>`), et l'appariement se fait sur `title_fr`, que
 * l'application contrôle et rend DÉTERMINISTE par fichier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediasMonitoringReconciliationTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig
    private val titresUploades = Collections.synchronizedList(mutableListOf<String>())
    private var listagesDemandes = 0

    private val uuidObjet = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val titreBase = "visit aaaaaaaa"

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        server = MockWebServer().apply { start() }
        NomenclatureCache.init(ctx)
        NomenclatureCache.setAll(mapOf("TYPE_MEDIA" to listOf(NomValeur(7, "Photo"))))
        config = GeoNatureConfig(ctx).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"
        }
        titresUploades.clear()
        listagesDemandes = 0
    }

    @After fun tearDown() { server.shutdown() }

    /** Fichiers réels : l'upload lit le contenu sur le disque. */
    private fun fichier(nom: String): String {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(ctx.filesDir, "medias").apply { mkdirs() }
        val f = File(dir, nom).apply { writeText("contenu de $nom") }
        return "file://${f.absolutePath}"
    }

    /** [dejaLa] = titres que le serveur déclare déjà détenir pour l'objet. */
    private fun router(dejaLa: List<String>, listageEnPanne: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path.startsWith("/api/auth/login") ->
                        json.setResponseCode(200).setBody("""{"access_token":"t","user":{"id_role":1}}""")
                    path.contains("get_id_table_location") ->
                        json.setResponseCode(200).setBody("55")
                    path.startsWith("/api/gn_commons/medias/") -> {
                        listagesDemandes++
                        if (listageEnPanne) json.setResponseCode(500).setBody("{}")
                        else json.setResponseCode(200).setBody(
                            dejaLa.joinToString(",", "[", "]") { """{"id_media":1,"title_fr":"$it"}""" }
                        )
                    }
                    path.startsWith("/api/gn_commons/media") -> {
                        val corps = request.body.readUtf8()
                        val titre = Regex("""name="title_fr"\r\n\r\n(.*?)\r\n""", RegexOption.DOT_MATCHES_ALL)
                            .find(corps)?.groupValues?.get(1)?.trim()
                        titresUploades.add(titre ?: "?")
                        json.setResponseCode(200).setBody("""{"id_media":9}""")
                    }
                    else -> json.setResponseCode(200).setBody("{}")
                }
            }
        }
    }

    private fun envoyer(chemins: List<String>, reconcilier: Boolean) = runBlocking {
        GeoNatureUpload.uploaderMediaMonitoring(
            config = config, mediaPaths = chemins,
            schemaDotTable = "gn_monitoring.t_base_visits",
            uuidAttachedRow = uuidObjet, titre = titreBase, author = "alice",
            reconcilier = reconcilier,
        )
    }

    @Test
    fun le_reessai_ne_renvoie_pas_la_photo_dont_la_reponse_s_etait_perdue() {
        val a = fichier("a.jpg"); val b = fichier("b.jpg"); val c = fichier("c.jpg")
        // Le serveur détient déjà a (réponse reçue) ET b (POST arrivé, réponse perdue : l'appareil
        // l'ignore). Seule c doit repartir.
        router(dejaLa = listOf(
            GeoNatureUpload.titreMedia(titreBase, a),
            GeoNatureUpload.titreMedia(titreBase, b),
        ))

        val res = envoyer(listOf(a, b, c), reconcilier = true)

        assertEquals(
            "seule la photo réellement absente du serveur doit être envoyée",
            listOf(GeoNatureUpload.titreMedia(titreBase, c)), titresUploades.toList(),
        )
        assertTrue(res.ok)
        assertEquals(
            "les trois photos sont désormais acquises, y compris celles déjà détenues",
            3, res.transmis.size,
        )
    }

    @Test
    fun un_premier_envoi_n_interroge_pas_le_serveur() {
        val a = fichier("a.jpg")
        router(dejaLa = emptyList())

        envoyer(listOf(a), reconcilier = false)

        assertEquals("pas d'aller-retour inutile sur un premier envoi", 0, listagesDemandes)
        assertEquals(1, titresUploades.size)
    }

    @Test
    fun listage_en_panne_on_envoie_quand_meme() {
        // Si l'on ne peut pas savoir ce que le serveur détient, on envoie : un doublon de photo est
        // ennuyeux, des photos de terrain qui n'arrivent jamais le sont davantage. C'est l'arbitrage
        // inverse de celui retenu pour les relevés, où un doublon est une donnée fausse en base.
        val a = fichier("a.jpg")
        router(dejaLa = emptyList(), listageEnPanne = true)

        val res = envoyer(listOf(a), reconcilier = true)

        assertEquals(1, titresUploades.size)
        assertTrue(res.ok)
    }

    @Test
    fun le_titre_ne_depend_pas_de_la_position_dans_la_liste() {
        // Un titre indexé « (1) », « (2) » se décalait dès qu'un fichier était sauté, rendant toute
        // réconciliation impossible : le même fichier changeait de nom d'un essai à l'autre.
        val b = fichier("b.jpg")
        assertEquals(
            GeoNatureUpload.titreMedia(titreBase, b),
            GeoNatureUpload.titreMedia(titreBase, b),
        )
        assertTrue(GeoNatureUpload.titreMedia(titreBase, b).endsWith("b.jpg"))
    }
}
