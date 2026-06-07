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

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto-mise à jour via les **releases GitHub** (canal de distribution de GeoMys). Vérifie la
 * dernière release **non** pre-release, compare à la version installée ([comparerVersions] /
 * `BuildConfig.VERSION_NAME`), télécharge l'APK et lance l'installation système.
 *
 * Limites Android : l'install ne réussit qu'entre APK signés du **même keystore** (release →
 * release) ; l'utilisateur doit autoriser « Installer des applications inconnues » au 1er usage.
 */
object MiseAJour {

    private const val API_LATEST = "https://api.github.com/repos/ANA-CEN-Ariege/GeoMys-android/releases/latest"
    // L'API GitHub exige un User-Agent, sinon 403.
    private const val USER_AGENT = "GeoMys-Android"

    sealed class Resultat {
        data class AJour(val version: String) : Resultat()
        data class Disponible(val version: String, val notes: String, val urlApk: String) : Resultat()
        data class Erreur(val message: String) : Resultat()
    }

    /** Interroge la dernière release non pre-release et compare à [versionInstallee] (VERSION_NAME). */
    suspend fun verifier(versionInstallee: String): Resultat = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code != 200) return@withContext Resultat.Erreur("GitHub HTTP $code")
            val obj = JSONObject(conn.inputStream.bufferedReader().readText())
            val versionDistante = obj.optString("tag_name", "").trim().removePrefix("v").removePrefix("V")
            if (versionDistante.isEmpty()) return@withContext Resultat.Erreur("Réponse GitHub inattendue")
            val notes = obj.optString("body", "").trim()
            val urlApk = obj.optJSONArray("assets")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                    ?.optString("browser_download_url", "")
            }.orEmpty()

            if (comparerVersions(versionDistante, versionInstallee) > 0 && urlApk.isNotEmpty())
                Resultat.Disponible(versionDistante, notes, urlApk)
            else
                Resultat.AJour(versionInstallee)
        } catch (e: Exception) {
            Resultat.Erreur(e.message ?: "Erreur réseau")
        }
    }

    /** Télécharge l'APK dans le cache et renvoie le fichier. [onProgress] reçoit un pourcentage
     *  0–100, ou -1 quand la taille totale est inconnue (progression indéterminée). */
    suspend fun telecharger(context: Context, url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true   // GitHub redirige vers objects.githubusercontent.com (https→https)
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val total = conn.contentLength
            val dest = File(context.cacheDir, "geomys-update.apk")
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lu = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        lu += n
                        onProgress(if (total > 0) ((lu * 100) / total).toInt() else -1)
                    }
                }
            }
            dest
        }

    /** Lance l'installation système de l'APK (via le FileProvider `${applicationId}.provider`). */
    fun installer(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
