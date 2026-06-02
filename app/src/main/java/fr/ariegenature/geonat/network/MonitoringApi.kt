/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat.network

import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.MonitoringCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

object MonitoringApi {

    /** Cache mémoire de la dernière liste chargée. Permet à l'écran de détail de retrouver
     *  un protocole par son [moduleCode] sans repasser les ~10 champs via Bundle args. */
    @Volatile private var dernierChargement: List<MonitoringModule> = emptyList()

    /** Vide les caches mémoire process-wide (liste de modules + LabelResolver par module).
     *  À appeler quand l'URL/login/mdp serveur changent — sinon on continue à servir des
     *  modules et des résolutions de nomenclatures du serveur précédent. Le cache disque
     *  ([MonitoringCache]) n'est pas touché : il est versionné par module_code et reste
     *  valide tant que l'utilisateur ne change pas de serveur. */
    fun invaliderCaches() {
        dernierChargement = emptyList()
        cacheResolvers.clear()
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
            val arr = runCatching { JSONArray(json) }.getOrNull()
                ?: JSONObject(json).let { o ->
                    o.optJSONArray("data") ?: o.optJSONArray("items") ?: o.optJSONArray("modules")
                } ?: return 0
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
            val arr = runCatching { JSONArray(json) }.getOrNull()
                ?: JSONObject(json).let { o ->
                    o.optJSONArray("data") ?: o.optJSONArray("items") ?: o.optJSONArray("modules")
                } ?: return emptySet()
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
            val arr = runCatching { JSONArray(json) }.getOrNull()
                ?: JSONObject(json).let { o ->
                    o.optJSONArray("data") ?: o.optJSONArray("items") ?: o.optJSONArray("modules")
                } ?: return null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("module_code") == moduleCode) {
                    return item.optString("module_label", "").takeIf { it.isNotEmpty() }
                }
            }
            null
        } catch (_: Exception) { null }
    }

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
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("Accept", "application/json")
                    if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                    if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                    val code = conn.responseCode
                    // 404 → gn_module_monitoring non installé sur l'instance.
                    // 403 → utilisateur authentifié mais sans aucun droit CRUVED sur le
                    // monitoring (cas légitime côté terrain : c'est juste « aucun protocole
                    // accessible »). Dans les deux cas on renvoie une liste vide silencieuse
                    // — l'appelant (SuivisFragment, MonitoringSync, countModulesEnCache, …)
                    // gère ce zéro naturellement, plus de bandeau "étape en échec" parasite.
                    if (code == 404 || code == 403) return@withContext emptyList()
                    if (code != 200) throw GNErreur.EnvoiEchoue(code, "Modules monitoring : HTTP $code")
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
            val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                val obj = JSONObject(text)
                obj.optJSONArray("data") ?: obj.optJSONArray("items") ?: obj.optJSONArray("modules")
                    ?: throw GNErreur.EnvoiEchoue(0, "Modules monitoring : format JSON inattendu")
            }
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
                    "chargerModules : ${total - filtres.size} module(s) masqué(s) par CRUVED " +
                        "(droits nuls pour ${config.login}), ${filtres.size} conservé(s)",
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

    /** Un enfant (site, sites_group, …) d'un module monitoring : id technique + nom "best-effort"
     *  extrait à la volée + map plate des propriétés strings (pour ré-extraire le nom côté UI
     *  une fois le schéma chargé) + GeoJSON brut de sa géométrie quand l'API la renvoie au
     *  niveau enfant (utile pour superposer tous les enfants sur la carte d'un site). */
    data class MonitoringEnfant(
        val id: Int,
        val nom: String,
        val proprietes: Map<String, String>,
        val geometrieGeoJson: String? = null,
    )

    /** Schéma d'une propriété d'un object_type (un champ saisissable). Vient des blocs
     *  `generic`/`specific` côté serveur. */
    data class MonitoringPropertySchema(
        val nom: String,
        /** `text`, `textarea`, `date`, `time`, `number`, `select`, `radio`, `datalist`,
         *  `nomenclature`, `observers`, `dataset`, `medias`, `taxonomy`, `bool_checkbox`, … */
        val typeWidget: String,
        val label: String,
        val obligatoire: Boolean,
        /** Discriminateur sémantique : `user`, `nomenclature`, `dataset`, `taxonomy`, `types_site`.
         *  Utilisé pour résoudre les IDs en labels via [LabelResolver]. */
        val typeUtil: String? = null,
        /** Pour widget=`nomenclature` (forme ancienne) : code mnémonique du type de nomenclature. */
        val nomenclatureType: String? = null,
        /** Valeurs prédéfinies pour `select`/`radio` (value → label). Vide pour les datalists
         *  qui sont alimentés dynamiquement par appel API. */
        val valeurs: List<Pair<String, String>> = emptyList(),
        /** Pour widget=`datalist` : champs nécessaires au fetch des options. */
        val apiUrl: String? = null,
        val application: String? = null,
        val keyLabel: String? = null,
        val keyValue: String? = null,
        val dataPath: String? = null,
        val multiple: Boolean = false,
        /** Valeur par défaut scalaire (text/number/date) — `value` ou `default` simple. */
        val defaultValue: String? = null,
        /** Valeur par défaut sous forme objet pour nomenclature : `{cd_nomenclature: "18"}` ou
         *  `{label_default: "Imago"}`. Résolu en `id_nomenclature` côté UI via les options. */
        val defaultObjet: Map<String, String> = emptyMap(),
        /** Filtres déclarés pour restreindre les options renvoyées (datalist/nomenclature).
         *  Format : Map<champ_filtre → liste de valeurs acceptables>. Ex chronoventaire stade :
         *  `{"label_default": ["Inconnu", "Chrysalide", "Imago", "Chenille", "Œuf"]}`. */
        val filtres: Map<String, List<String>> = emptyMap(),
        /** Texte d'aide / tooltip déclaré dans le schéma (`definition`). Affiché sous le label
         *  dans le formulaire de saisie pour expliquer ce qu'on attend. */
        val definition: String? = null,
        /** Sur les propriétés `dataset` : code du module pour filtrer les jeux de données
         *  proposés au seul module pertinent. Sans ce filtre, on liste TOUS les datasets du
         *  serveur. */
        val moduleCodeFiltre: String? = null,
        /** Expression d'affichage conditionnel (clé `hidden` ou `display` du schéma serveur,
         *  format string interpolée Angular type `${champ_x}` ou `${champ_x} === 'val'`).
         *  Évaluée à la volée par le renderer pour masquer/afficher dynamiquement le champ
         *  en fonction des autres valeurs. Null = toujours visible. */
        val hiddenExpr: String? = null,
        /** `hidden: true` côté schéma serveur : champ technique caché à l'UI (id_base_visit,
         *  id_module, medias, nb_observations…). Inclus dans le payload POST avec valeur
         *  null sinon le serveur Marshmallow plante. */
        val hiddenBool: Boolean = false,
        /** Liste taxonomique propre à CE champ (lue depuis `id_list` ou l'api
         *  `taxref/allnamebylist/<id>`). Prime sur l'`idListTaxonomy` du module pour
         *  restreindre l'autocomplete TaxRef d'un champ taxonomie. */
        val idListTaxonomie: Int? = null,
        /** Borne minimale pour un champ numérique. Brut serveur car peut être un littéral
         *  (`"min": 0`) ou une expression `(value) => value.<autre_champ>` qui pointe vers
         *  un autre champ du formulaire. Résolu à la volée par [fr.ariegenature.geonat
         *  .monitoring.form.ValidationExpr] au moment de la validation. */
        val minValue: String? = null,
        /** Borne maximale (cf. [minValue]). Couple typique côté monitoring :
         *  `count_min` avec `max: "(value) => value.count_max"` pour forcer min ≤ max. */
        val maxValue: String? = null,
        /** Vrai si le champ a été déclaré dans le bloc `specific` de l'object_type côté
         *  schéma serveur (par opposition au bloc `generic` du modèle de base). Parité avec
         *  `isInSpecific` de gn_mobile_monitoring : un champ specific est **toujours inclus**
         *  dans le formulaire de saisie, même s'il n'est pas listé dans `display_form` /
         *  `display_properties`. C'est typiquement comme ça que les protocoles ajoutent leurs
         *  champs custom sans avoir à redéclarer tout le display_form. */
        val enSpecific: Boolean = false,
        /** Pour les widgets `medias` : table Postgres à laquelle le média est rattaché côté
         *  gn_commons (champ `schema_dot_table` du schéma serveur, ex.
         *  `gn_monitoring.t_base_visits`). Sert à résoudre l'id_table_location lors de
         *  l'upload du fichier via /api/gn_commons/media. */
        val schemaDotTable: String? = null,
        /** Pour le widget `dataset` du champ id_dataset : valeur `creatable_in_module` du
         *  schéma, format `"<module_code>.<code_object>"` (ex. `apollons.MONITORINGS_VISITES`
         *  pour le picker dataset d'une visite). Passée au backend via `?create=<value>`
         *  pour ne lister que les jeux où l'utilisateur connecté a le droit CRUVED `C` sur
         *  cet object — parité formulaire web monitoring. */
        val creatableInModule: String? = null,
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
        /** Schéma des propriétés saisissables, indexé par nom. Vide si le protocole ne déclare
         *  pas de schéma de saisie (vieux protocole, ou type qui n'est qu'un container). */
        val properties: Map<String, MonitoringPropertySchema> = emptyMap(),
        /** Ordre d'affichage des propriétés dans une fiche ou un formulaire. Si vide, on affiche
         *  selon l'ordre d'insertion JSON de `properties`. */
        val displayProperties: List<String> = emptyList(),
        /** `display_form` : sous-ensemble + ordre des champs spécifiquement pour le FORMULAIRE
         *  de saisie. Prime sur `displayProperties` quand non vide (parité version web /
         *  gn_mobile_monitoring : display_form > display_properties). */
        val displayForm: List<String> = emptyList(),
        /** Règles `change` (tableau de lignes JS `({objForm, meta}) => { … patchValue({…}) }`)
         *  déclarées au niveau de l'object_type pour auto-remplir des champs dépendants.
         *  Brut — l'évaluation est faite par [fr.ariegenature.geonat.monitoring.form.ChangeRules]. */
        val changeRules: List<String> = emptyList(),
        /** Liste ordonnée des propriétés à afficher dans la vue LISTE de ce type (sous le nom).
         *  Ex chronoventaire site : `["base_site_name", "first_use_date", "last_visit", "nb_visits"]`.
         *  Séparé de `displayProperties` (qui est pour la fiche). */
        val displayList: List<String> = emptyList(),
        /** Critères de tri par défaut pour la liste de ce type. List<(prop, "asc"|"desc")>. */
        val sorts: List<Pair<String, String>> = emptyList(),
        /** Nom du champ qui sert d'identifiant technique pour ce type (ex: `id_base_site`,
         *  `id_dalle`, `id_sites_group`). Utilisé côté UI pour masquer le champ de sélection
         *  du parent dans le formulaire de création d'un enfant (le parent est connu par
         *  contexte de navigation). */
        val idFieldName: String? = null,
        /** Pour le type "module" uniquement : id de la liste d'observateurs déclarée. Sert à
         *  fetcher /api/users/menu/<id> pour résoudre les `id_role` en noms. */
        val idListObserver: Int? = null,
        /** Pour le type "module" uniquement : id de la liste taxonomique. */
        val idListTaxonomy: Int? = null,
        /** Type de géométrie déclaré par le schéma (`Point`, `Polygon`, `LineString`,
         *  `MultiPolygon`, …). Null si le type n'a pas de géométrie associée — dans ce cas
         *  le bouton "voir sur carte" est inutile et masqué. */
        val geometryType: String? = null,
        /** Nom du champ uuid de cet object_type (ex. `uuid_base_visit` pour les visites).
         *  Sert à pré-générer un uuid côté client et l'injecter dans le payload POST de
         *  création, pour pouvoir ensuite rattacher les médias gn_commons à cet uuid via
         *  `uuid_attached_row` sans avoir à reparser la réponse serveur. */
        val uuidFieldName: String? = null,
        /** Genre grammatical du label (`M` / `F`), tel que déclaré par le protocole.
         *  Utilisé côté UI pour formuler proprement « Édition de la visite » vs « Édition
         *  du passage » vs « Édition de l'observation ». Null = inconnu (heuristique
         *  voyelle initiale uniquement). */
        val genre: String? = null,
    )

    /** Cache des labels résolus depuis le serveur — permet de remplacer les IDs (id_role,
     *  id_nomenclature, id_dataset) par leurs labels au moment de l'affichage. */
    data class LabelResolver(
        /** code_nomenclature_type → (id_nomenclature → label_fr). */
        val nomenclatures: Map<String, Map<String, String>> = emptyMap(),
        /** id_role → nom_complet. */
        val users: Map<String, String> = emptyMap(),
        /** id_dataset → dataset_name. */
        val datasets: Map<String, String> = emptyMap(),
    ) {
        /** Résout l'ID d'une propriété en label si une correspondance existe. Retourne null si
         *  le type_util n'est pas géré ou si l'ID n'est pas trouvé. */
        fun resoudre(prop: MonitoringPropertySchema, valeur: String): String? {
            if (valeur.isEmpty() || valeur == "null") return null
            return when (prop.typeUtil) {
                "user" -> users[valeur]
                "nomenclature" -> prop.nomenclatureType?.let { nomenclatures[it]?.get(valeur) }
                "dataset" -> datasets[valeur]
                else -> null
            }
        }
    }

    /** GET /api/monitorings/object/<module_code>/module?depth=1 — récupère le module et la liste
     *  de ses enfants directs avec leurs propriétés brutes. Map `object_type → [enfants]`, map
     *  vide si aucun enfant. **Throw** [GNErreur.AuthEchouee] si l'auth tombe, et
     *  [GNErreur.EnvoiEchoue] avec le code HTTP sur 403/404/500 — l'appelant peut humaniser via
     *  [humaniserErreurReseau]. */
    suspend fun chargerEnfants(config: GeoNatureConfig, moduleCode: String): Map<String, List<MonitoringEnfant>> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val text = try {
                val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                if (auth == null) {
                    MonitoringCache.getJson(MonitoringCache.keyEnfants(moduleCode)) ?: throw GNErreur.AuthEchouee(401)
                } else {
                    val (token, _, cookies) = auth
                    val url = URL("$base/api/monitorings/object/$moduleCode/module?depth=1")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("Accept", "application/json")
                    if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                    if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                    val code = conn.responseCode
                    if (code != 200) throw GNErreur.EnvoiEchoue(code, "Enfants du module $moduleCode")
                    val brut = conn.inputStream.bufferedReader().readText()
                    MonitoringCache.setJson(MonitoringCache.keyEnfants(moduleCode), brut)
                    brut
                }
            } catch (e: IOException) {
                MonitoringCache.getJson(MonitoringCache.keyEnfants(moduleCode)) ?: throw e
            }
            val obj = try { JSONObject(text) } catch (_: Exception) {
                throw GNErreur.EnvoiEchoue(0, "Enfants $moduleCode : JSON illisible")
            }
            val children = obj.optJSONObject("children") ?: return@withContext emptyMap<String, List<MonitoringEnfant>>()
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
                    val geoJson = item.optJSONObject("geometry")?.toString()
                    liste.add(MonitoringEnfant(id, nom, proprietesPlates, geoJson))
                }
                result[type] = liste
            }
            result
        }

    /** Aplatit le bloc `properties` d'un objet monitoring en `Map<String, String>`. Filtre les
     *  valeurs null/objets/tableaux : on garde uniquement les scalaires (string, number, bool)
     *  utiles à l'affichage. */
    internal fun aplatirProprietes(props: JSONObject?): Map<String, String> {
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

    /** Résout le label "humain" d'un objet serveur à partir de la fiche en cache local.
     *  Utilisé par la liste des saisies en attente pour afficher le nom du parent au lieu
     *  de "#id". Retourne null si la fiche n'a pas été mise en cache, ou si l'heuristique
     *  ne trouve qu'un fallback "#id". Pas d'appel réseau. */
    fun labelObjetEnCache(moduleCode: String, objectType: String, id: Int): String? {
        if (id <= 0) return null
        val json = MonitoringCache.getJson(MonitoringCache.keyObjet(moduleCode, objectType, id))
            ?: return null
        return try {
            val obj = JSONObject(json)
            val props = aplatirProprietes(obj.optJSONObject("properties"))
            val nom = extraireNomHeuristique(props, objectType, id)
            nom.takeIf { it != "#$id" && it != "—" }
        } catch (_: Exception) { null }
    }

    /** Lit dans le schéma cache de [moduleCode] le `label` humain d'un type d'objet (par ex.
     *  "Site", "Station", "Visite"). Retourne null si le schéma n'est pas en cache ou si le
     *  type n'a pas de label déclaré. Pas d'appel réseau. */
    fun labelTypeEnCache(moduleCode: String, type: String): String? {
        val json = MonitoringCache.getJson(MonitoringCache.keySchema(moduleCode)) ?: return null
        return try {
            val v = JSONObject(json).optJSONObject(type) ?: return null
            v.optString("label", "").takeIf { it.isNotEmpty() }
                ?: v.optString("label_list", "").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /** Lit dans le schéma cache de [moduleCode] le couple (parent_type, id_field_name du
     *  parent) pour un type donné. Retourne null si le schéma n'est pas dispo ou si l'objet
     *  n'a pas de parent. L'idFieldName est porté par le SCHÉMA DU PARENT — c'est le nom de
     *  la propriété qui contient l'id du parent dans la fiche enfant. */
    private fun parentTypeEtIdField(moduleCode: String, type: String): Pair<String, String>? {
        val json = MonitoringCache.getJson(MonitoringCache.keySchema(moduleCode)) ?: return null
        return try {
            val schema = JSONObject(json)
            val v = schema.optJSONObject(type) ?: return null
            val parentType = v.optString("parent_type", "")
                .ifEmpty { v.optJSONArray("parent_types")?.optString(0, "").orEmpty() }
                .takeIf { it.isNotEmpty() } ?: return null
            val parentObj = schema.optJSONObject(parentType) ?: return null
            val idField = parentObj.optString("id_field_name", "")
                .takeIf { it.isNotEmpty() } ?: return null
            parentType to idField
        } catch (_: Exception) { null }
    }

    /** Remonte la chaîne des ancêtres serveur d'un objet en s'appuyant uniquement sur le
     *  cache local (schéma + fiches). Retourne la liste des (type, label) du parent direct
     *  vers le plus haut ancêtre. Liste vide si l'objet n'a pas de parent ou si le cache ne
     *  permet pas de résoudre la chaîne. Safety net à 5 niveaux pour éviter une boucle. */
    fun chaineParentsEnCache(
        moduleCode: String,
        objectType: String,
        id: Int,
    ): List<Triple<String, Int, String>> {
        val result = mutableListOf<Triple<String, Int, String>>()
        var typeCourant = objectType
        var idCourant = id
        val visites = mutableSetOf<Pair<String, Int>>()
        var profondeur = 0
        while (visites.add(typeCourant to idCourant) && profondeur++ < 5) {
            val (parentType, idField) = parentTypeEtIdField(moduleCode, typeCourant) ?: break
            val ficheJson = MonitoringCache.getJson(
                MonitoringCache.keyObjet(moduleCode, typeCourant, idCourant),
            ) ?: break
            val parentIdStr = try {
                val props = aplatirProprietes(JSONObject(ficheJson).optJSONObject("properties"))
                props[idField]
            } catch (_: Exception) { null } ?: break
            val parentId = parentIdStr.toIntOrNull() ?: break
            val label = labelObjetEnCache(moduleCode, parentType, parentId)
                ?: "$parentType #$parentId"
            result.add(Triple(parentType, parentId, label))
            typeCourant = parentType
            idCourant = parentId
        }
        return result
    }

    /** Un objet monitoring complet : type + id + propriétés plates + enfants directs (1 niveau).
     *  Sert pour les fiches site/visite/observation, toutes pilotées par le même renderer
     *  générique côté UI.
     *  - [geometrie] : libellé court formaté pour affichage (ex. "44.123°N, 1.456°E")
     *  - [geometrieGeoJson] : GeoJSON brut sérialisé pour rendu sur carte (osmdroid) */
    data class MonitoringObjet(
        val type: String,
        val id: Int,
        val moduleCode: String,
        val proprietes: Map<String, String>,
        val enfants: Map<String, List<MonitoringEnfant>>,
        val geometrie: String?,
        val geometrieGeoJson: String?,
    )

    /** GET /api/monitorings/object/<module_code>/<object_type>/<id>?depth=1 — fiche d'un objet
     *  (site, visite, observation, …) avec ses propriétés et ses enfants directs.
     *  **Throw** [GNErreur.AuthEchouee] sur défaut d'auth, [GNErreur.EnvoiEchoue] sur HTTP != 200
     *  ou parse cassé (le code est ainsi exploitable côté UI pour humaniser le message). */
    suspend fun chargerObjet(
        config: GeoNatureConfig,
        moduleCode: String,
        objectType: String,
        id: Int,
    ): MonitoringObjet =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val text = try {
                val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                if (auth == null) {
                    MonitoringCache.getJson(MonitoringCache.keyObjet(moduleCode, objectType, id))
                        ?: throw GNErreur.AuthEchouee(401)
                } else {
                    val (token, _, cookies) = auth
                    val url = URL("$base/api/monitorings/object/$moduleCode/$objectType/$id?depth=1")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("Accept", "application/json")
                    if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                    if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                    val code = conn.responseCode
                    if (code != 200) throw GNErreur.EnvoiEchoue(code, "$objectType #$id")
                    val brut = conn.inputStream.bufferedReader().readText()
                    MonitoringCache.setJson(MonitoringCache.keyObjet(moduleCode, objectType, id), brut)
                    brut
                }
            } catch (e: IOException) {
                MonitoringCache.getJson(MonitoringCache.keyObjet(moduleCode, objectType, id)) ?: throw e
            }
            val obj = try { JSONObject(text) } catch (_: Exception) {
                throw GNErreur.EnvoiEchoue(0, "$objectType #$id : JSON illisible")
            }
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
                        val cgeo = item.optJSONObject("geometry")?.toString()
                        liste.add(MonitoringEnfant(cid, cnom, cprops, cgeo))
                    }
                    enfants[ctype] = liste
                }
            }
            val geoObj = obj.optJSONObject("geometry")
            MonitoringObjet(
                type = objectType,
                id = id,
                moduleCode = moduleCode,
                proprietes = proprietes,
                enfants = enfants,
                geometrie = formatGeometrie(geoObj),
                geometrieGeoJson = geoObj?.toString(),
            )
        }

    /** Convertit un objet GeoJSON en libellé court pour affichage. Point → "lat°N/S, lon°E/W",
     *  Polygon/MultiPolygon → "Polygone (N sommets)", autres → le type GeoJSON brut. */
    internal fun formatGeometrie(geo: JSONObject?): String? {
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
            val text: String? = try {
                val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                if (auth == null) {
                    // Auth en échec (offline ou serveur down) → fallback cache si présent.
                    MonitoringCache.getJson(MonitoringCache.keySchema(moduleCode))
                } else {
                    val (token, _, cookies) = auth
                    val url = URL("$base/api/monitorings/config/$moduleCode")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("Accept", "application/json")
                    if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                    if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                    val code = conn.responseCode
                    if (code != 200) {
                        // Fallback cache pour les erreurs serveur transitoires.
                        MonitoringCache.getJson(MonitoringCache.keySchema(moduleCode))
                    } else {
                        val brut = conn.inputStream.bufferedReader().readText()
                        MonitoringCache.setJson(MonitoringCache.keySchema(moduleCode), brut)
                        brut
                    }
                }
            } catch (_: IOException) {
                MonitoringCache.getJson(MonitoringCache.keySchema(moduleCode))
            }
            if (text.isNullOrEmpty()) return@withContext null
            val obj = try { JSONObject(text) } catch (_: Exception) { return@withContext null }
            // Substitution des variables `__MODULE.XXX` (parité substituteVariables de
            // gn_mobile_monitoring) : certains schémas embarquent ces placeholders dans des
            // chaînes (api, id_list…). On les remplace par leur valeur lue dans le bloc `custom`.
            substituerVariablesModule(obj)
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
                val displayPropsArr = v.optJSONArray("display_properties")
                val displayProps = mutableListOf<String>()
                if (displayPropsArr != null) {
                    for (i in 0 until displayPropsArr.length()) {
                        displayPropsArr.optString(i, "").takeIf { it.isNotEmpty() }?.let { displayProps.add(it) }
                    }
                }
                val displayListArr = v.optJSONArray("display_list")
                val displayListNoms = mutableListOf<String>()
                if (displayListArr != null) {
                    for (i in 0 until displayListArr.length()) {
                        displayListArr.optString(i, "").takeIf { it.isNotEmpty() }?.let { displayListNoms.add(it) }
                    }
                }
                val displayFormArr = v.optJSONArray("display_form")
                val displayFormNoms = mutableListOf<String>()
                if (displayFormArr != null) {
                    for (i in 0 until displayFormArr.length()) {
                        displayFormArr.optString(i, "").takeIf { it.isNotEmpty() }?.let { displayFormNoms.add(it) }
                    }
                }
                // `change` : tableau de lignes JS conservé brut pour l'évaluateur de règles.
                val changeArr = v.optJSONArray("change")
                val changeLignes = mutableListOf<String>()
                if (changeArr != null) {
                    for (i in 0 until changeArr.length()) {
                        changeArr.optString(i, "").let { changeLignes.add(it) }
                    }
                }
                val sortsArr = v.optJSONArray("sorts")
                val sortsList = mutableListOf<Pair<String, String>>()
                if (sortsArr != null) {
                    for (i in 0 until sortsArr.length()) {
                        val s = sortsArr.optJSONObject(i) ?: continue
                        val prop = s.optString("prop", "").takeIf { it.isNotEmpty() } ?: continue
                        val dir = s.optString("dir", "asc").lowercase()
                        sortsList.add(prop to dir)
                    }
                }
                // gn_module_monitoring expose les propriétés saisissables dans DEUX blocs côte à
                // côte : `generic` (champs hérités du modèle de base — id, dates système, etc.)
                // et `specific` (champs custom du protocole). Le merge fait que specific peut
                // surcharger generic. Et `parent_types` est un array, pas un scalar.
                val parentTypeFromArr = v.optJSONArray("parent_types")?.optString(0, "")
                    ?.takeIf { it.isNotEmpty() }
                val childrenFromTypesArr = v.optJSONArray("children_types")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").takeIf { it.isNotEmpty() } }
                }.orEmpty()
                result[type] = MonitoringSchemaObjet(
                    type = type,
                    label = v.optString("label", "").takeIf { it.isNotEmpty() },
                    labelList = v.optString("label_list", "").takeIf { it.isNotEmpty() },
                    nameField = nameField,
                    parentType = v.optString("parent_type", "")
                        .ifEmpty { parentTypeFromArr ?: "" }
                        .takeIf { it.isNotEmpty() },
                    childrenTypes = children.ifEmpty { childrenFromTypesArr },
                    properties = parserPropertiesFusionnees(v),
                    displayProperties = displayProps,
                    displayForm = displayFormNoms,
                    changeRules = changeLignes,
                    displayList = displayListNoms,
                    sorts = sortsList,
                    idFieldName = v.optString("id_field_name", "").takeIf { it.isNotEmpty() },
                    idListObserver = v.optInt("id_list_observer", -1).takeIf { it > 0 },
                    idListTaxonomy = v.optInt("id_list_taxonomy", -1).takeIf { it > 0 },
                    geometryType = v.optString("geometry_type", "").takeIf { it.isNotEmpty() && it != "null" },
                    uuidFieldName = v.optString("uuid_field_name", "").takeIf { it.isNotEmpty() },
                    genre = v.optString("genre", "").takeIf { it.isNotEmpty() },
                )
            }
            // Post-processing : dérive l'URL des widgets `observers`/`dataset`/`taxonomy_list`
            // qui n'ont pas d'`api` explicite dans le schéma (raccourci natif gn_module_monitoring
            // — le client est censé connaître l'URL standard). Utilise les ID de listes déclarés
            // au niveau du module.
            val moduleBloc = obj.optJSONObject("module")
            val idListObserver = moduleBloc?.optInt("id_list_observer", -1)?.takeIf { it > 0 }
            val idListTaxonomy = moduleBloc?.optInt("id_list_taxonomy", -1)?.takeIf { it > 0 }
            result.mapValues { (_, schemaObjet) ->
                schemaObjet.copy(properties = schemaObjet.properties.mapValues { (_, prop) ->
                    derirverApiSiManquant(prop, idListObserver, idListTaxonomy, moduleCode)
                })
            }
        }

    /** Pour les widgets `observers`/`dataset`/`taxonomy_list` déclarés sans `api`/`keyLabel`/
     *  `keyValue`, applique les conventions standard gn_module_monitoring (URL fixe + champs
     *  par défaut). Pour `observers`/`taxonomy_list`, si le widget a déjà un api on ne touche
     *  à rien. Le widget `dataset` fait exception : on garantit toujours un filtre `module_code`
     *  (cf. ci-dessous), même quand un api explicite est fourni.
     *  [moduleCodeProtocole] : code du protocole en cours, utilisé pour filtrer les datasets
     *  d'un widget `dataset` quand le schéma n'a pas explicitement `module_code` — on prend
     *  par défaut les datasets rattachés au protocole, ce qui est ce que veut le serveur. */
    private fun derirverApiSiManquant(
        prop: MonitoringPropertySchema,
        idListObserver: Int?,
        idListTaxonomy: Int?,
        moduleCodeProtocole: String,
    ): MonitoringPropertySchema {
        // Cas `dataset` traité AVANT l'early-return sur apiUrl : on veut TOUJOURS un filtre
        // `module_code`, même quand le schéma fournit déjà un `api` explicite (sinon
        // /api/meta/datasets renvoie tous les jeux de données de l'instance, pas seulement
        // ceux rattachés au protocole). Priorité au `module_code` du schéma, sinon le module
        // du protocole en cours. Idempotent : on n'ajoute rien si `module_code` est déjà là.
        if (prop.typeWidget.equals("dataset", ignoreCase = true) ||
            prop.apiUrl?.contains("meta/datasets") == true
        ) {
            val codeFiltre = prop.moduleCodeFiltre ?: moduleCodeProtocole
            val base = prop.apiUrl ?: "meta/datasets"
            val avecModule = if (base.contains("module_code=")) base
                else base + (if (base.contains('?')) "&" else "?") + "module_code=$codeFiltre"
            // `active=true` : exclut les jeux de données archivés du picker (parité web).
            // Idempotent : on n'ajoute pas si le schéma fournit déjà un filtre actif explicite.
            val avecActif = if (avecModule.contains("active=")) avecModule
                else avecModule + "&active=true"
            // `create=<module>.<code_object>` : restreint via CRUVED action=C sur l'objet visé
            // par cette création (ex. apollons.MONITORINGS_VISITES pour un id_dataset de visite).
            // Backend gn_meta : `TDatasets.filter_by_creatable(module_code, object_code)`. Sans
            // ça on listait les datasets du module entier, y compris ceux où le user n'a pas
            // le droit de saisir une visite. Idempotent.
            val apiFiltree = prop.creatableInModule?.takeIf { it.isNotEmpty() }?.let { cim ->
                if (avecActif.contains("create=")) avecActif
                else avecActif + "&create=$cim"
            } ?: avecActif
            return prop.copy(
                apiUrl = apiFiltree,
                keyLabel = prop.keyLabel ?: "dataset_name",
                keyValue = prop.keyValue ?: "id_dataset",
            )
        }
        if (prop.apiUrl != null) return prop
        val (api, kLabel, kValue) = when (prop.typeWidget.lowercase()) {
            "observers" -> Triple(
                idListObserver?.let { "users/menu/$it" } ?: return prop,
                "nom_complet", "id_role",
            )
            "taxonomy_list" -> Triple(
                idListTaxonomy?.let { "biblistes/$it" } ?: return prop,
                "nom_liste", "id_liste",
            )
            else -> return prop
        }
        return prop.copy(
            apiUrl = api,
            keyLabel = prop.keyLabel ?: kLabel,
            keyValue = prop.keyValue ?: kValue,
        )
    }

    /** Fusionne les blocs `generic` (héritage du modèle de base) et `specific` (custom protocole)
     *  d'un object_type gn_module_monitoring. La fusion est faite **attribut par attribut** :
     *  un `specific` peut surcharger seulement le `attribut_label` d'un champ generic tout en
     *  conservant son `type_widget` (parité avec mergeConfigurations de gn_mobile_monitoring).
     *  Ordre : champs generic d'abord, puis champs présents uniquement dans specific.
     *  Fallback sur `properties` (vieille forme) si les deux blocs sont absents. */
    internal fun parserPropertiesFusionnees(objSchema: JSONObject): Map<String, MonitoringPropertySchema> {
        val generic = objSchema.optJSONObject("generic")
        val specific = objSchema.optJSONObject("specific")
        if (generic == null && specific == null) {
            val map = linkedMapOf<String, MonitoringPropertySchema>()
            objSchema.optJSONObject("properties")?.let { parserBlocProperties(it, map) }
            return map
        }
        val cles = linkedSetOf<String>()
        generic?.keys()?.forEach { cles.add(it) }
        specific?.keys()?.forEach { cles.add(it) }
        // Set des clés présentes dans le bloc `specific` — sert à 2 choses :
        //  - passer `enSpecific=true` à parserUnePropriete pour qu'il infère un widget
        //    même quand `type_widget` est absent (cas typique d'un protocole qui n'écrit
        //    que `type_util: 'date'` ou `type_util: 'user'` côté specific) ;
        //  - marquer le PropertySchema résultant pour que construireFormulaire l'inclue
        //    même si display_form ne le liste pas.
        val clesSpecific = specific?.keys()?.asSequence()?.toSet().orEmpty()
        val map = linkedMapOf<String, MonitoringPropertySchema>()
        for (nom in cles) {
            val fusion = fusionnerChamp(generic?.optJSONObject(nom), specific?.optJSONObject(nom))
            val estSpecific = nom in clesSpecific
            parserUnePropriete(nom, fusion, enSpecific = estSpecific)?.let {
                map[nom] = if (estSpecific) it.copy(enSpecific = true) else it
            }
        }
        return map
    }

    /** Fusion shallow de deux config de champ : on copie d'abord les attributs `generic`,
     *  puis ceux de `specific` qui les surchargent un par un. */
    internal fun fusionnerChamp(generic: JSONObject?, specific: JSONObject?): JSONObject {
        val out = JSONObject()
        generic?.keys()?.forEach { k -> out.put(k, generic.get(k)) }
        specific?.keys()?.forEach { k -> out.put(k, specific.get(k)) }
        return out
    }

    /** Parse un bloc d'object_type (generic ou specific) et accumule dans [into]. */
    private fun parserBlocProperties(propsObj: JSONObject, into: MutableMap<String, MonitoringPropertySchema>) {
        val it = propsObj.keys()
        while (it.hasNext()) {
            val nom = it.next()
            val v = propsObj.optJSONObject(nom) ?: continue
            parserUnePropriete(nom, v)?.let { into[nom] = it }
        }
    }

    /** Parse une config de champ (déjà fusionnée generic+specific) en [MonitoringPropertySchema].
     *  Retourne null pour les entrées à ignorer (ni type_widget ni hidden:true). [enSpecific]
     *  indique si le champ vient du bloc `specific` côté serveur — auquel cas on est plus
     *  tolérant sur l'absence de `type_widget` (parité gn_mobile_monitoring : un champ
     *  specific est toujours inclus, avec un widget inféré à partir de `type_util` ou
     *  défaut `text`). */
    internal fun parserUnePropriete(
        nom: String,
        v: JSONObject,
        enSpecific: Boolean = false,
    ): MonitoringPropertySchema? {
        // `hidden` peut être :
        //  - Boolean true  → champ technique masqué à l'UI (id_base_visit, id_module,
        //    medias, nb_observations…). On le CONSERVE quand même dans le schéma car
        //    le serveur Marshmallow l'attend dans le payload POST avec valeur null —
        //    sans ça, un 500 silencieux. C'est `construireFormulaire` qui filtre l'UI.
        //  - Boolean false → champ visible inconditionnellement.
        //  - String        → expression d'affichage dynamique (à évaluer côté UI).
        val hiddenBrut = v.opt("hidden")
        val hiddenBool = hiddenBrut is Boolean && hiddenBrut
        val hiddenExpr = (hiddenBrut as? String)
            ?: v.opt("display")?.takeIf { it is String } as? String
        val typeWidgetBrut = v.optString("type_widget", "")
            .ifEmpty { v.optString("widget", "") }
            .ifEmpty { v.optString("type", "") }
        // Champ sans `type_widget` :
        //   - hidden=true → conservé tel quel (technique, payload-only) ;
        //   - dans specific → on infère un widget : `type_util==date` → date, sinon text
        //     (parité gn_mobile_monitoring form_config_parser.dart:829-832) ;
        //   - sinon → entrée parasite ou champ calculé serveur (nb_visits…), on skip.
        val typeUtilBrut = v.optString("type_util", "").takeIf { it.isNotEmpty() }
        val typeWidget = if (typeWidgetBrut.isEmpty()) {
            when {
                hiddenBool -> ""
                enSpecific -> when (typeUtilBrut?.lowercase()) {
                    "date" -> "date"
                    else -> "text"
                }
                else -> return null
            }
        } else typeWidgetBrut
        val label = v.optString("attribut_label", "")
            .ifEmpty { v.optString("label", "") }
            .ifEmpty { nom.replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }
        val obligatoire = v.optBoolean("required", false)
        val apiBrut = v.optString("api", "").takeIf { it.isNotEmpty() }
        // Détection enrichie du type de nomenclature (parité isNomenclatureField de
        // gn_mobile_monitoring) : code explicite, sinon inféré depuis l'`api`
        // (.../nomenclature/CODE) ou le nom du champ (id_nomenclature_CODE).
        val nomenclatureType = v.optString("code_nomenclature_type", "")
            .ifEmpty { v.optString("nomenclature_type", "") }
            .takeIf { it.isNotEmpty() }
            ?: infererCodeNomenclature(nom, apiBrut)
        val valeursArr = v.optJSONArray("values")
        val valeurs = mutableListOf<Pair<String, String>>()
        if (valeursArr != null) {
            for (i in 0 until valeursArr.length()) {
                val entry = valeursArr.optJSONObject(i)
                if (entry != null) {
                    val value = entry.optString("value", "")
                    val lbl = entry.optString("label", value)
                    if (value.isNotEmpty()) valeurs.add(value to lbl)
                } else {
                    valeursArr.optString(i, "").takeIf { it.isNotEmpty() }?.let { s -> valeurs.add(s to s) }
                }
            }
        }
        // Pour les widgets `datalist` / `nomenclature` (forme ancienne) : récupère ce qu'il
        // faut pour aller fetcher les options dynamiques côté serveur.
        val apiUrl = apiBrut
            ?: nomenclatureType?.let { "nomenclatures/nomenclature/$it" }
        val application = v.optString("application", "").takeIf { it.isNotEmpty() }
        val keyLabel = v.optString("keyLabel", "").takeIf { it.isNotEmpty() }
        val keyValue = v.optString("keyValue", "").takeIf { it.isNotEmpty() }
        val dataPath = v.optString("data_path", "").takeIf { it.isNotEmpty() }
        val multiple = v.optBoolean("multiple", false) || v.optBoolean("multi_select", false)
        val typeUtil = typeUtilBrut
        // Liste taxonomique portée par le champ lui-même : `id_list` direct ou extrait de
        // l'api `taxref/allnamebylist/<id>` (parité getTaxonListId). Sert à restreindre
        // l'autocomplete TaxRef à la liste autorisée pour ce champ.
        val idListTaxonomie = extraireInt(v.opt("id_list"))
            ?: apiBrut?.let { extraireIdListeAllnamebylist(it) }
        // Default value : peut être sous `default` (objet ou scalaire) ou directement
        // sous `value` (scalaire ou objet).
        val defaultBrut = v.opt("default") ?: v.opt("value")
        var defaultValue: String? = null
        val defaultObjet = mutableMapOf<String, String>()
        when (defaultBrut) {
            is String -> defaultValue = defaultBrut.takeIf { it.isNotEmpty() && it != "null" }
            is Number, is Boolean -> defaultValue = defaultBrut.toString()
            is JSONObject -> {
                val dIt = defaultBrut.keys()
                while (dIt.hasNext()) {
                    val dk = dIt.next()
                    defaultBrut.opt(dk)?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
                        ?.let { defaultObjet[dk] = it }
                }
            }
            else -> { /* null, JSONObject.NULL, ou type non géré */ }
        }
        // Bornes numériques : lues à la racine OU dans un sous-objet `validations` (les deux
        // formats coexistent côté GeoNature ; gn_mobile_monitoring lit aussi les deux). On
        // conserve la valeur brute en String — elle peut être un littéral (`"0"`, `0`) ou une
        // expression arrow JS (`"(value) => value.count_max"`) résolue à la volée par
        // [fr.ariegenature.geonat.monitoring.form.ValidationExpr].
        val validationsObj = v.optJSONObject("validations")
        val minValue = bornBrute(v.opt("min")) ?: validationsObj?.let { bornBrute(it.opt("min")) }
        val maxValue = bornBrute(v.opt("max")) ?: validationsObj?.let { bornBrute(it.opt("max")) }

        // Filtres : Map<champ, liste-de-valeurs-acceptables>
        val filtresMap = mutableMapOf<String, List<String>>()
        v.optJSONObject("filters")?.let { fObj ->
            val fIt = fObj.keys()
            while (fIt.hasNext()) {
                val fKey = fIt.next()
                val fArr = fObj.optJSONArray(fKey) ?: continue
                val fVals = (0 until fArr.length()).mapNotNull { i ->
                    fArr.optString(i, "").takeIf { it.isNotEmpty() }
                }
                if (fVals.isNotEmpty()) filtresMap[fKey] = fVals
            }
        }
        return MonitoringPropertySchema(
            nom = nom,
            typeWidget = typeWidget,
            label = label,
            obligatoire = obligatoire,
            typeUtil = typeUtil,
            nomenclatureType = nomenclatureType,
            valeurs = valeurs,
            apiUrl = apiUrl,
            application = application,
            keyLabel = keyLabel,
            keyValue = keyValue,
            dataPath = dataPath,
            multiple = multiple,
            defaultValue = defaultValue,
            defaultObjet = defaultObjet.toMap(),
            hiddenExpr = hiddenExpr,
            hiddenBool = hiddenBool,
            filtres = filtresMap.toMap(),
            definition = v.optString("definition", "").takeIf { it.isNotEmpty() },
            moduleCodeFiltre = v.optString("module_code", "").takeIf { it.isNotEmpty() },
            idListTaxonomie = idListTaxonomie,
            minValue = minValue,
            maxValue = maxValue,
            schemaDotTable = v.optString("schema_dot_table", "").takeIf { it.isNotEmpty() },
            creatableInModule = v.optString("creatable_in_module", "").takeIf { it.isNotEmpty() },
        )
    }

    /** Normalise une borne (`min`/`max`) lue dans le schéma : Number/Boolean → toString,
     *  String non vide → trimée, JSONObject.NULL / null / "" → null. On ne valide PAS le
     *  contenu ici : une expression `(value) => …` est conservée telle quelle, pour résolution
     *  ultérieure par ValidationExpr. */
    private fun bornBrute(brut: Any?): String? = when (brut) {
        null, JSONObject.NULL -> null
        is Number, is Boolean -> brut.toString()
        is String -> brut.trim().takeIf { it.isNotEmpty() }
        else -> null
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

    /** Infère le code mnémonique d'un type de nomenclature quand il n'est pas explicite :
     *  depuis l'api (`.../nomenclatures/nomenclature/STADE_VIE`) ou le nom du champ
     *  (`id_nomenclature_stade_vie` → `stade_vie`). Renvoie null si rien d'exploitable. */
    internal fun infererCodeNomenclature(nom: String, api: String?): String? {
        if (api != null && api.contains("nomenclatures/nomenclature/")) {
            api.substringAfterLast('/').takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (nom.startsWith("id_nomenclature_")) {
            return nom.removePrefix("id_nomenclature_").takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** Extrait l'id de liste taxonomique d'une api `taxref/allnamebylist/<id>`. */
    internal fun extraireIdListeAllnamebylist(api: String): Int? {
        if (!api.contains("allnamebylist/")) return null
        val parts = api.split('/')
        val idx = parts.indexOf("allnamebylist")
        return if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1].toIntOrNull() else null
    }

    /** Remplace en place les placeholders `__MODULE.XXX` présents dans les chaînes du schéma
     *  par leur valeur. La table de correspondance est construite depuis le bloc `custom`
     *  (clés `__MODULE.XXX`) — c'est exactement ce que fait substituteVariables côté
     *  gn_mobile_monitoring. No-op si aucun bloc `custom`. */
    internal fun substituerVariablesModule(racine: JSONObject) {
        val custom = racine.optJSONObject("custom") ?: return
        val subs = mutableMapOf<String, String>()
        custom.keys().forEach { cle ->
            if (cle.startsWith("__MODULE.")) {
                val valeur = custom.opt(cle)
                if (valeur != null && valeur != JSONObject.NULL) subs[cle] = valeur.toString()
            }
        }
        if (subs.isEmpty()) return
        substituerRecursif(racine, subs)
    }

    /** Parcourt récursivement un nœud JSON et remplace, dans chaque feuille String contenant
     *  `__MODULE.`, les placeholders connus par leur valeur. */
    private fun substituerRecursif(node: Any?, subs: Map<String, String>) {
        when (node) {
            is JSONObject -> {
                val cles = node.keys().asSequence().toList()
                for (cle in cles) {
                    when (val valeur = node.opt(cle)) {
                        is String -> if (valeur.contains("__MODULE.")) {
                            node.put(cle, appliquerSubstitutions(valeur, subs))
                        }
                        is JSONObject, is JSONArray -> substituerRecursif(valeur, subs)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    when (val valeur = node.opt(i)) {
                        is String -> if (valeur.contains("__MODULE.")) {
                            node.put(i, appliquerSubstitutions(valeur, subs))
                        }
                        is JSONObject, is JSONArray -> substituerRecursif(valeur, subs)
                    }
                }
            }
        }
    }

    private fun appliquerSubstitutions(valeur: String, subs: Map<String, String>): String {
        var out = valeur
        for ((placeholder, remplacement) in subs) {
            if (out.contains(placeholder)) out = out.replace(placeholder, remplacement)
        }
        return out
    }

    /** Convertit une valeur JSON (Int/Double/String numérique) en Int, sinon null. */
    private fun extraireInt(value: Any?): Int? = when (value) {
        null, JSONObject.NULL -> null
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    /** Une option de datalist fetchée depuis l'API serveur. `cdNomenclature`/`labelDefaut`
     *  permettent de résoudre les valeurs par défaut déclarées dans le schéma
     *  (`default: {cd_nomenclature: "18"}` ou `default: {label_default: "Imago"}`). */
    data class OptionDatalist(
        val value: String,
        val label: String,
        val cdNomenclature: String? = null,
        val labelDefaut: String? = null,
    )

    /** Cache du LabelResolver par moduleCode. Évite de re-fetcher les nomenclatures / users /
     *  datasets entre fiches d'un même protocole. Invalidé en même temps que [dernierSchema]
     *  ne le serait — ici on garde indéfiniment dans la session (les nomenclatures changent
     *  rarement). */
    @Volatile private var cacheResolvers: MutableMap<String, LabelResolver> = mutableMapOf()

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
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
            if (conn.responseCode != 200) return emptyMap()
            val text = conn.inputStream.bufferedReader().readText()
            val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                val obj = try { JSONObject(text) } catch (_: Exception) { return emptyMap() }
                val cle = dataPath ?: listOf("values", "data", "items", "results")
                    .firstOrNull { obj.has(it) } ?: return emptyMap()
                obj.optJSONArray(cle) ?: return emptyMap()
            }
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

    /** Trie une liste d'enfants selon les critères déclarés par le schéma (champ `sorts`).
     *  Tente une comparaison numérique d'abord, fallback comparaison de strings (les dates ISO
     *  YYYY-MM-DD se trient correctement en string). Sans critères : tri alpha par nom. */
    fun trierEnfants(enfants: List<MonitoringEnfant>, sorts: List<Pair<String, String>>): List<MonitoringEnfant> {
        if (sorts.isEmpty()) return enfants.sortedBy { it.nom.lowercase() }
        val comp = Comparator<MonitoringEnfant> { a, b ->
            var r = 0
            for ((prop, dir) in sorts) {
                val va = a.proprietes[prop] ?: ""
                val vb = b.proprietes[prop] ?: ""
                val da = va.toDoubleOrNull()
                val db = vb.toDoubleOrNull()
                r = if (da != null && db != null) da.compareTo(db) else va.compareTo(vb)
                if (dir == "desc") r = -r
                if (r != 0) break
            }
            r
        }
        return enfants.sortedWith(comp)
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
                val conn = URL("$base/api/users/menu/$idListe").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
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
        val base = config.urlServeur.trim().trimEnd('/')
        val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
            ?: return@withContext fallbackDatasetCache()
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
        val conn = urlFinale.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
        if (postBody != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
        }
        if (conn.responseCode != 200) return@withContext fallbackDatasetCache()
        val text = conn.inputStream.bufferedReader().readText()
        // Réponse soit array direct, soit objet contenant data_path → array.
        val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
            val obj = try { JSONObject(text) } catch (_: Exception) {
                return@withContext fallbackDatasetCache()
            }
            val cle = prop.dataPath ?: listOf("values", "data", "items", "results")
                .firstOrNull { obj.has(it) } ?: return@withContext fallbackDatasetCache()
            obj.optJSONArray(cle) ?: return@withContext fallbackDatasetCache()
        }
        extraireOptions(array, keyValue, keyLabel, prop.filtres)
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

    /** Envoie une nouvelle visite (ou tout objet monitoring "saisissable") sur le serveur.
     *  Endpoint : `POST /api/monitorings/object/<moduleCode>/<objectType>`.
     *  Le body est un GeoJSON-like `{geometry, properties}` ; pour les visites, la
     *  géométrie est généralement héritée du parent côté serveur — on n'envoie donc pas
     *  de `geometry` par défaut.
     *
     *  [parentIdField] / [parentId] : nom du champ FK vers le parent (ex `id_base_site`)
     *  et son id. Posés directement dans `properties` (le serveur les attend là, pas
     *  dans l'URL).
     *
     *  [valeurs] : map code_propriété → valeur typée renvoyée par
     *  [fr.ariegenature.geonat.monitoring.form.FormulaireRenderer.lireValeurs]. Les types
     *  attendus côté serveur sont déduits ici par best-effort :
     *  - Int / Long / Number → number JSON
     *  - Boolean → boolean JSON
     *  - List<*> → JSONArray (cas SELECT_MULTIPLE, observers, etc.)
     *  - String → tentative parse Int sinon String JSON (les ids nomenclature/dataset
     *    voyagent en String depuis le renderer alors que le serveur les veut en number).
     *
     *  Retourne le `id` du nouvel objet créé via Result.success, ou une exception via
     *  Result.failure (auth, HTTP, parse). */
    suspend fun envoyerVisite(
        config: GeoNatureConfig,
        moduleCode: String,
        objectType: String,
        parentIdField: String?,
        parentId: Int?,
        valeurs: Map<String, Any?>,
        nomsChampsSchema: Collection<String> = emptyList(),
        /** Codes des champs texte-libre dont la valeur String ne doit PAS être coercée en
         *  Int même si elle est numérique (cf. audit B6). Vide → coercition historique. */
        champsTexteLibre: Collection<String> = emptyList(),
        /** uuid pré-généré côté client à injecter dans le payload sous [uuidFieldName].
         *  Sert à connaître à l'avance l'uuid_attached_row pour l'upload média ultérieur. */
        uuidClient: String? = null,
        uuidFieldName: String? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val base = config.urlServeur.trim().trimEnd('/')
            val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)
            val (token, _, cookies) = auth

            val properties = JSONObject()
            // Lien au parent : injecté dans properties (le sélecteur de parent est masqué
            // côté UI car déjà choisi par le drill-down qui amène ici).
            if (!parentIdField.isNullOrEmpty() && parentId != null && parentId > 0) {
                properties.put(parentIdField, parentId)
            }
            for ((code, brut) in valeurs) {
                // Un champ texte-libre garde sa valeur String telle quelle : "42" dans un
                // commentaire ne doit pas partir en number (audit B6).
                properties.put(code, normaliserPourJson(brut, preserverString = code in champsTexteLibre))
            }
            // uuid pré-généré côté client (cas où on a un média à rattacher après création).
            // Injecté seulement si le caller a fourni le nom du champ — sinon on ne sait pas
            // où le mettre dans le payload et le serveur générera son propre uuid.
            if (!uuidClient.isNullOrEmpty() && !uuidFieldName.isNullOrEmpty() &&
                !properties.has(uuidFieldName)) {
                properties.put(uuidFieldName, uuidClient)
            }
            // `id_digitiser` : le serveur attend l'id_role de l'utilisateur qui enregistre.
            // Contrainte NOT NULL côté DB monitoring → un 500 silencieux sans ce champ.
            val idRole = config.idRoleUtilisateur.takeIf { it > 0 }
            if (idRole != null && !properties.has("id_digitiser")) {
                properties.put("id_digitiser", idRole)
            }
            // `id_dataset` : NOT NULL côté table monitoring (FK gn_meta.t_datasets).
            // ⚠ Le dataset OCCTAX configuré dans l'app n'est PAS forcément valide ici —
            // chaque protocole monitoring a son propre dataset rattaché. Si le schéma ne
            // l'expose pas, on cherche dans le cache local le premier dataset rattaché
            // au module courant (champ `moduleCodes` du cache datasets, peuplé au sync
            // via /api/meta/datasets?fields=modules). Fallback sur le dataset OCCTAX global
            // seulement si rien trouvé (instances anciennes sans la relation).
            if (!properties.has("id_dataset")) {
                val idDatasetModule = trouverDatasetPourModule(config, moduleCode)
                // Fallback OCCTAX seulement s'il est réellement présent sur le serveur courant
                // (sinon FK invalide → 500 opaque). On trace l'usage du fallback pour diagnostic :
                // le dataset OCCTAX n'est pas forcément rattaché au protocole (risque de 403 CRUVED).
                val fallbackOcctax = config.idDataset.trim().toIntOrNull()
                    ?.takeIf { it > 0 && config.datasetAcceptablePourEnvoi(it) }
                val idDataset = idDatasetModule ?: fallbackOcctax
                if (idDatasetModule == null && fallbackOcctax != null) {
                    android.util.Log.w("MonitoringApi",
                        "id_dataset du module '$moduleCode' introuvable → fallback dataset OCCTAX $fallbackOcctax (peut être hors-protocole)")
                }
                if (idDataset != null) properties.put("id_dataset", idDataset)
            }
            // `visit_date_max` est en général NOT NULL en DB. Si le formulaire ne l'a pas
            // collecté (cas fréquent : une seule date dans l'UI), copie depuis visit_date_min.
            if (properties.has("visit_date_min") && !properties.has("visit_date_max")) {
                properties.put("visit_date_max", properties.opt("visit_date_min"))
            }
            // Nettoie les valeurs vides résiduelles côté String : un champ DATE non rempli
            // arrive à "" et casse le parse côté serveur ; pareil pour les heures, etc.
            // On retire les clés à "" pour laisser le serveur appliquer son défaut.
            val cleanedKeys = mutableListOf<String>()
            properties.keys().forEach { k -> cleanedKeys.add(k) }
            cleanedKeys.forEach { k ->
                val v = properties.opt(k)
                if (v is String && v.isBlank()) properties.remove(k)
            }
            // Padding : le serveur Marshmallow valide que TOUTES les propriétés du schéma
            // sont présentes dans le payload, y compris les champs techniques cachés à
            // l'UI (id_base_visit, id_module, medias, nb_observations, observers_txt…).
            // Les champs absents reçoivent null — ce que fait aussi le formulaire web,
            // sauf pour quelques champs typés array qui prennent `[]` par défaut.
            val champsArrayParDefaut = setOf("medias")
            nomsChampsSchema.forEach { k ->
                if (!properties.has(k)) {
                    if (k in champsArrayParDefaut) properties.put(k, JSONArray())
                    else properties.put(k, JSONObject.NULL)
                }
            }

            // Format simple `{properties: …}` — c'est exactement ce que le formulaire web
            // GeoNature envoie sur la même instance (vérifié via DevTools). Le wrapper
            // GeoJSON Feature complet déclenche un 500 silencieux côté serveur.
            val body = JSONObject().put("properties", properties)

            val urlStr = "$base/api/monitorings/object/$moduleCode/$objectType"
            val bodyStr = body.toString()
            // Payload loggé seulement en debug : il contient des données métier (observateurs,
            // commentaires, géoloc) qu'on ne veut pas exposer en clair dans logcat en production.
            if (fr.ariegenature.geonat.BuildConfig.DEBUG) {
                android.util.Log.i("MonitoringApi", "POST $urlStr\n  body=$bodyStr")
            }
            val conn = URL(urlStr).openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 20000
                conn.readTimeout = 20000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                conn.outputStream.use { it.write(bodyStr.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val erreur = try {
                        conn.errorStream?.bufferedReader()?.readText()
                    } catch (_: Exception) { null }
                    android.util.Log.w("MonitoringApi", "POST $urlStr → HTTP $code\n  réponse=$erreur")
                    throw GNErreur.EnvoiEchoue(code, erreur?.take(500) ?: "pas de message")
                }
                val text = conn.inputStream.bufferedReader().readText()
                val obj = JSONObject(text)
                // L'API retourne le nouvel objet créé sous forme GeoJSON Feature. L'id se trouve
                // soit à la racine, soit dans `properties`, soit dans `id_<type>` du payload.
                obj.optInt("id", -1)
                    .takeIf { it > 0 }
                    ?: obj.optJSONObject("properties")?.optInt("id", -1)?.takeIf { it > 0 }
                    ?: obj.optJSONObject("properties")?.optInt("id_$objectType", -1)?.takeIf { it > 0 }
                    ?: 0
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Convertit une valeur typée (issue du form renderer) en valeur acceptable par
     *  JSONObject.put — préserve les types Number/Boolean, tente de parser les Strings
     *  numériques en Int (pour matcher ce qu'attend le serveur sur id_nomenclature etc.),
     *  sérialise List<*> en JSONArray. */
    /** Cherche le premier dataset actif rattaché au [moduleCode] côté serveur. D'abord
     *  dans le cache local OCCTAX (si par chance le dataset cumule plusieurs modules),
     *  puis via un appel live `/api/meta/datasets?module_code=<m>&active=true` qui
     *  retourne directement les datasets du module monitoring (filtré CRUVED). */
    private fun trouverDatasetPourModule(config: GeoNatureConfig, moduleCode: String): Int? {
        // 1) Cache local
        val json = config.datasetsCacheJson.takeIf { it.isNotEmpty() }
        if (json != null) {
            try {
                val t = object : com.google.gson.reflect.TypeToken<List<GeoNatureDataset>>() {}.type
                val datasets: List<GeoNatureDataset>? = com.google.gson.Gson().fromJson(json, t)
                datasets?.firstOrNull { moduleCode in it.moduleCodes }?.let { return it.id }
            } catch (_: Exception) { /* fallback ci-dessous */ }
        }
        // 2) Appel live filtré par module (les datasets monitoring ne sont pas dans le
        // cache OCCTAX par défaut — sync app filtre module_code=OCCTAX).
        return try {
            val base = config.urlServeur.trim().trimEnd('/')
            val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse) ?: return null
            val (token, _, cookies) = auth
            val variantes = listOf(moduleCode, moduleCode.lowercase()).distinct()
            for (variant in variantes) {
                val conn = URL("$base/api/meta/datasets?module_code=$variant&active=true").openConnection()
                    as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                if (conn.responseCode != 200) continue
                val txt = conn.inputStream.bufferedReader().readText()
                val arr = try { JSONArray(txt) } catch (_: Exception) {
                    JSONObject(txt).optJSONArray("data") ?: JSONArray()
                }
                if (arr.length() > 0) {
                    val id = arr.getJSONObject(0).optInt("id_dataset", -1)
                    if (id > 0) return id
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /** [preserverString] : quand true, une valeur String numérique reste String (champ
     *  texte-libre). N'affecte que le niveau racine — les items d'array (observers, etc.)
     *  restent coercés car le serveur les veut en Int. */
    private fun normaliserPourJson(v: Any?, preserverString: Boolean = false): Any {
        if (v === JSONObject.NULL) return JSONObject.NULL
        return when (v) {
            null -> JSONObject.NULL
            is Boolean -> v
            is Number -> v
            // JSONArray arrive quand on reparse un payload depuis l'outbox. On reconstruit
            // un nouveau JSONArray en normalisant chaque item — sinon les "121" stockés
            // en String dans observers restent en String alors que le serveur attend des Int.
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until v.length()) out.put(normaliserPourJson(v.opt(i)))
                out
            }
            is JSONObject -> v
            is List<*> -> JSONArray().apply { v.forEach { put(normaliserPourJson(it)) } }
            is String -> {
                val t = v.trim()
                if (preserverString) t else t.toIntOrNull() ?: t
            }
            else -> v.toString()
        }
    }
}