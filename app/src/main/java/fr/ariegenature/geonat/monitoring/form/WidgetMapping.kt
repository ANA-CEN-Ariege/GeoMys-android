package fr.ariegenature.geonat.monitoring.form

import fr.ariegenature.geonat.network.MonitoringApi

/** Convertit un schéma de propriété gn_module_monitoring vers le [ViewType] du renderer.
 *  Le `multiple` est lu depuis le schéma — datalist/observers/dataset peuvent être single
 *  (Spinner) ou multi (dialog cases à cocher). Renvoie null pour les widgets pas encore portés. */
fun mapperViewType(prop: fr.ariegenature.geonat.network.MonitoringApi.MonitoringPropertySchema): ViewType? =
    when (prop.typeWidget.lowercase()) {
        "text", "string" -> ViewType.TEXT
        "textarea", "observers-text" -> ViewType.TEXTAREA
        "number", "integer", "float", "decimal" -> ViewType.NUMBER
        "date" -> ViewType.DATE
        "datetime" -> ViewType.DATE // POC : on ignore la composante heure
        "time" -> ViewType.TIME
        "select", "radio" ->
            if (prop.multiple) ViewType.SELECT_MULTIPLE else ViewType.SELECT
        // Widget explicitement multi-sélection côté serveur (variantes vues sur le terrain).
        "multiselect", "multi_select", "select_multiple", "select-multiple" -> ViewType.SELECT_MULTIPLE
        // Booléens : différentes conventions serveur — toutes vers CheckBox.
        "bool_checkbox", "bool", "boolean", "checkbox" -> ViewType.CHECKBOX
        // Widgets de type "liste alimentée par API" : tous unifiés sur SELECT(_MULTIPLE).
        // Le pré-fetch (cf [chargerOptionsDatalist]) leur donne des `values` toutes prêtes
        // depuis l'endpoint déclaré dans le schéma (api, keyLabel, keyValue, data_path).
        "datalist", "nomenclature", "observers", "dataset" ->
            if (prop.multiple) ViewType.SELECT_MULTIPLE else ViewType.SELECT
        // Taxonomie : autocomplete TaxRef sur le cache local (= ce qu'on synchronise au sync).
        "taxonomy", "taxon", "taxon-input", "taxonomy-input" -> ViewType.TAXON
        // Encore à porter :
        //   medias       → composant galerie + upload de fichiers
        //   geometry     → picker sur carte
        //   min_max       → besoin d'un ViewType.MIN_MAX (deux NumberPicker côte à côte)
        else -> null
    }

/** Construit une liste d'[EditableField] à partir d'un object_type du schéma serveur.
 *  Respecte `display_properties` pour l'ordre. Renvoie aussi la liste des propriétés ignorées
 *  (widgets non encore supportés) pour qu'on puisse l'afficher à l'utilisateur. */
data class FormulaireConstruction(
    val fields: List<EditableField>,
    val ignores: List<Pair<String, String>>, // (nom propriété, type_widget)
)

fun construireFormulaire(schemaObjet: MonitoringApi.MonitoringSchemaObjet): FormulaireConstruction {
    val ordre = schemaObjet.displayProperties.ifEmpty { schemaObjet.properties.keys.toList() }
    val fields = mutableListOf<EditableField>()
    val ignores = mutableListOf<Pair<String, String>>()
    ordre.forEach { nom ->
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
                // Pour les widgets TAXON, on transmet l'id_list_taxonomy déclaré au niveau
                // de l'object_type (ex. id_list_taxonomy d'une "observation" STOM). Le
                // renderer filtrera l'autocomplete TaxRef à cette liste. Si l'objet
                // n'a pas d'id_list_taxonomy, le caller (NouvelleVisiteFragment) peut
                // patcher avec le idListTaxonomy du module ou le idTaxaList du dataset.
                idListeTaxonomieRestreinte = if (viewType == ViewType.TAXON)
                    schemaObjet.idListTaxonomy else null,
            )
        )
    }
    return FormulaireConstruction(fields, ignores)
}
