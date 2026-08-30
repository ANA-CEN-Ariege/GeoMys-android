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

import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.MonitoringCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
    /** Permissions CRUVED de l'utilisateur **courant** sur ce module, telles que retournées
     *  par `/api/monitorings/modules` (le bloc `cruved` est renseigné côté backend en fonction
     *  des droits de l'utilisateur authentifié). Map `"C"|"R"|"U"|"V"|"E"|"D" → niveau 0..3`,
     *  ou null pour les serveurs/responses qui n'exposent pas du tout le bloc (rétrocompat).
     *  Sémantique d'un niveau (GeoNature) : 0 = aucun droit, 1 = ses propres données,
     *  2 = données de son organisme, 3 = toutes les données. */
    val cruved: Map<String, Int>? = null,
) {
    /** True si l'utilisateur a au moins UN droit > 0 sur ce module (parité
     *  `_hasModulePermissions` de gn_mobile_monitoring). Sert à masquer les modules sur lesquels
     *  l'utilisateur n'a rien — typiquement filtrage de la liste des protocoles. Quand le bloc
     *  CRUVED est absent (vieux serveur, cache antérieur au filtrage), on considère le module
     *  visible : c'est le comportement historique de l'app, qu'on ne casse pas en silence. */
    val aAuMoinsUnDroit: Boolean
        get() = cruved == null || cruved.values.any { it > 0 }
}

/** Chargement et cache de la liste des modules (protocoles) de gn_module_monitoring :
 *  fetch réseau + fallback cache disque, filtrage CRUVED, et helpers de consultation du
 *  cache (labels, comptages, listes taxonomiques) sans appel réseau. Extrait de
 *  [MonitoringApi] (découpage du god-object) — la surface publique historique reste
 *  accessible via la façade [MonitoringApi] pour les fonctions très utilisées. */
object MonitoringModules {

    /** Cache mémoire de la dernière liste chargée. Permet à l'écran de détail de retrouver
     *  un protocole par son [MonitoringModule.moduleCode] sans repasser les ~10 champs via
     *  Bundle args. */
    @Volatile private var dernierChargement: List<MonitoringModule> = emptyList()

    /** Vide le cache mémoire process-wide de la liste de modules. Appelé par
     *  [MonitoringApi.invaliderCaches] quand l'URL/login/mdp serveur changent. */
    internal fun invaliderCacheMemoire() {
        dernierChargement = emptyList()
    }

    fun moduleParCode(moduleCode: String): MonitoringModule? =
        dernierChargement.firstOrNull { it.moduleCode == moduleCode }

    /** Compte les protocoles actuellement disponibles : taille de [dernierChargement] s'il
     *  est peuplé (cas où l'utilisateur a déjà navigué dans Suivis lors de cette session),
     *  sinon longueur du JSONArray du cache disque. Le cache disque est déjà filtré CRUVED
     *  (cf [chargerModules]) donc le nombre retourné correspond bien aux protocoles
     *  accessibles à l'utilisateur, pas à tout ce que le serveur expose. Pas d'appel réseau. */
    fun countModulesEnCache(): Int {
        if (dernierChargement.isNotEmpty()) return dernierChargement.size
        val json = MonitoringCache.getJson(MonitoringCache.keyModules()) ?: return 0
        return try {
            val arr = json.parserTableauJson("data", "items", "modules") ?: return 0
            arr.length()
        } catch (_: Exception) { 0 }
    }

    /** `id_list_taxonomy` (au niveau module) des protocoles présents dans le cache — déjà filtrés
     *  CRUVED par [chargerModules]. Sert au panneau « Détails » à compter les taxons rattachés à
     *  des listes de protocoles. Pas d'appel réseau (mémoire puis cache disque). */
    fun listesTaxonomieProtocolesEnCache(): Set<Int> {
        if (dernierChargement.isNotEmpty()) {
            return dernierChargement.mapNotNull { it.idListTaxonomy }.toSet()
        }
        val json = MonitoringCache.getJson(MonitoringCache.keyModules()) ?: return emptySet()
        return try {
            val arr = json.parserTableauJson("data", "items", "modules") ?: return emptySet()
            val ids = HashSet<Int>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optInt("id_list_taxonomy", -1)?.takeIf { it > 0 }?.let(ids::add)
            }
            ids
        } catch (_: Exception) { emptySet() }
    }

    /** Résout le `module_label` (libellé humain) d'un protocole à partir de son code, en
     *  cherchant d'abord la cache mémoire puis le cache disque modules. Retourne null si
     *  le code n'est pas dans le cache (cas d'un protocole jamais visité). Pas d'appel
     *  réseau. */
    fun labelModuleEnCache(moduleCode: String): String? {
        moduleParCode(moduleCode)?.moduleLabel?.takeIf { it.isNotEmpty() && it != moduleCode }
            ?.let { return it }
        val json = MonitoringCache.getJson(MonitoringCache.keyModules()) ?: return null
        return try {
            val arr = json.parserTableauJson("data", "items", "modules") ?: return null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("module_code") == moduleCode) {
                    return item.optString("module_label", "").takeIf { it.isNotEmpty() }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /** L'utilisateur peut-il CRÉER des données (visites/observations) sur ce protocole ?
     *
     *  Renvoie TOUJOURS true : le serveur reste seul juge (il rejette par 403 le cas échéant),
     *  exactement comme l'interface web GeoNature qui n'empêche pas la saisie côté client. On ne
     *  verrouille jamais l'envoi en silence.
     *
     *  Pourquoi ne PAS gater sur le CRUVED de `/api/monitorings/modules` ? Ce `cruved` ne reflète
     *  PAS le droit de créer une visite : il vaut `C:0` pour TOUS les protocoles, y compris pour
     *  un utilisateur qui crée sans problème des visites sur le web (constaté sur l'instance
     *  ANA-CEN : 13 modules, tous `C:0 / E:3 / R:3`). C'est le droit sur l'OBJET « module »
     *  lui-même (éditer la définition du protocole — réservé aux admins), pas sur ses DONNÉES.
     *  Gater dessus masquait à tort les flèches ➤ d'envoi ET le bouton « Tout envoyer » de « Mes
     *  visites » à des utilisateurs pourtant habilités. Occtax/OccHab s'appuient sur un autre
     *  signal (cruved de gn_commons/modules, lui fiable) et conservent donc leur gating.
     *
     *  [moduleCode] conservé pour l'API (et un éventuel gating futur basé sur le bon signal :
     *  cruved par type d'objet, absent de la liste des modules). */
    @Suppress("UNUSED_PARAMETER")
    fun moduleAutoriseCreation(moduleCode: String): Boolean = true

    /** GET /api/monitorings/modules — liste les modules de suivi disponibles sur l'instance.
     *  Renvoie [] silencieusement si HTTP 404 (gn_module_monitoring non installé).
     *  Sur toute autre erreur HTTP (5xx, parse), propage l'exception. Sur erreur **réseau**
     *  (IOException), retombe sur le cache local s'il est présent. */
    suspend fun chargerModules(config: GeoNatureConfig): List<MonitoringModule> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            // `depuisReseau` = true si le JSON vient d'un appel HTTP réussi (et non du cache
            // disque). On l'utilise plus bas pour décider de réécrire le cache (uniquement
            // sur un fetch frais — pas de réécriture quand on est en fallback offline).
            var depuisReseau = false
            val text = try {
                val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                if (auth == null) {
                    // Auth en échec (offline ou serveur injoignable) → fallback cache si présent.
                    MonitoringCache.getJson(MonitoringCache.keyModules()) ?: throw GNErreur.AuthEchouee(401)
                } else {
                    val (token, _, cookies) = auth
                    val url = URL("$base/api/monitorings/modules")
                    val conn = HttpClient.get(url, token, cookies, 10000)
                    val code = HttpClient.lireCode(conn)
                    // 404 → gn_module_monitoring non installé sur l'instance.
                    // 403 → utilisateur authentifié mais sans aucun droit CRUVED sur le
                    // monitoring (cas légitime côté terrain : c'est juste « aucun protocole
                    // accessible »). Dans les deux cas on renvoie une liste vide silencieuse
                    // — l'appelant (SuivisFragment, MonitoringSync, countModulesEnCache, …)
                    // gère ce zéro naturellement, plus de bandeau "étape en échec" parasite.
                    if (code == 404 || code == 403) return@withContext emptyList()
                    if (code != 200) { conn.disconnect(); throw GNErreur.EnvoiEchoue(code, "Modules monitoring : HTTP $code") }
                    depuisReseau = true
                    conn.inputStream.bufferedReader().readText()
                    // ⚠ on NE met PAS le brut en cache ici : la réponse serveur inclut tous
                    // les modules de l'instance avec un bloc CRUVED personnalisé. Si on cache
                    // le brut, l'offline ultérieur expose des protocoles auxquels l'utilisateur
                    // n'a pas droit (et que la re-lecture filtre quand même, mais on garde
                    // alors en clair les permissions inutiles sur disque). On écrit le cache
                    // après filtrage, plus bas.
                }
            } catch (e: IOException) {
                MonitoringCache.getJson(MonitoringCache.keyModules()) ?: throw e
            }
            val array: JSONArray = text.parserTableauJson("data", "items", "modules")
                ?: throw GNErreur.EnvoiEchoue(0, "Modules monitoring : format JSON inattendu")
            // Parsing en deux temps pour pouvoir filtrer CRUVED ET ré-écrire le cache disque
            // avec uniquement les items accessibles. On conserve le JSONObject brut de chaque
            // module à côté du modèle Kotlin, ainsi on n'a pas à re-sérialiser depuis le data
            // class (on garderait moins de champs que le serveur n'en envoie).
            val parsed = mutableListOf<Pair<MonitoringModule, JSONObject>>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val idModule = item.optInt("id_module", -1).takeIf { it > 0 } ?: continue
                val moduleCode = item.optString("module_code", "").ifEmpty { continue }
                val label = item.optString("module_label", moduleCode).ifEmpty { moduleCode }
                val desc = item.optString("module_desc", "").takeIf { it.isNotEmpty() }
                val m = MonitoringModule(
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
                    cruved = parserCruved(item.optJSONObject("cruved")),
                )
                parsed.add(m to item)
            }
            // Note : `active_frontend: false` est fréquent sur les protocoles ariégeois — ça
            // signifie "pas dans le menu web GeoNature" et non "désactivé". Ne pas filtrer
            // dessus sinon on cache tous les protocoles légitimes.
            //
            // Filtrage CRUVED : on n'expose à l'utilisateur que les modules sur lesquels il a
            // au moins UN droit > 0 (parité gn_mobile_monitoring). Le serveur renvoie déjà le
            // bloc personnalisé par utilisateur dans la réponse — il suffit de l'appliquer.
            // Les modules avec `cruved` absent (vieux serveurs, ou cache antérieur à cette
            // version) sont préservés via [MonitoringModule.aAuMoinsUnDroit] pour ne pas
            // disparaître silencieusement.
            val total = parsed.size
            val filtres = parsed.filter { (m, _) -> m.aAuMoinsUnDroit }
            if (filtres.size < total) {
                android.util.Log.i(
                    "MonitoringApi",
                    // Pas de login dans le message : les logs de prod ne doivent pas exposer
                    // d'identifiant utilisateur.
                    "chargerModules : ${total - filtres.size} module(s) masqué(s) par CRUVED " +
                        "(droits nuls pour l'utilisateur), ${filtres.size} conservé(s)",
                )
            }
            // Réécrit le cache disque avec uniquement les items accessibles, dans le même
            // format que le serveur (JSONArray d'items bruts). Préserve donc tous les champs
            // serveur sans avoir à les re-sérialiser depuis MonitoringModule. Pas de
            // réécriture quand on lit déjà depuis le cache (déjà filtré au tour précédent).
            if (depuisReseau) {
                val arr = JSONArray()
                filtres.forEach { (_, raw) -> arr.put(raw) }
                MonitoringCache.setJson(MonitoringCache.keyModules(), arr.toString())
            }
            filtres.map { (m, _) -> m }
                .sortedBy { it.moduleLabel.lowercase() }
                .also { dernierChargement = it }
        }

    /** Parse le bloc `cruved` d'un module monitoring en Map<lettre, niveau>. Le serveur
     *  envoie typiquement `{"C": 1, "R": 3, "U": 1, "V": 0, "E": 0, "D": 0}`. On accepte
     *  Number ou String numérique ; on normalise la clé en MAJUSCULE pour éviter les surprises
     *  selon les versions de backend. Retourne null si le bloc est absent ou non-objet (cas
     *  rétrocompat où on garde le module visible). */
    internal fun parserCruved(obj: JSONObject?): Map<String, Int>? {
        if (obj == null) return null
        val map = mutableMapOf<String, Int>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val niveau = when (val v = obj.opt(k)) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            } ?: continue
            map[k.uppercase()] = niveau
        }
        return if (map.isEmpty()) null else map
    }
}
