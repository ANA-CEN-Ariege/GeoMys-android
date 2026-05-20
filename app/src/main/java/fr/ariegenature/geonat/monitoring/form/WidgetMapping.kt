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
        "time" -> ViewType.TEXT // POC : "08:00" en libre — un ViewType.TIME dédié reste à porter
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
        // Encore à porter :
        //   medias       → composant galerie + upload de fichiers
        //   taxonomy     → autocomplete TaxRef (besoin d'un picker dédié)
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
            )
        )
    }
    return FormulaireConstruction(fields, ignores)
}
