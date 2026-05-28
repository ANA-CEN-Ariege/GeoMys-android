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
        "datetime" -> ViewType.DATE // POC : on ignore la composante heure
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
        // Non portés (listés dans `ignores`, affichés à l'utilisateur) :
        //   medias    → capture/import + upload gn_commons rattaché à l'objet créé (pipeline
        //               dédié, cf. note point 7) ; non simulé pour ne pas corrompre le payload.
        //   geometry  → picker carte : inutile ici (roadmap = saisies sur objets existants,
        //               pas de création d'objet géolocalisé).
        //   min_max   → besoin d'un ViewType.MIN_MAX (deux NumberPicker côte à côte)
        else -> null
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
    // Priorité de l'ordre des champs : display_form > display_properties > toutes les clés
    // (parité version web / gn_mobile_monitoring où display_form prime sur display_properties).
    val ordre = schemaObjet.displayForm.ifEmpty {
        schemaObjet.displayProperties.ifEmpty { schemaObjet.properties.keys.toList() }
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
            )
        )
    }
    return FormulaireConstruction(fields, ignores)
}
