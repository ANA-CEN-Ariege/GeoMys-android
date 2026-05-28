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
                // GeoNature renvoie un 302 vers `/#/login` (la SPA HTML) quand les identifiants
                // sont invalides au lieu d'un 401 propre. Si on laissait HttpURLConnection
                // suivre automatiquement, on tomberait sur la SPA et on diagnostiquerait à tort
                // une mauvaise URL. Parité gn_mobile_monitoring (login_api_impl.dart).
                conn.instanceFollowRedirects = false
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("login", config.login).put("password", config.motDePasse).toString()
                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val code = conn.responseCode
                // Distinction fine des réponses GeoNature observées en prod :
                //   - HTTP 200 + body JSON avec token → succès,
                //   - HTTP 200 + body JSON sans token / avec `msg` → auth refusée en mode "soft",
                //   - HTTP 200 + body HTML → vraie mauvaise URL (la racine du site sert la SPA),
                //   - HTTP 302 vers `#/login` ou hors `/api/` → identifiants invalides
                //     (cas Apache/Gunicorn qui transforme un échec auth en redirect SPA),
                //   - HTTP 302 HTTP→HTTPS → l'utilisateur doit corriger l'URL,
                //   - HTTP 401/403 → identifiants invalides (cas propre),
                //   - HTTP 404 → URL serveur introuvable.
                when (code) {
                    in 300..399 -> diagnostiquerRedirect(conn, url)
                    200 -> {
                        val bodyText = try {
                            conn.inputStream.bufferedReader().readText()
                        } catch (_: Exception) { "" }
                        val json: JSONObject? = try { JSONObject(bodyText) } catch (_: Exception) { null }
                        if (json == null) {
                            // Pas du JSON parsable → typiquement la SPA HTML servie quand l'URL
                            // ne pointe pas vers l'API (cas le plus fréquent ici : racine du
                            // site GeoNature au lieu de l'URL d'instance).
                            return@withContext Pair(
                                false,
                                "L'URL saisie ne pointe pas vers une API GeoNature : réponse HTML reçue. Vérifiez l'URL du serveur.",
                            )
                        }
                        val token = extractToken(json)
                        if (token == null) {
                            // JSON valide mais sans token → message d'erreur d'auth en HTTP 200
                            // (pattern Flask-JWT-Extended sur certaines instances).
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

    /** Diagnostique une réponse 3xx du serveur GeoNature en se basant sur le header `Location`.
     *  Trois sorties possibles, alignées sur la logique gn_mobile_monitoring :
     *
     *  - **HTTP → HTTPS** : le serveur redirige vers son équivalent HTTPS. Le POST initial
     *    serait perdu si Dart/Java rejouait la requête — on demande à l'utilisateur de mettre
     *    à jour son URL pour éviter d'envoyer son mot de passe en clair.
     *  - **Redirect SPA** : URL cible contient `#` (= fragment Angular `/#/login`), OU le
     *    redirect SORT de `/api/` (origine sous `/api/auth/login`, cible hors). C'est le
     *    pattern GeoNature pour signaler des identifiants invalides via une redirection.
     *  - **Autre** : redirection inattendue, on rapporte le code + le Location pour aider au
     *    diagnostic. */
    private fun diagnostiquerRedirect(
        conn: java.net.HttpURLConnection,
        urlOrigine: URL,
    ): Pair<Boolean, String> {
        val location = conn.getHeaderField("Location").orEmpty()
        val code = conn.responseCode
        return when {
            urlOrigine.protocol == "http" && location.startsWith("https://", ignoreCase = true) ->
                Pair(
                    false,
                    "Le serveur redirige vers HTTPS. Modifiez l'URL pour qu'elle commence par « https:// ».",
                )
            location.contains("#") ||
                (urlOrigine.path.contains("/api/") && !location.contains("/api/")) ->
                Pair(false, "Identifiant ou mot de passe incorrect")
            else ->
                Pair(false, "Redirection inattendue ($code) → $location")
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