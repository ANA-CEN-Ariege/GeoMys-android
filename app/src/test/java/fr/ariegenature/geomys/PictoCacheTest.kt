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

import fr.ariegenature.geomys.store.PictoCache
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Cache disque des pictogrammes de protocole monitoring ([PictoCache]) : résolution d'URL
 * ([PictoCache.urlPicto]), présence locale ([PictoCache.fichierLocal]), téléchargement via
 * MockWebServer ([PictoCache.telecharger]) et vidage ([PictoCache.vider]).
 *
 * Tests JVM PURS (pas de Robolectric) : le cache est pointé sur un dossier temporaire via
 * [PictoCache.initPourTests].
 *
 * LIMITE ASSUMÉE : `estImageValide` s'appuie sur `android.graphics.BitmapFactory` qui, avec le
 * stub android.jar des tests unitaires (`returnDefaultValues = true`), renvoie toujours des
 * dimensions 0×0 → TOUT contenu est jugé « non-image » en JVM. Le cas nominal « vraie image
 * JPEG persistée » n'est donc PAS testable ici (il exigerait Robolectric ou un test
 * instrumenté) ; on verrouille en revanche la règle de refus (rien d'écrit sur disque quand le
 * contenu n'est pas décodable — portail captif, page d'erreur HTML…) et toute la logique pure.
 */
class PictoCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dossier: File
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        dossier = tmp.newFolder("pictos")
        PictoCache.initPourTests(dossier)
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------ urlPicto

    @Test
    fun `urlPicto — module_picto URL absolue renvoyee telle quelle`() {
        assertEquals(
            "https://cdn.exemple.org/pictos/stom.png",
            PictoCache.urlPicto("https://srv.fr/geonature", "STOM", "https://cdn.exemple.org/pictos/stom.png"),
        )
        // Insensible à la casse du schéma.
        assertEquals(
            "HTTP://cdn/x.jpg",
            PictoCache.urlPicto("https://srv.fr", "STOM", "HTTP://cdn/x.jpg"),
        )
    }

    @Test
    fun `urlPicto — chemin absolu serveur prefixe par la base`() {
        assertEquals(
            "https://srv.fr/media/pictos/a.png",
            PictoCache.urlPicto("https://srv.fr/", "STOM", "/media/pictos/a.png"),
        )
    }

    @Test
    fun `urlPicto — nom de fichier image relatif prefixe par la base`() {
        assertEquals(
            "https://srv.fr/logo.WEBP", // extension insensible à la casse
            PictoCache.urlPicto("https://srv.fr", "STOM", "logo.WEBP"),
        )
    }

    @Test
    fun `urlPicto — code FontAwesome ou vide retombe sur la convention img_jpg`() {
        val attendu = "https://srv.fr/api/media/monitorings/STOM/img.jpg"
        // Défaut serveur fréquent : « fa-puzzle-piece » n'est PAS une image.
        assertEquals(attendu, PictoCache.urlPicto("https://srv.fr", "STOM", "fa-puzzle-piece"))
        assertEquals(attendu, PictoCache.urlPicto("https://srv.fr", "STOM", null))
        assertEquals(attendu, PictoCache.urlPicto("https://srv.fr", "STOM", "  "))
        // Base nettoyée (espaces + « / » final).
        assertEquals(attendu, PictoCache.urlPicto(" https://srv.fr/ ", "STOM", ""))
    }

    // ------------------------------------------------------------------ fichierLocal

    @Test
    fun `fichierLocal — absent renvoie null`() {
        assertNull(PictoCache.fichierLocal("STOM"))
    }

    @Test
    fun `fichierLocal — present et non vide renvoie le fichier`() {
        File(dossier, "STOM.jpg").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(File(dossier, "STOM.jpg"), PictoCache.fichierLocal("STOM"))
    }

    @Test
    fun `fichierLocal — fichier vide traite comme absent`() {
        File(dossier, "STOM.jpg").writeBytes(ByteArray(0))
        assertNull(PictoCache.fichierLocal("STOM"))
    }

    @Test
    fun `fichierLocal — slash du module_code assaini en underscore`() {
        // Un module_code contenant « / » ne doit pas sortir du dossier de cache.
        File(dossier, "A_B.jpg").writeBytes(byteArrayOf(1))
        assertEquals(File(dossier, "A_B.jpg"), PictoCache.fichierLocal("A/B"))
    }

    // ------------------------------------------------------------------ telecharger

    @Test
    fun `telecharger — 404 renvoie null et ecrit le marqueur negatif (pas d'image)`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(PictoCache.telecharger("STOM", server.url("/api/media/monitorings/STOM/img.jpg").toString()))
        // Cache NÉGATIF : le 404 est mémorisé par un marqueur vide `STOM.absent` — et rien d'autre.
        val marqueur = File(dossier, "STOM.absent")
        assertTrue("le marqueur négatif doit exister", marqueur.isFile)
        assertEquals("le marqueur doit être vide", 0L, marqueur.length())
        assertEquals("aucun autre fichier ne doit être écrit", listOf(marqueur), dossier.listFiles().orEmpty().toList())
    }

    @Test
    fun `telecharger — echec non-404 (5xx) renvoie null SANS marqueur negatif`() {
        // Un échec transitoire (serveur en carafe) ne doit pas être mémorisé 24 h.
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(PictoCache.telecharger("STOM", server.url("/img.jpg").toString()))
        assertEquals(0, dossier.listFiles().orEmpty().size)
    }

    @Test
    fun `telecharger — sans token ni cookies, pas d'en-tetes d'auth`() {
        server.enqueue(MockResponse().setResponseCode(404))
        PictoCache.telecharger("STOM", server.url("/img.jpg").toString())
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
    }

    @Test
    fun `telecharger — token et cookies fournis poses en Authorization Bearer et Cookie`() {
        // Session transmise SEULEMENT vers l'origine (hôte + schéma) de l'instance GeoNature
        // (audit sécurité 2026-08-27) : ici le picto est servi par le serveur lui-même.
        server.enqueue(MockResponse().setResponseCode(404))
        PictoCache.telecharger("STOM", server.url("/img.jpg").toString(), token = "tok123", cookies = "session=abc",
            base = server.url("/geonature").toString())
        val req = server.takeRequest()
        assertEquals("Bearer tok123", req.getHeader("Authorization"))
        assertEquals("session=abc", req.getHeader("Cookie"))
    }

    @Test
    fun `telecharger — picto hors de l'origine du serveur = jamais de session`() {
        // module_picto absolu vers un autre hôte (CDN, ancien serveur) : ni Bearer ni Cookie.
        server.enqueue(MockResponse().setResponseCode(404))
        PictoCache.telecharger("STOM", server.url("/img.jpg").toString(), token = "tok123", cookies = "session=abc",
            base = "https://autre-serveur.example.org/geonature")
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
    }

    @Test
    fun `telecharger — contenu non-image (portail captif HTML) renvoie null et n'ecrit rien`() {
        // Règle estImageValide : un 200 au contenu non décodable en image (portail captif,
        // proxy, page d'erreur) ne doit JAMAIS être persisté — il serait resservi hors-ligne
        // indéfiniment (audit 2026-08-23). Ni fichier final, ni .tmp orphelin.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>Connectez-vous au wifi de la gare</body></html>"),
        )
        assertNull(PictoCache.telecharger("STOM", server.url("/img.jpg").toString()))
        assertEquals("ni image ni .tmp ne doivent rester", 0, dossier.listFiles().orEmpty().size)
    }

    @Test
    fun `telecharger — corps vide renvoie null et n'ecrit rien`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        assertNull(PictoCache.telecharger("STOM", server.url("/img.jpg").toString()))
        assertEquals(0, dossier.listFiles().orEmpty().size)
    }

    @Test
    fun `telecharger — erreur reseau (serveur ferme) renvoie null sans lever`() {
        val url = server.url("/img.jpg").toString()
        server.shutdown() // connexion refusée
        assertNull(PictoCache.telecharger("STOM", url))
        assertEquals(0, dossier.listFiles().orEmpty().size)
    }

    // ------------------------------------------------------------------ fichierOuTelecharger (cache négatif)
    //
    // NB : la suppression du marqueur au téléchargement RÉUSSI n'est pas testable ici (même
    // limite assumée que le cas nominal : estImageValide rejette tout en JVM pur).

    @Test
    fun `fichierOuTelecharger — marqueur negatif frais court-circuite le reseau`() {
        File(dossier, "STOM.absent").writeBytes(ByteArray(0)) // lastModified = maintenant
        assertNull(PictoCache.fichierOuTelecharger(server.url("/gn").toString(), "STOM", null))
        assertEquals("aucune requête réseau ne doit partir", 0, server.requestCount)
    }

    @Test
    fun `fichierOuTelecharger — marqueur negatif perime retente le reseau et rafraichit le marqueur`() {
        val marqueur = File(dossier, "STOM.absent")
        marqueur.writeBytes(ByteArray(0))
        val perime = System.currentTimeMillis() - 25L * 60 * 60 * 1000 // 25 h > TTL 24 h
        assertTrue(marqueur.setLastModified(perime))
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(PictoCache.fichierOuTelecharger(server.url("/gn").toString(), "STOM", null))
        assertEquals("le réseau doit être retenté", 1, server.requestCount)
        assertTrue("le 404 doit rafraîchir le marqueur", marqueur.lastModified() > perime)
    }

    @Test
    fun `fichierOuTelecharger — image en cache prime sur le marqueur negatif`() {
        // État incohérent possible (image posée par prefetch après un vieux 404) : le fichier
        // local gagne, sans requête réseau.
        File(dossier, "STOM.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(dossier, "STOM.absent").writeBytes(ByteArray(0))
        assertEquals(
            File(dossier, "STOM.jpg"),
            PictoCache.fichierOuTelecharger(server.url("/gn").toString(), "STOM", null),
        )
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------ vider

    @Test
    fun `vider — supprime tous les pictos en cache et les marqueurs negatifs`() {
        File(dossier, "STOM.jpg").writeBytes(byteArrayOf(1, 2))
        File(dossier, "STERF.jpg").writeBytes(byteArrayOf(3))
        File(dossier, "STERF.absent").writeBytes(ByteArray(0))
        PictoCache.vider()
        assertEquals(0, dossier.listFiles().orEmpty().size)
        assertNull(PictoCache.fichierLocal("STOM"))
        assertNull(PictoCache.fichierLocal("STERF"))
    }

    @Test
    fun `vider — sans rien en cache ne leve pas`() {
        PictoCache.vider()
        assertTrue(dossier.isDirectory)
    }
}
