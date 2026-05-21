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
                // L'API GeoNature peut répondre à un mauvais mot de passe par :
                //   - HTTP 401/403 + body JSON (cas propre, traité explicitement),
                //   - HTTP 200 + body JSON sans token (cas observé sur certaines instances),
                //   - HTTP 200 + body texte d'erreur (rare mais possible).
                // Le cas "HTTP 200 + body HTML" indique réellement une mauvaise URL serveur.
                // On tente donc systématiquement de parser le body comme JSON pour distinguer
                // erreur d'auth vs mauvaise URL.
                when (code) {
                    200 -> {
                        val bodyText = try {
                            conn.inputStream.bufferedReader().readText()
                        } catch (_: Exception) { "" }
                        val json: JSONObject? = try { JSONObject(bodyText) } catch (_: Exception) { null }
                        if (json == null) {
                            // Pas du JSON parsable → probablement une page HTML (login Apache, etc.).
                            return@withContext Pair(false, "Mauvaise URL : réponse HTML reçue. Essayez d'ajouter ou retirer /GeoNature à l'URL.")
                        }
                        val token = extractToken(json)
                        if (token == null) {
                            // JSON valide mais sans token → c'est probablement un message d'erreur
                            // d'auth retourné en HTTP 200 (pattern fréquent avec Flask-JWT-Extended).
                            val msgErreur = json.optString("msg", "")
                                .ifEmpty { json.optString("message", "") }
                                .ifEmpty { json.optString("error", "") }
                            val ressembleAuth = msgErreur.contains("identifi", ignoreCase = true)
                                || msgErreur.contains("password", ignoreCase = true)
                                || msgErreur.contains("mot de passe", ignoreCase = true)
                                || msgErreur.contains("login", ignoreCase = true)
                                || msgErreur.contains("auth", ignoreCase = true)
                                || msgErreur.contains("invalid", ignoreCase = true)
                                || msgErreur.contains("incorrect", ignoreCase = true)
                            return@withContext Pair(
                                false,
                                if (ressembleAuth || msgErreur.isEmpty())
                                    "Identifiant ou mot de passe incorrect"
                                else msgErreur,
                            )
                        }
                        // Sauvegarde du nom complet de l'utilisateur (utilisé comme déterminateur
                        // par défaut dans la saisie multi-taxons) et de son id_role (pour la
                        // pré-sélection dans les champs observers de saisie monitoring). Best-effort.
                        extraireNomComplet(json)?.let { config.nomUtilisateur = it }
                        extraireIdRole(json)?.let { config.idRoleUtilisateur = it }
                        Pair(true, "Connexion réussie")
                    }
                    401, 403 -> Pair(false, "Identifiant ou mot de passe incorrect")
                    404 -> Pair(false, "URL serveur introuvable (HTTP 404) — vérifiez l'adresse")
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