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

import fr.ariegenature.geomys.network.MonitoringApi.MonitoringPropertySchema
import fr.ariegenature.geomys.network.MonitoringApi.MonitoringSchemaObjet
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.MonitoringCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL

/** Schémas déclaratifs des protocoles gn_module_monitoring : chargement du
 *  `/api/monitorings/config/<module_code>` (+ cache disque), fusion des blocs
 *  `generic`/`specific`, parsing des propriétés saisissables, substitution des
 *  placeholders `__MODULE.XXX` et dérivation des URLs d'API implicites
 *  (observers/dataset/taxonomy_list). Extrait de [MonitoringApi] (découpage du
 *  god-object) — [MonitoringApi.chargerSchemaProtocole] reste la façade appelée
 *  par les écrans. */
object MonitoringSchemas {

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
                    val conn = HttpClient.get(url, token, cookies, 10000)
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
        if (prop.apiUrl != null) {
            // Widget `nomenclature` : l'api est dérivé (`nomenclatures/nomenclature/<TYPE>`) mais
            // le schéma ne fournit JAMAIS keyLabel/keyValue. L'endpoint GeoNature renvoie des items
            // `{id_nomenclature, cd_nomenclature, label_default, label_fr, …}` → valeur=id_nomenclature,
            // libellé=label_default. Sans ça chargerOptionsDatalist renvoyait null → « fetch options
            // échoué » (cas id_nomenclature_statut_obs du protocole Chevêches).
            if (prop.apiUrl.contains("nomenclatures/nomenclature/") &&
                (prop.keyLabel == null || prop.keyValue == null)
            ) {
                return prop.copy(
                    keyValue = prop.keyValue ?: "id_nomenclature",
                    keyLabel = prop.keyLabel ?: "label_default",
                )
            }
            // Un datalist `monitorings/list/<module>/<type>?fields=ID&fields=NOM` (ex. id_base_site
            // du protocole Chevêches) déclare un `api` mais souvent PAS de keyLabel/keyValue : on
            // les dérive des `fields=` de l'URL (champ `id_*` = valeur, l'autre = libellé). Sans ça
            // chargerOptionsDatalist renvoyait null → « fetch options échoué ».
            if (prop.keyLabel == null || prop.keyValue == null) {
                val fields = Regex("""fields=([^&]+)""").findAll(prop.apiUrl)
                    .map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }.toList()
                if (fields.size >= 2) {
                    val idField = fields.firstOrNull { it.startsWith("id_") || it == "id" }
                    val labelField = fields.firstOrNull { it != idField }
                    return prop.copy(
                        keyValue = prop.keyValue ?: idField ?: fields[0],
                        keyLabel = prop.keyLabel ?: labelField ?: fields[1],
                    )
                }
            }
            return prop
        }
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
        // `required` : booléen OU expression dynamique `({value}) => …` (ex. champs
        // végétation du protocole Point écoute avifaune, requis seulement au passage 2 —
        // même mécanique que `hidden`). L'expression est transportée jusqu'au renderer qui
        // l'évalue contre les valeurs courantes du formulaire.
        val requiredBrut = v.opt("required")
        val obligatoire = when (requiredBrut) {
            is Boolean -> requiredBrut
            is String -> requiredBrut.equals("true", ignoreCase = true)
            else -> false
        }
        val obligatoireExpr = (requiredBrut as? String)
            ?.takeIf { it.contains("=>") || it.contains("value") }
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
        // [fr.ariegenature.geomys.monitoring.form.ValidationExpr].
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
            obligatoireExpr = obligatoireExpr,
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
}
