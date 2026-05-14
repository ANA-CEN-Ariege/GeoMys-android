package fr.ariegenature.geonat.network

import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** Module de suivi (protocole) côté gn_module_monitoring. Chaque entrée correspond à un protocole
 *  configuré sur le serveur GeoNature (suivi gypaète, suivi mares, etc.). */
data class MonitoringModule(
    val idModule: Int,
    val moduleCode: String,
    val moduleLabel: String,
    val moduleDesc: String? = null,
)

object MonitoringApi {

    /** GET /api/monitorings/modules — liste les modules de suivi disponibles sur l'instance.
     *  Retourne une liste vide si l'instance n'a pas gn_module_monitoring installé (404) ou
     *  si l'utilisateur n'a pas les droits CRUVED de lecture. */
    suspend fun chargerModules(config: GeoNatureConfig): List<MonitoringModule> =
        withContext(Dispatchers.IO) {
            try {
                val base = config.urlServeur.trim().trimEnd('/')
                val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                    ?: return@withContext emptyList()

                val url = URL("$base/api/monitorings/modules")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                if (conn.responseCode != 200) return@withContext emptyList()

                val text = conn.inputStream.bufferedReader().readText()
                val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                    val obj = JSONObject(text)
                    obj.optJSONArray("data") ?: obj.optJSONArray("items") ?: obj.optJSONArray("modules")
                        ?: return@withContext emptyList()
                }
                val result = mutableListOf<MonitoringModule>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val idModule = item.optInt("id_module", -1).takeIf { it > 0 } ?: continue
                    val moduleCode = item.optString("module_code", "").ifEmpty { continue }
                    val label = item.optString("module_label", moduleCode).ifEmpty { moduleCode }
                    val desc = item.optString("module_desc", "").takeIf { it.isNotEmpty() }
                    result.add(MonitoringModule(idModule, moduleCode, label, desc))
                }
                result.sortedBy { it.moduleLabel.lowercase() }
            } catch (_: Exception) { emptyList() }
        }
}
