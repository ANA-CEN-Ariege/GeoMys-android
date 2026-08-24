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

import fr.ariegenature.geomys.network.MonitoringApi.LabelResolver
import fr.ariegenature.geomys.network.MonitoringApi.MonitoringPropertySchema
import fr.ariegenature.geomys.network.MonitoringApi.MonitoringSchemaObjet
import fr.ariegenature.geomys.network.MonitoringApi.OptionDatalist
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.MonitoringCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL

/** Options dynamiques des formulaires monitoring : fetch des datalists/nomenclatures/datasets
 *  (+ write-through cache offline), listes d'observateurs UsersHub, et construction du
 *  [LabelResolver] qui traduit les IDs (id_role, id_nomenclature, id_dataset) en libellés
 *  humains. Extrait de [MonitoringApi] (découpage du god-object) —
 *  [MonitoringApi.chargerOptionsDatalist] reste la façade appelée par les écrans. */
object MonitoringDatalists {

    /** Cache du LabelResolver par moduleCode. Évite de re-fetcher les nomenclatures / users /
     *  datasets entre fiches d'un même protocole. Invalidé en même temps que [dernierSchema]
     *  ne le serait — ici on garde indéfiniment dans la session (les nomenclatures changent
     *  rarement). */
    @Volatile private var cacheResolvers: MutableMap<String, LabelResolver> = mutableMapOf()

    /** Vide le cache mémoire process-wide des LabelResolver par module. Appelé par
     *  [MonitoringApi.invaliderCaches] quand l'URL/login/mdp serveur changent. */
    internal fun invaliderCacheMemoire() {
        cacheResolvers.clear()
    }

    /** Construit le LabelResolver pour un protocole : scan du schéma pour identifier les types
     *  d'IDs à résoudre (`type_util` ∈ user/nomenclature/dataset), fetch parallèle des listes
     *  correspondantes, retourne un resolver utilisable côté UI. Met en cache par moduleCode.
     *  Renvoie un resolver vide (mais non-null) en cas d'échec partiel/total — chaque ID non
     *  résolu reste alors affiché tel quel. */
    suspend fun chargerResolveurLabels(
        config: GeoNatureConfig,
        moduleCode: String,
        schema: Map<String, MonitoringSchemaObjet>,
    ): LabelResolver = withContext(Dispatchers.IO) {
        cacheResolvers[moduleCode]?.let { return@withContext it }
        // Inventaire des ressources à fetcher.
        val codesNomenclature = mutableSetOf<String>()
        var besoinUsers = false
        var besoinDatasets = false
        schema.values.forEach { obj ->
            obj.properties.values.forEach { prop ->
                when (prop.typeUtil) {
                    "nomenclature" -> prop.nomenclatureType?.let { codesNomenclature.add(it) }
                    "user" -> besoinUsers = true
                    "dataset" -> besoinDatasets = true
                }
            }
        }
        val idListObserver = schema["module"]?.idListObserver
        val base = config.urlServeur.trim().trimEnd('/')
        val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
        // En offline (auth KO), on peut quand même servir les observateurs via le cache
        // disque écrit par le sync. On ne retourne plus immédiatement un resolver vide :
        // on tente le fallback observateurs avant d'abandonner.
        val token = auth?.first
        val cookies = auth?.third.orEmpty()

        suspend fun fetchListe(path: String, keyId: String, keyLabel: String, dataPath: String?): Map<String, String> {
            val url = URL("$base/api/$path")
            val conn = HttpClient.get(url, token, cookies, 15000)
            if (conn.responseCode != 200) return emptyMap()
            val text = conn.inputStream.bufferedReader().readText()
            val clesArray = (listOfNotNull(dataPath) + listOf("values", "data", "items", "results")).toTypedArray()
            val array: JSONArray = text.parserTableauJson(*clesArray) ?: return emptyMap()
            val map = mutableMapOf<String, String>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.opt(keyId)?.toString() ?: continue
                val lbl = item.opt(keyLabel)?.toString() ?: continue
                if (id.isNotEmpty() && lbl.isNotEmpty()) map[id] = lbl
            }
            return map
        }

        coroutineScope {
            // Nomenclatures et datasets : pas de fallback cache disque pour l'instant, donc
            // si auth a échoué (offline), on les skippe pour ne pas timeout 15s × N requêtes.
            val nomenclaturesDeferred = if (auth == null) emptyList() else codesNomenclature.map { code ->
                async { code to fetchListe("nomenclatures/nomenclature/$code", "id_nomenclature", "label_fr", "values") }
            }
            // Observateurs : on délègue à chargerObservateursDeListe qui gère le fallback
            // cache disque automatiquement — utile en offline strict.
            val usersDeferred = if (besoinUsers && idListObserver != null) async {
                val arr = chargerObservateursDeListe(config, idListObserver) ?: return@async emptyMap()
                val map = mutableMapOf<String, String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.opt("id_role")?.toString() ?: continue
                    val lbl = item.opt("nom_complet")?.toString() ?: continue
                    if (id.isNotEmpty() && lbl.isNotEmpty()) map[id] = lbl
                }
                map.toMap()
            } else null
            val datasetsDeferred = if (besoinDatasets && auth != null) async {
                fetchListe("meta/datasets", "id_dataset", "dataset_name", null)
            } else null

            val nomenclaturesMap = nomenclaturesDeferred.associate { it.await() }
            val usersMap = usersDeferred?.await() ?: emptyMap()
            val datasetsMap = datasetsDeferred?.await() ?: emptyMap()
            val resolver = LabelResolver(
                nomenclatures = nomenclaturesMap,
                users = usersMap,
                datasets = datasetsMap,
            )
            cacheResolvers[moduleCode] = resolver
            resolver
        }
    }

    /** Charge la liste d'observateurs d'une UsersHub `id_liste` : fetch live + persistance
     *  dans le cache `MonitoringCache.keyObservateurs`, ou fallback sur le cache disque si
     *  pas de réseau / auth en échec. Retourne null si vraiment rien ne marche. */
    suspend fun chargerObservateursDeListe(
        config: GeoNatureConfig,
        idListe: Int,
    ): JSONArray? = withContext(Dispatchers.IO) {
        if (idListe <= 0) return@withContext null
        val key = MonitoringCache.keyObservateurs(idListe)
        val base = config.urlServeur.trim().trimEnd('/')
        val auth = runCatching { GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse) }.getOrNull()
        if (auth != null) {
            val (token, _, cookies) = auth
            val res = runCatching {
                val conn = HttpClient.get(URL("$base/api/users/menu/$idListe"), token, cookies, 15000)
                if (conn.responseCode != 200) null
                else conn.inputStream.bufferedReader().readText()
            }.getOrNull()
            if (res != null) {
                MonitoringCache.setJson(key, res)
                return@withContext runCatching { JSONArray(res) }.getOrNull()
            }
        }
        // Fallback cache disque pour usage offline.
        MonitoringCache.getJson(key)?.let { runCatching { JSONArray(it) }.getOrNull() }
    }

    /** Fetch dynamiquement les options d'un widget `datalist` ou `nomenclature` (forme ancienne).
     *  Construit l'URL `<base>/api/<api>`, parse selon `dataPath` (si fourni) → array d'objets,
     *  puis projette chaque entrée sur (keyValue, keyLabel). Renvoie null sur erreur HTTP/auth/parse.
     *  Application "TaxHub" non supportée pour l'instant — renvoie null avec log. */
    suspend fun chargerOptionsDatalist(
        config: GeoNatureConfig,
        prop: MonitoringPropertySchema,
    ): List<OptionDatalist>? = withContext(Dispatchers.IO) {
        val apiPath = prop.apiUrl ?: return@withContext null
        val keyLabel = prop.keyLabel ?: return@withContext null
        val keyValue = prop.keyValue ?: "id"
        if (prop.application != null && prop.application != "GeoNature") {
            return@withContext null // TaxHub à supporter plus tard
        }
        // Cas spécial observateurs : on passe par le helper qui gère le cache disque
        // (fetch live → cache → réutilisable en offline). Détection par préfixe d'URL.
        val matchObs = Regex("""users/menu/(\d+)""").find(apiPath)
        if (matchObs != null) {
            val idListe = matchObs.groupValues[1].toIntOrNull() ?: return@withContext null
            val arr = chargerObservateursDeListe(config, idListe) ?: return@withContext null
            return@withContext extraireOptions(arr, keyValue, keyLabel, prop.filtres)
        }
        // Cas spécial dataset : la source autoritative est l'endpoint serveur
        // /api/meta/datasets, qui applique côté backend (a) le filtre `create=<module>.<code_object>`
        // = CRUVED action=C scope du user authentifié (cf. TDatasets.filter_by_creatable +
        // filter_by_scope sur cor_dataset_actor — un user "MES données" scope=1 ne voit
        // que les datasets dont il est acteur), (b) le filtre `active=true`, (c) le filtre
        // `module_code`. Le cache local datasetsCacheJson n'est PAS une source fiable :
        // peuplé via /meta/datasets?fields=modules sans `create`, il contient TOUS les
        // datasets rattachés au module, sans filtre CRUVED. On le réservait donc à un
        // fallback offline.
        // L'apiUrl reçue par chargerOptionsDatalist a déjà été enrichie par derirverApiSiManquant
        // (active=true + create=<creatable_in_module> + module_code=<code>), donc le fetch
        // live ci-dessous renvoie exactement la liste que le web propose. On ne court-circuite
        // PAS avec le cache local.
        // Fallback offline : si le fetch live échoue, on retombe en bas sur le cache filtré
        // par moduleCodes — meilleure approximation hors-ligne, à défaut de mieux.
        // Helper local pour le fallback dataset offline. Renvoie null si on n'est pas sur
        // le cas dataset ou si le cache ne donne rien d'exploitable.
        fun fallbackDatasetCache(): List<OptionDatalist>? {
            if (!apiPath.startsWith("meta/datasets") &&
                !prop.typeWidget.equals("dataset", ignoreCase = true)
            ) return null
            val moduleCodeFiltre = Regex("""module_code=([^&]+)""").find(apiPath)?.groupValues?.get(1)
                ?: prop.moduleCodeFiltre ?: return null
            val cache = config.datasetsCacheJson.takeIf { it.isNotEmpty() } ?: return null
            val datasets: List<GeoNatureDataset> = runCatching {
                val t = object : com.google.gson.reflect.TypeToken<List<GeoNatureDataset>>() {}.type
                com.google.gson.Gson().fromJson<List<GeoNatureDataset>>(cache, t)
            }.getOrNull().orEmpty()
            val associes = datasets.filter { moduleCodeFiltre in it.moduleCodes }
            if (associes.isEmpty()) return null
            return associes
                .map { OptionDatalist(it.id.toString(), it.nom) }
                .sortedBy { it.label.lowercase() }
        }
        // Clé + relecture du cache d'options (offline). Définis AVANT le login pour servir aussi le
        // cas « pas de réseau » (login null) : sans ça, une liste déroulante de nomenclature est
        // vide hors-ligne et bloque la saisie d'une nouvelle visite.
        val clesArray = (listOfNotNull(prop.dataPath) + listOf("values", "data", "items", "results")).toTypedArray()
        val cleCache = MonitoringCache.keyOptionsDatalist(apiPath)
        fun cacheOffline(): List<OptionDatalist>? =
            MonitoringCache.getJson(cleCache)?.let { txt ->
                runCatching { txt.parserTableauJson(*clesArray) }.getOrNull()
                    ?.let { extraireOptions(it, keyValue, keyLabel, prop.filtres) }
                    ?.takeIf { it.isNotEmpty() }
            }
        val base = config.urlServeur.trim().trimEnd('/')
        // Hors-ligne (login impossible) : cache datalist EXACT d'abord — le cache datasets
        // d'Occtax n'est pas filtré CRUVED, il ne sert qu'en dernier ressort.
        val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
            ?: return@withContext cacheOffline() ?: fallbackDatasetCache()
        val estDataset = apiPath.startsWith("meta/datasets") ||
            prop.typeWidget.equals("dataset", ignoreCase = true)
        // Pour le widget `dataset`, on doit obligatoirement POST avec body JSON : le backend
        // n'applique `filter_by_params` (= filtre `active`, `module_code` etc.) QUE quand
        // `request.is_json` est True. En GET avec query string, le `create=…` filtre via
        // CRUVED mais `active=true` est ignoré — on récupère alors les datasets inactifs en
        // plus, et la liste est plus grosse que ce que propose la version web.
        // Cf. backend/geonature/core/gn_meta/routes.py::get_datasets.
        val (urlFinale, postBody) = if (estDataset) {
            // Sépare la query string : on garde l'éventuel `orderby` côté URL (comme le web),
            // et on transforme `module_code`, `active`, `create`, … en JSON body.
            val (chemin, query) = apiPath.split('?', limit = 2).let {
                it[0] to (it.getOrNull(1).orEmpty())
            }
            val paramsBody = JSONObject()
            val querysUrl = mutableListOf<String>()
            query.split('&').filter { it.isNotEmpty() }.forEach { kv ->
                val (k, vRaw) = kv.split('=', limit = 2).let {
                    it[0] to (it.getOrNull(1).orEmpty())
                }
                val v = java.net.URLDecoder.decode(vRaw, "UTF-8")
                when {
                    k == "orderby" -> querysUrl.add("$k=$vRaw")
                    k == "active" -> paramsBody.put(k, v.equals("true", ignoreCase = true))
                    else -> paramsBody.put(k, v)
                }
            }
            val urlStr = "$base/api/$chemin" + if (querysUrl.isNotEmpty()) "?${querysUrl.joinToString("&")}" else ""
            URL(urlStr) to paramsBody.toString()
        } else {
            URL("$base/api/$apiPath") to null
        }
        // Tentatives successives pour le dataset : body complet, puis SANS le filtre `create`
        // (CRUVED action C). Sur certaines instances, `create=<module>.<objet>` renvoie vide ou
        // échoue (objet de permission absent de la config du protocole) alors que des jeux de
        // données sont bien rattachés au module — le web ne s'en aperçoit pas car il masque le
        // champ à dataset unique sans appeler cette route. Champ requis vide = saisie bloquée,
        // on préfère élargir au module (parité avec ce que le protocole affiche en tête).
        val bodies: List<String?> = if (postBody != null && postBody.contains("\"create\"")) {
            val sansCreate = JSONObject(postBody).also { it.remove("create") }.toString()
            listOf(postBody, sansCreate)
        } else listOf(postBody)
        for ((index, body) in bodies.withIndex()) {
            val conn = HttpClient.get(urlFinale, token, cookies, 15000)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                // IOException ici = réseau tombé APRÈS un login servi par le cache d'auth
                // (fenêtre TTL 5 min) : non rattrapée, elle remontait par l'async
                // d'enrichirAvecOptions jusqu'au launch de NouvelleVisiteFragment → crash à
                // l'ouverture du formulaire (audit 2026-08-23). On dégrade vers la tentative
                // suivante puis le repli cache, comme les autres échecs réseau de la boucle.
                try {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                } catch (_: IOException) {
                    continue
                }
            }
            val httpCode = try { conn.responseCode } catch (_: IOException) { -1 }
            if (httpCode != 200) continue
            val text = try { conn.inputStream.bufferedReader().readText() } catch (_: IOException) { continue }
            // Réponse soit array direct, soit objet contenant data_path → array.
            val array: JSONArray = text.parserTableauJson(*clesArray) ?: continue
            val options = extraireOptions(array, keyValue, keyLabel, prop.filtres)
            // Dataset vide avec une tentative plus permissive encore disponible → on la joue.
            if (estDataset && options.isEmpty() && index < bodies.size - 1) {
                android.util.Log.w("MonitoringApi",
                    "Datalist dataset vide avec filtre create — nouvelle tentative sans create")
                continue
            }
            if (options.isEmpty()) {
                // 200 valide mais 0 option — deux cas distincts (audit 2026-08-23) :
                // - dataset : quirk connu (filtre create/objet de permission), déjà retenté
                //   sans create ci-dessus → repli caches, en préférant le cache datalist
                //   EXACT (write-through, filtré CRUVED) au cache datasets d'Occtax (non
                //   filtré — l'utilisateur y verrait des JDD où il ne peut pas créer) ;
                // - autre datalist (nomenclatures…) : la réponse vide FAIT FOI — resservir
                //   un cache périmé re-proposerait indéfiniment des valeurs retirées côté
                //   serveur (options fantômes). On mémorise le vide et on le retourne.
                if (estDataset) return@withContext cacheOffline() ?: fallbackDatasetCache() ?: options
                MonitoringCache.setJson(cleCache, text)
                return@withContext options
            }
            // Write-through : mémorise la réponse brute pour l'usage OFFLINE (nouvelle visite).
            MonitoringCache.setJson(cleCache, text)
            return@withContext options
        }
        // Échec réseau/HTTP sur toutes les tentatives : cache datalist exact d'abord,
        // cache datasets grossier en dernier ressort (datasets seulement).
        cacheOffline() ?: fallbackDatasetCache()
    }

    /** Factorise la conversion d'un JSONArray d'objets en `List<OptionDatalist>` triée par
     *  label, avec application des filtres déclarés au schéma (ex: stade biologique
     *  restreint à ["Inconnu", "Chrysalide", …]). */
    private fun extraireOptions(
        array: JSONArray,
        keyValue: String,
        keyLabel: String,
        filtres: Map<String, List<String>>,
    ): List<OptionDatalist> {
        val opts = mutableListOf<OptionDatalist>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val v = item.opt(keyValue)?.toString().orEmpty()
            val l = item.opt(keyLabel)?.toString().orEmpty()
            if (v.isEmpty() || l.isEmpty()) continue
            val cdNom = item.opt("cd_nomenclature")?.toString()?.takeIf { it.isNotEmpty() }
            val lblDef = item.opt("label_default")?.toString()?.takeIf { it.isNotEmpty() }
            opts.add(OptionDatalist(v, l, cdNom, lblDef))
        }
        val filtrees = if (filtres.isEmpty()) opts else opts.filter { o ->
            filtres.all { (champ, valeursAcceptables) ->
                val v = when (champ) {
                    "label_default" -> o.labelDefaut
                    "cd_nomenclature" -> o.cdNomenclature
                    else -> null
                }
                v == null || v in valeursAcceptables
            }
        }
        return filtrees.sortedBy { it.label.lowercase() }
    }
}
