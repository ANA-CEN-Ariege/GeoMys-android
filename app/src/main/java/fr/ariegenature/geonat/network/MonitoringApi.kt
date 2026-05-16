package fr.ariegenature.geonat.network

import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** Module de suivi (protocole) côté gn_module_monitoring. Chaque entrée correspond à un protocole
 *  configuré sur le serveur GeoNature (suivi gypaète, suivi mares, etc.).
 *  Tous les champs après [moduleDesc] sont nullables : leur présence dépend du schéma de
 *  sérialisation de l'instance (TModules + TModuleComplement). */
data class MonitoringModule(
    val idModule: Int,
    val moduleCode: String,
    val moduleLabel: String,
    val moduleDesc: String? = null,
    val modulePicto: String? = null,
    val activeFrontend: Boolean? = null,
    val activeBackend: Boolean? = null,
    val bSynthese: Boolean? = null,
    val idListObserver: Int? = null,
    val idListTaxonomy: Int? = null,
    val metaCreateDate: String? = null,
    val metaUpdateDate: String? = null,
)

object MonitoringApi {

    /** Cache mémoire de la dernière liste chargée. Permet à l'écran de détail de retrouver
     *  un protocole par son [moduleCode] sans repasser les ~10 champs via Bundle args. */
    @Volatile private var dernierChargement: List<MonitoringModule> = emptyList()

    fun moduleParCode(moduleCode: String): MonitoringModule? =
        dernierChargement.firstOrNull { it.moduleCode == moduleCode }

    /** GET /api/monitorings/modules — liste les modules de suivi disponibles sur l'instance.
     *  Renvoie [] silencieusement si HTTP 404 (gn_module_monitoring non installé).
     *  Sur toute autre erreur (timeout, 5xx, parse), propage l'exception au caller. */
    suspend fun chargerModules(config: GeoNatureConfig): List<MonitoringModule> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)

            val url = URL("$base/api/monitorings/modules")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
            val code = conn.responseCode
            if (code == 404) return@withContext emptyList() // gn_module_monitoring absent
            if (code != 200) throw GNErreur.EnvoiEchoue(code, "Modules monitoring : HTTP $code")

            val text = conn.inputStream.bufferedReader().readText()
            val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                val obj = JSONObject(text)
                obj.optJSONArray("data") ?: obj.optJSONArray("items") ?: obj.optJSONArray("modules")
                    ?: throw GNErreur.EnvoiEchoue(code, "Modules monitoring : format JSON inattendu")
            }
            val result = mutableListOf<MonitoringModule>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val idModule = item.optInt("id_module", -1).takeIf { it > 0 } ?: continue
                val moduleCode = item.optString("module_code", "").ifEmpty { continue }
                val label = item.optString("module_label", moduleCode).ifEmpty { moduleCode }
                val desc = item.optString("module_desc", "").takeIf { it.isNotEmpty() }
                result.add(
                    MonitoringModule(
                        idModule = idModule,
                        moduleCode = moduleCode,
                        moduleLabel = label,
                        moduleDesc = desc,
                        modulePicto = item.optString("module_picto", "").takeIf { it.isNotEmpty() },
                        activeFrontend = if (item.has("active_frontend")) item.optBoolean("active_frontend") else null,
                        activeBackend = if (item.has("active_backend")) item.optBoolean("active_backend") else null,
                        bSynthese = if (item.has("b_synthese")) item.optBoolean("b_synthese") else null,
                        idListObserver = item.optInt("id_list_observer", -1).takeIf { it > 0 },
                        idListTaxonomy = item.optInt("id_list_taxonomy", -1).takeIf { it > 0 },
                        metaCreateDate = item.optString("meta_create_date", "").takeIf { it.isNotEmpty() },
                        metaUpdateDate = item.optString("meta_update_date", "").takeIf { it.isNotEmpty() },
                    )
                )
            }
            result.sortedBy { it.moduleLabel.lowercase() }.also { dernierChargement = it }
        }

    /** Un enfant (site, sites_group, …) d'un module monitoring : id technique + nom lisible. */
    data class MonitoringEnfant(val id: Int, val nom: String)

    /** GET /api/monitorings/object/<module_code>/module?depth=1 — récupère le module et la liste
     *  de ses enfants directs avec leur nom lisible. Map `object_type → [enfants]`. Null sur
     *  erreur réseau/auth, map vide si aucun enfant. */
    suspend fun chargerEnfants(config: GeoNatureConfig, moduleCode: String): Map<String, List<MonitoringEnfant>>? =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: return@withContext null

            val url = URL("$base/api/monitorings/object/$moduleCode/module?depth=1")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
            val code = conn.responseCode
            if (code != 200) return@withContext null
            val text = conn.inputStream.bufferedReader().readText()
            val obj = try { JSONObject(text) } catch (_: Exception) { return@withContext null }
            val children = obj.optJSONObject("children") ?: return@withContext emptyMap()
            val result = linkedMapOf<String, List<MonitoringEnfant>>()
            val it = children.keys()
            while (it.hasNext()) {
                val type = it.next()
                val arr = children.optJSONArray(type) ?: continue
                val liste = mutableListOf<MonitoringEnfant>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optInt("id", item.optInt("${type}_id", -1))
                    val props = item.optJSONObject("properties")
                    val nom = extraireNom(props, type, id)
                    liste.add(MonitoringEnfant(id, nom))
                }
                result[type] = liste.sortedBy { it.nom.lowercase() }
            }
            result
        }

    /** Cherche le champ "nom" d'un objet monitoring. gn_module_monitoring nomme typiquement
     *  ses champs `base_<type>_name` ou `<type>_name` ; on essaie aussi `name`/`label` en dernier
     *  recours. Fallback : "#<id>". */
    private fun extraireNom(props: JSONObject?, type: String, id: Int): String {
        if (props != null) {
            val candidats = listOf("base_${type}_name", "${type}_name", "base_site_name", "name", "label")
            for (c in candidats) {
                val v = props.optString(c, "")
                if (v.isNotEmpty() && v != "null") return v
            }
        }
        return if (id > 0) "#$id" else "—"
    }

    /** GET /api/monitorings/config/<module_code> — récupère les labels per-`object_type` déclarés
     *  par le protocole dans son config/objects.json serveur. Pour STOM par exemple :
     *    { "sites_group": { "label": "Site STOM", "label_list": "Sites STOM" },
     *      "site":        { "label": "Point d'écoute", "label_list": "Points d'écoute" } }
     *  Renvoie un map `object_type → label_list (sinon label)`. Null si l'endpoint échoue. */
    suspend fun chargerLabelsObjets(config: GeoNatureConfig, moduleCode: String): Map<String, String>? =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: return@withContext null

            val url = URL("$base/api/monitorings/config/$moduleCode")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
            val code = conn.responseCode
            if (code != 200) return@withContext null
            val text = conn.inputStream.bufferedReader().readText()
            val obj = try { JSONObject(text) } catch (_: Exception) { return@withContext null }
            val labels = linkedMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val key = it.next()
                val v = obj.optJSONObject(key) ?: continue
                val label = v.optString("label_list", "").ifEmpty { v.optString("label", "") }
                if (label.isNotEmpty()) labels[key] = label
            }
            labels
        }
}