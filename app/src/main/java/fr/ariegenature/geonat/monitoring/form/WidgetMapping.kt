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

package fr.ariegenature.geonat.monitoring.form

import fr.ariegenature.geonat.network.MonitoringApi

/** Détection enrichie d'un champ taxonomie (parité isTaxonomyField de gn_mobile_monitoring) :
 *  au-delà du `type_widget`, on reconnaît un `type_util == taxonomy`, un champ nommé `cd_nom`,
 *  ou une api `taxref/allnamebylist/<id>`. */
private fun estChampTaxon(prop: MonitoringApi.MonitoringPropertySchema): Boolean {
    if (prop.typeWidget.lowercase() in setOf("taxonomy", "taxon", "taxon-input", "taxonomy-input")) return true
    if (prop.typeUtil?.lowercase() == "taxonomy") return true
    if (prop.nom == "cd_nom") return true
    if (prop.apiUrl?.contains("allnamebylist/") == true) return true
    return false
}

/** Convertit un schéma de propriété gn_module_monitoring vers le [ViewType] du renderer.
 *  Le `multiple` est lu depuis le schéma — datalist/observers/dataset peuvent être single
 *  (Spinner) ou multi (dialog cases à cocher). Renvoie null pour les widgets pas encore portés. */
fun mapperViewType(prop: fr.ariegenature.geonat.network.MonitoringApi.MonitoringPropertySchema): ViewType? {
    // Taxonomie détectée par plusieurs signaux (cf. estChampTaxon) — prioritaire car un
    // champ taxon peut être déclaré en `datalist` côté serveur.
    if (estChampTaxon(prop)) return ViewType.TAXON
    return when (prop.typeWidget.lowercase()) {
        "text", "string" -> ViewType.TEXT
        "textarea", "observers-text" -> ViewType.TEXTAREA
        "number", "integer", "float", "decimal" -> ViewType.NUMBER
        "date" -> ViewType.DATE
        "datetime", "date-time", "timestamp" -> ViewType.DATETIME
        "time" -> ViewType.TIME
        "select" ->
            if (prop.multiple) ViewType.SELECT_MULTIPLE else ViewType.SELECT
        // `radio` : choix unique en boutons radio (parité web GeoNature / gn_mobile_monitoring,
        // qui rendent ce widget en RadioButton, pas en liste déroulante). Un `radio multiple`
        // n'a pas de sens → on bascule sur la multi-sélection à cases.
        "radio" ->
            if (prop.multiple) ViewType.SELECT_MULTIPLE else ViewType.RADIO
        // Widget explicitement multi-sélection côté serveur (variantes vues sur le terrain).
        "multiselect", "multi_select", "select_multiple", "select-multiple" -> ViewType.SELECT_MULTIPLE
        // Booléens : différentes conventions serveur — toutes vers CheckBox.
        "bool_checkbox", "bool", "boolean", "checkbox" -> ViewType.CHECKBOX
        // Widgets de type "liste alimentée par API" : tous unifiés sur SELECT(_MULTIPLE).
        // Le pré-fetch (cf [chargerOptionsDatalist]) leur donne des `values` toutes prêtes
        // depuis l'endpoint déclaré dans le schéma (api, keyLabel, keyValue, data_path).
        "datalist", "nomenclature", "observers", "dataset" ->
            if (prop.multiple) ViewType.SELECT_MULTIPLE else ViewType.SELECT
        // Taxonomie : déjà capté par estChampTaxon ci-dessus, conservé pour lisibilité.
        "taxonomy", "taxon", "taxon-input", "taxonomy-input" -> ViewType.TAXON
        // `html` : bloc d'aide / message texte injecté par le protocole web. Pas saisissable
        // → on l'écarte explicitement (parité avec isHtmlWidget de gn_mobile_monitoring).
        "html" -> null
        // `medias` : pièce jointe — upload différé vers gn_commons après création de l'objet
        // (cf. OutboxEnvoi.envoyerUne). MVP single-file ; multi-fichiers à porter plus tard.
        "medias" -> ViewType.MEDIA
        // Non portés nativement, mais on dégrade gracieusement en TEXT pour ne pas perdre
        // le champ — l'utilisateur saisira en clair plutôt que de voir le champ disparaître :
        //   geometry  → picker carte (hors périmètre actuel monitoring) ;
        //   min_max   → couple de NumberPicker côte à côte (à porter quand un protocole l'utilise).
        // Parité gn_mobile_monitoring : `default: return 'TextField'`.
        else -> ViewType.TEXT
    }
}

/** Champs techniques toujours exclus du FORMULAIRE de saisie (parité globalFieldsToExclude
 *  de gn_mobile_monitoring). Ils restent dans le schéma (payload POST) mais ne sont jamais
 *  éditables : identifiants, uuid, digitiser, compteurs et dates calculés côté serveur.
 *  Filet de sécurité au cas où le serveur ne les marque pas tous `hidden:true` (certains
 *  protocoles redéclarent nb_visits/last_visit dans `specific` juste pour le libellé). */
private val CHAMPS_EXCLUS_FORMULAIRE = setOf(
    "uuid_base_visit", "uuid_observation", "uuid_base_site", "uuid_sites_group",
    "uuid_module_complement",
    "id_module", "id_digitiser", "id_base_site",
    "nb_observations", "nb_visits", "last_visit",
    "observers_txt",
)

/** Construit une liste d'[EditableField] à partir d'un object_type du schéma serveur.
 *  Respecte `display_properties` pour l'ordre. Renvoie aussi la liste des propriétés ignorées
 *  (widgets non encore supportés) pour qu'on puisse l'afficher à l'utilisateur. */
data class FormulaireConstruction(
    val fields: List<EditableField>,
    val ignores: List<Pair<String, String>>, // (nom propriété, type_widget)
)

fun construireFormulaire(schemaObjet: MonitoringApi.MonitoringSchemaObjet): FormulaireConstruction {
    // Parité version web monitoring (monitoring-form.component.ts → initObjFormDefiniton +
    // sortObjFormDefinition) : on inclut TOUTES les propriétés du schéma fusionné
    // generic+specific qui ont un type_widget exploitable, puis on trie par display_properties
    // (display_form n'est PAS utilisé pour filtrer côté web). Notre ancien filtrage par
    // union(display_form, display_properties) masquait les champs canoniques comme `comments`
    // et `id_dataset` que les protocoles laissent souvent hors de ces listes (ex. apollons).
    // L'ordre final est : display_properties d'abord (dans l'ordre déclaré), puis les autres
    // champs du schéma dans leur ordre serveur (LinkedHashMap preserve l'insertion).
    val displayProps = schemaObjet.displayProperties
    val ordre = if (displayProps.isEmpty()) {
        schemaObjet.properties.keys.toList()
    } else {
        val deja = LinkedHashSet<String>()
        val resultat = mutableListOf<String>()
        displayProps.forEach { if (it in schemaObjet.properties && deja.add(it)) resultat.add(it) }
        schemaObjet.properties.keys.forEach { if (deja.add(it)) resultat.add(it) }
        resultat
    }
    // Exclusions techniques + cas spécifique sites_group (id_inventor inexistant pour ce type).
    val exclus = if (schemaObjet.type == "sites_group")
        CHAMPS_EXCLUS_FORMULAIRE + "id_inventor"
    else CHAMPS_EXCLUS_FORMULAIRE
    val fields = mutableListOf<EditableField>()
    val ignores = mutableListOf<Pair<String, String>>()
    ordre.forEach { nom ->
        if (nom in exclus) return@forEach
        val prop = schemaObjet.properties[nom] ?: return@forEach
        // Les propriétés `hidden:true` côté schéma serveur sont conservées dans le
        // MonitoringSchemaObjet pour le payload POST (le serveur Marshmallow les attend)
        // mais on ne les affiche pas dans le formulaire UI.
        if (prop.hiddenBool) return@forEach
        val viewType = mapperViewType(prop)
        if (viewType == null) {
            ignores.add(prop.nom to prop.typeWidget)
            return@forEach
        }
        fields.add(
            EditableField(
                code = prop.nom,
                viewType = viewType,
                label = prop.label,
                values = prop.valeurs.map { (v, l) -> PropertyValue(v, l) },
                obligatoire = prop.obligatoire,
                aide = prop.definition,
                hiddenExpr = prop.hiddenExpr,
                // Pour les widgets TAXON, on transmet l'id_list_taxonomy : d'abord celui porté
                // par le champ lui-même (id_list / api allnamebylist), sinon celui déclaré au
                // niveau de l'object_type. Le renderer filtre l'autocomplete TaxRef à cette
                // liste. Si rien, le caller (NouvelleVisiteFragment) peut patcher avec le
                // idListTaxonomy du module ou le idTaxaList du dataset.
                idListeTaxonomieRestreinte = if (viewType == ViewType.TAXON)
                    (prop.idListTaxonomie ?: schemaObjet.idListTaxonomy) else null,
                // Bornes numériques (NUMBER uniquement — ignorées sinon). On les transmet brutes :
                // un littéral est résolu immédiatement, une expression `(value) => value.X` est
                // résolue contre les valeurs courantes au moment de la validation.
                minValue = if (viewType == ViewType.NUMBER) prop.minValue else null,
                maxValue = if (viewType == ViewType.NUMBER) prop.maxValue else null,
                // MEDIA uniquement : table cible côté gn_commons pour résoudre id_table_location
                // lors de l'upload du fichier après création de l'objet parent.
                schemaDotTable = if (viewType == ViewType.MEDIA) prop.schemaDotTable else null,
            )
        )
    }
    return FormulaireConstruction(fields, ignores)
}
