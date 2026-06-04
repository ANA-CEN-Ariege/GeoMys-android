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

package fr.ariegenature.geomys.network

import java.net.HttpURLConnection
import java.net.URL

/** Factorise la configuration répétitive de [HttpURLConnection] pour les appels GeoNature :
 *  timeouts + en-têtes `Accept`/`Authorization: Bearer`/`Cookie`. L'appelant garde la main sur
 *  TOUT le reste (lecture de `responseCode`, du body, écriture du body POST, gestion d'erreur et
 *  des fallbacks cache) — le helper ne fait que monter la connexion, il ne la consomme pas.
 *
 *  N'embarque PAS les deux cas particuliers : le POST de login (`instanceFollowRedirects=false`,
 *  sans `Accept` ni auth, cf. [GeoNatureAuth]) et l'upload multipart (cf. [GeoNatureUpload]), qui
 *  restent codés explicitement vu leurs en-têtes spécifiques. */
internal object HttpClient {

    /** GET JSON. `token`/`cookies` optionnels (en-têtes ajoutés seulement si non vides). */
    fun get(
        url: URL,
        token: String? = null,
        cookies: String = "",
        timeoutMs: Int = 10000,
        readTimeoutMs: Int = timeoutMs,
    ): HttpURLConnection = configurer(url, token, cookies, timeoutMs, readTimeoutMs)

    /** POST avec corps JSON : positionne `method`/`doOutput`/`Content-Type: application/json`.
     *  L'appelant écrit ensuite le body via `outputStream` puis lit `responseCode`.
     *
     *  `Connection: close` : interdit la réutilisation keep-alive. Sans ça, HttpURLConnection
     *  peut REJOUER silencieusement un POST quand la connexion réutilisée s'avère périmée
     *  (bascule réseau, serveur qui a fermé) — le serveur traite alors la requête DEUX fois
     *  alors que l'app ne voit qu'un succès → relevés/occurrences/visites en double. Le coût
     *  (une connexion neuve par POST) est négligeable au volume de l'app ; la non-duplication
     *  des données prime. */
    fun postJson(
        url: URL,
        token: String? = null,
        cookies: String = "",
        timeoutMs: Int = 10000,
        readTimeoutMs: Int = timeoutMs,
    ): HttpURLConnection = configurer(url, token, cookies, timeoutMs, readTimeoutMs).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Connection", "close")
    }

    /** DELETE JSON. */
    fun delete(
        url: URL,
        token: String? = null,
        cookies: String = "",
        timeoutMs: Int = 15000,
        readTimeoutMs: Int = timeoutMs,
    ): HttpURLConnection = configurer(url, token, cookies, timeoutMs, readTimeoutMs).apply {
        requestMethod = "DELETE"
    }

    private fun configurer(
        url: URL,
        token: String?,
        cookies: String,
        timeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
        return conn
    }
}
