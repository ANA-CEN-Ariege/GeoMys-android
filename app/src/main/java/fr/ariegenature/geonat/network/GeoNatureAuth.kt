package fr.ariegenature.geonat.network

import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL

object GeoNatureAuth {

    suspend fun testerConnexion(config: GeoNatureConfig): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val base = config.urlServeur.trim().trimEnd('/')
                val url = URL("$base/api/auth/login")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("login", config.login).put("password", config.motDePasse).toString()
                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val code = conn.responseCode
                val ct = conn.getHeaderField("Content-Type") ?: ""
                if (!ct.contains("json")) {
                    return@withContext Pair(false, "Mauvaise URL : réponse HTML reçue. Essayez d'ajouter ou retirer /GeoNature à l'URL.")
                }
                when (code) {
                    200 -> {
                        val json = JSONObject(conn.inputStream.bufferedReader().readText())
                        val token = extractToken(json)
                        // Sauvegarde du nom complet de l'utilisateur (utilisé comme déterminateur
                        // par défaut dans la saisie multi-taxons) et de son id_role (pour la
                        // pré-sélection dans les champs observers de saisie monitoring). Best-effort.
                        extraireNomComplet(json)?.let { config.nomUtilisateur = it }
                        extraireIdRole(json)?.let { config.idRoleUtilisateur = it }
                        if (token != null) Pair(true, "Connexion réussie")
                        else Pair(false, "Token absent de la réponse")
                    }
                    401, 403 -> Pair(false, "Identifiants incorrects (HTTP $code)")
                    else -> Pair(false, "Erreur serveur HTTP $code")
                }
            } catch (e: Exception) {
                Pair(false, "Impossible de joindre le serveur : ${e.message}")
            }
        }

    /** Cache de session du dernier login réussi : permet aux appels en rafale (par ex. 73
     *  chargerObjet en parallèle pour la carte d'un protocole) de ne pas re-authentifier
     *  chaque fois. Invalidé quand (base, login) change ou après [CACHE_TTL_MS]. */
    private data class CacheAuth(
        val base: String,
        val login: String,
        val token: String?,
        val idRole: Int?,
        val cookies: String,
        val expireAt: Long,
    )
    @Volatile private var cache: CacheAuth? = null
    private const val CACHE_TTL_MS = 5L * 60L * 1000L // 5 minutes

    /** Vidage explicite du cache d'auth (à appeler depuis l'écran config quand l'utilisateur
     *  modifie ses identifiants ou l'URL serveur). */
    fun invaliderCache() { cache = null }

    // Retourne (token, idRole, cookies) — cookies à renvoyer avec les appels suivants
    internal fun loginAvecCookies(base: String, login: String, password: String): Triple<String?, Int?, String>? {
        val now = System.currentTimeMillis()
        cache?.let { c ->
            if (c.base == base && c.login == login && c.expireAt > now) {
                return Triple(c.token, c.idRole, c.cookies)
            }
        }
        return try {
            val url = URL("$base/api/auth/login")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("login", login).put("password", password).toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            if (conn.responseCode != 200) return null
            val ct = conn.getHeaderField("Content-Type") ?: ""
            if (!ct.contains("json")) return null

            val cookies = conn.headerFields["Set-Cookie"]
                ?.joinToString("; ") { it.substringBefore(";").trim() }
                ?: ""
            val jsonText = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(jsonText)
            val token = extractToken(json)

            // Sur GeoNature, id_role est souvent dans l'objet "user"
            val userJson = json.optJSONObject("user")
            val idRole = userJson?.optInt("id_role", -1)?.takeIf { it > 0 }
                ?: json.optInt("id_role", -1).takeIf { it > 0 }

            cache = CacheAuth(base, login, token, idRole, cookies, now + CACHE_TTL_MS)
            Triple(token, idRole, cookies)
        } catch (e: Exception) { null }
    }

    internal fun login(base: String, login: String, password: String): Pair<String?, Int?>? =
        loginAvecCookies(base, login, password)?.let { (token, idRole, _) -> Pair(token, idRole) }

    /** Extrait le nom complet (prénom + nom) du payload JSON de login. Renvoie null si absent. */
    private fun extraireNomComplet(json: JSONObject): String? {
        val userJson = json.optJSONObject("user") ?: return null
        val prenom = userJson.optString("prenom_role", "").trim()
        val nom = userJson.optString("nom_role", "").trim()
        val complet = listOf(prenom, nom).filter { it.isNotEmpty() }.joinToString(" ")
        return complet.takeIf { it.isNotEmpty() }
    }

    /** Extrait l'id_role de l'utilisateur connecté du payload de login. Renvoie null si absent. */
    private fun extraireIdRole(json: JSONObject): Int? {
        val userJson = json.optJSONObject("user")
        return userJson?.optInt("id_role", -1)?.takeIf { it > 0 }
            ?: json.optInt("id_role", -1).takeIf { it > 0 }
    }

    private fun extractToken(json: JSONObject): String? {
        val userJson = json.optJSONObject("user")
        return json.optString("access_token").takeIf { it.isNotEmpty() }
            ?: json.optString("token").takeIf { it.isNotEmpty() }
            ?: userJson?.optString("access_token").takeIf { !it.isNullOrEmpty() }
            ?: userJson?.optString("token").takeIf { !it.isNullOrEmpty() }
    }
}