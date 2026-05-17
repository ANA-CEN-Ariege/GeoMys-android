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

    /** Un enfant (site, sites_group, …) d'un module monitoring : id technique + nom "best-effort"
     *  extrait à la volée + map plate des propriétés strings (pour ré-extraire le nom côté UI
     *  une fois le schéma chargé). */
    data class MonitoringEnfant(
        val id: Int,
        val nom: String,
        val proprietes: Map<String, String>,
    )

    /** Schéma d'un object_type déclaré par un protocole dans son `config/objects.json` serveur,
     *  tel que renvoyé par /api/monitorings/config/<module_code>. Tous les champs sont nullables :
     *  les vieux protocoles ou les protocoles minimalistes n'exposent pas forcément tout. */
    data class MonitoringSchemaObjet(
        val type: String,
        val label: String?,
        val labelList: String?,
        /** Nom du champ de `properties` à utiliser comme libellé d'une instance (par ex.
         *  `base_site_name` pour site, `sites_group_name` pour sites_group). Si présent, prime
         *  sur l'extraction heuristique. */
        val nameField: String?,
        /** object_type parent dans la hiérarchie. "module" pour les types directement attachés
         *  au protocole, ou null si non déclaré. */
        val parentType: String?,
        /** object_types enfants directs déclarés. Permet de savoir, pour `module`, quels types
         *  sont au niveau "macro" (= ce que l'utilisateur appelle "site"). */
        val childrenTypes: List<String>,
    )

    /** GET /api/monitorings/object/<module_code>/module?depth=1 — récupère le module et la liste
     *  de ses enfants directs avec leurs propriétés brutes. Map `object_type → [enfants]`. Null
     *  sur erreur réseau/auth, map vide si aucun enfant. */
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
                    val proprietesPlates = aplatirProprietes(props)
                    val nom = extraireNomHeuristique(proprietesPlates, type, id)
                    liste.add(MonitoringEnfant(id, nom, proprietesPlates))
                }
                result[type] = liste
            }
            result
        }

    /** Aplatit le bloc `properties` d'un objet monitoring en `Map<String, String>`. Filtre les
     *  valeurs null/objets/tableaux : on garde uniquement les scalaires (string, number, bool)
     *  utiles à l'affichage. */
    private fun aplatirProprietes(props: JSONObject?): Map<String, String> {
        if (props == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        val it = props.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = props.opt(k)
            when (v) {
                null, JSONObject.NULL -> { /* skip */ }
                is String -> if (v.isNotEmpty()) map[k] = v
                is Number, is Boolean -> map[k] = v.toString()
                else -> { /* on ignore arrays/objects pour l'instant */ }
            }
        }
        return map
    }

    /** Fallback heuristique pour le nom d'un enfant quand le schéma n'est pas disponible.
     *  Essaie `base_<type>_name`, `<type>_name`, `base_site_name`, `name`, `label`. Sinon `#id`. */
    internal fun extraireNomHeuristique(proprietes: Map<String, String>, type: String, id: Int): String {
        val candidats = listOf("base_${type}_name", "${type}_name", "base_site_name", "name", "label")
        for (c in candidats) proprietes[c]?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (id > 0) "#$id" else "—"
    }

    /** Un objet monitoring complet : type + id + propriétés plates + enfants directs (1 niveau).
     *  Sert pour les fiches site/visite/observation, toutes pilotées par le même renderer
     *  générique côté UI. [geometrie] = libellé court formaté (ex. "44.123°N, 1.456°E" pour un
     *  Point, "Polygone" pour des surfaces) ou null si pas de géométrie côté serveur. */
    data class MonitoringObjet(
        val type: String,
        val id: Int,
        val moduleCode: String,
        val proprietes: Map<String, String>,
        val enfants: Map<String, List<MonitoringEnfant>>,
        val geometrie: String?,
    )

    /** GET /api/monitorings/object/<module_code>/<object_type>/<id>?depth=1 — fiche d'un objet
     *  (site, visite, observation, …) avec ses propriétés et ses enfants directs. Null si
     *  l'endpoint échoue. */
    suspend fun chargerObjet(
        config: GeoNatureConfig,
        moduleCode: String,
        objectType: String,
        id: Int,
    ): MonitoringObjet? =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: return@withContext null

            val url = URL("$base/api/monitorings/object/$moduleCode/$objectType/$id?depth=1")
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
            val proprietes = aplatirProprietes(obj.optJSONObject("properties"))
            val enfants = linkedMapOf<String, List<MonitoringEnfant>>()
            obj.optJSONObject("children")?.let { childrenObj ->
                val it = childrenObj.keys()
                while (it.hasNext()) {
                    val ctype = it.next()
                    val arr = childrenObj.optJSONArray(ctype) ?: continue
                    val liste = mutableListOf<MonitoringEnfant>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val cid = item.optInt("id", item.optInt("${ctype}_id", -1))
                        val cprops = aplatirProprietes(item.optJSONObject("properties"))
                        val cnom = extraireNomHeuristique(cprops, ctype, cid)
                        liste.add(MonitoringEnfant(cid, cnom, cprops))
                    }
                    enfants[ctype] = liste
                }
            }
            MonitoringObjet(
                type = objectType,
                id = id,
                moduleCode = moduleCode,
                proprietes = proprietes,
                enfants = enfants,
                geometrie = formatGeometrie(obj.optJSONObject("geometry")),
            )
        }

    /** Convertit un objet GeoJSON en libellé court pour affichage. Point → "lat°N/S, lon°E/W",
     *  Polygon/MultiPolygon → "Polygone (N sommets)", autres → le type GeoJSON brut. */
    private fun formatGeometrie(geo: JSONObject?): String? {
        if (geo == null) return null
        val type = geo.optString("type", "").ifEmpty { return null }
        val coords = geo.opt("coordinates")
        return when (type) {
            "Point" -> {
                val arr = coords as? JSONArray ?: return type
                if (arr.length() < 2) return type
                val lon = arr.optDouble(0, Double.NaN)
                val lat = arr.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return type
                val ns = if (lat >= 0) "N" else "S"
                val ew = if (lon >= 0) "E" else "W"
                "%.5f° %s, %.5f° %s".format(Math.abs(lat), ns, Math.abs(lon), ew)
            }
            "Polygon" -> {
                val nb = (coords as? JSONArray)?.optJSONArray(0)?.length() ?: 0
                if (nb > 0) "Polygone ($nb sommets)" else "Polygone"
            }
            "MultiPolygon" -> "MultiPolygone"
            "LineString" -> {
                val nb = (coords as? JSONArray)?.length() ?: 0
                if (nb > 0) "Ligne ($nb points)" else "Ligne"
            }
            "MultiPoint" -> {
                val nb = (coords as? JSONArray)?.length() ?: 0
                if (nb > 0) "$nb points" else "MultiPoint"
            }
            else -> type
        }
    }

    /** GET /api/monitorings/config/<module_code> — récupère le schéma déclaratif du protocole
     *  (fichier objects.json côté serveur). Pour chaque object_type expose le label, le
     *  `name_field`, le `parent_type` et les `children`. Permet de driver l'affichage et la
     *  navigation au lieu d'utiliser des heuristiques. Null si l'endpoint échoue. */
    suspend fun chargerSchemaProtocole(config: GeoNatureConfig, moduleCode: String): Map<String, MonitoringSchemaObjet>? =
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
            val result = linkedMapOf<String, MonitoringSchemaObjet>()
            val it = obj.keys()
            while (it.hasNext()) {
                val type = it.next()
                val v = obj.optJSONObject(type) ?: continue
                val childrenArr = v.optJSONArray("children")
                val children = mutableListOf<String>()
                if (childrenArr != null) {
                    for (i in 0 until childrenArr.length()) {
                        val s = childrenArr.optString(i, "")
                        if (s.isNotEmpty()) children.add(s)
                    }
                }
                // gn_module_monitoring varie d'une version à l'autre : on essaie plusieurs noms
                // de clé pour le champ "nom".
                val nameField = v.optString("description_field_name", "")
                    .ifEmpty { v.optString("display_field_name", "") }
                    .ifEmpty { v.optString("name_field", "") }
                    .takeIf { it.isNotEmpty() }
                result[type] = MonitoringSchemaObjet(
                    type = type,
                    label = v.optString("label", "").takeIf { it.isNotEmpty() },
                    labelList = v.optString("label_list", "").takeIf { it.isNotEmpty() },
                    nameField = nameField,
                    parentType = v.optString("parent_type", "").takeIf { it.isNotEmpty() },
                    childrenTypes = children,
                )
            }
            result
        }
}