/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat

import fr.ariegenature.geonat.network.GeoNatureAuth
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Flux d'authentification GeoNature (POST /api/auth/login) via MockWebServer : extraction
 *  du token, de l'id_role et des cookies de session ; gestion des réponses d'échec. */
class GeoNatureAuthTest {

    private lateinit var server: MockWebServer
    private val base: String get() = server.url("/").toString().trimEnd('/')

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    @Test
    fun login_200_extrait_token_idrole_et_cookies() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "token=abc123; Path=/; HttpOnly")
                .setBody("""{"access_token":"abc123","user":{"id_role":42}}"""),
        )
        // login distinct par test → pas de collision avec le cache d'auth (TTL).
        val res = GeoNatureAuth.loginAvecCookies(base, "alice", "pwd")
        assertTrue(res != null)
        val (token, idRole, cookies) = res!!
        assertEquals("abc123", token)
        assertEquals(42, idRole)
        assertTrue(cookies.contains("token=abc123"))

        // La requête envoyée est bien un POST JSON sur /api/auth/login avec login+password.
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/auth/login", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"login\":\"alice\""))
        assertTrue(body.contains("\"password\":\"pwd\""))
    }

    @Test
    fun login_401_renvoie_null() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertNull(GeoNatureAuth.loginAvecCookies(base, "bob", "x"))
    }

    @Test
    fun login_200_mais_pas_json_renvoie_null() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>login</html>"),
        )
        assertNull(GeoNatureAuth.loginAvecCookies(base, "carol", "x"))
    }

    @Test
    fun token_lu_aussi_depuis_le_champ_token() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token":"xyz","user":{"id_role":7}}"""),
        )
        assertEquals("xyz", GeoNatureAuth.loginAvecCookies(base, "dave", "x")!!.first)
    }
}
