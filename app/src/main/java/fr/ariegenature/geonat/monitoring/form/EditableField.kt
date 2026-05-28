package fr.ariegenature.geonat.monitoring.form

/** Type de widget d'un champ de formulaire dynamique gn_module_monitoring.
 *  POC volontairement minimaliste — on étend au fil de l'eau quand on rencontre des protocoles
 *  qui ont besoin de plus (NOMENCLATURE_TYPE, OBSERVERS, MEDIA, MIN_MAX, RADIO, CHECKBOX…).
 *  Mapping de référence depuis le `type_widget` déclaré dans `/api/monitorings/config/<code>`
 *  fait dans [WidgetMapping.kt]. */
enum class ViewType {
    TEXT,
    TEXTAREA,
    NUMBER,
    DATE,
    /** Sélecteur d'heure (TimePickerDialog Material). Valeur retournée : "HH:MM". */
    TIME,
    /** Autocomplete TaxRef pour saisir un cd_nom. AutoCompleteTextView branché sur le
     *  cache local TaxRef ; la valeur retournée est le `cd_nom` (Int) résolu via
     *  TaxRefCache, ou null si le texte ne matche aucun taxon. */
    TAXON,
    SELECT,
    /** Choix unique rendu en boutons radio (widget serveur `radio`). Mêmes `values` qu'un
     *  SELECT mais affiché comme un groupe de boutons (horizontal si peu d'options courtes,
     *  ex. Oui/Non) plutôt qu'un Spinner. Valeur retournée = la `value` de l'option cochée. */
    RADIO,
    /** Multi-sélection : utilisé pour les datalists `multiple: true` (observers, dataset multi,
     *  types de sites) ainsi que pour les widgets serveur `multiselect`. Rendu via dialog
     *  cases à cocher, valeur retournée = List<String>. */
    SELECT_MULTIPLE,
    /** Booléen : widgets serveur `bool_checkbox` / `bool` / `checkbox`. Rendu en CheckBox
     *  Material, valeur retournée = Boolean. */
    CHECKBOX,
}

/** Valeur d'une option pour un widget SELECT (et plus tard NOMENCLATURE_TYPE / RADIO).
 *  [value] est la valeur technique envoyée à l'API, [label] est ce que voit l'utilisateur. */
data class PropertyValue(val value: String, val label: String)

/** Un champ éditable d'un formulaire dynamique. Construit à partir du schéma serveur
 *  ([fr.ariegenature.geonat.network.MonitoringApi.MonitoringSchemaObjet]) ou en dur pour les
 *  POC. Mutable sur [value] uniquement — les autres champs sont des métadonnées de schéma. */
data class EditableField(
    val code: String,
    val viewType: ViewType,
    val label: String,
    var value: Any? = null,
    val values: List<PropertyValue> = emptyList(),
    val obligatoire: Boolean = false,
    /** Texte d'aide affiché sous le champ (vient du `definition` du schéma serveur). */
    val aide: String? = null,
    /** Expression `hidden` du schéma serveur (forme Angular `${champ}` etc.). Évaluée
     *  par le renderer pour masquer/afficher dynamiquement selon les autres valeurs. */
    val hiddenExpr: String? = null,
    /** Pour les champs TAXON : id_liste UsersHub qui restreint les taxons proposés à
     *  l'autocomplete. Vient typiquement de `schema.idListTaxonomy` du protocole ou du
     *  `dataset.idTaxaList` rattaché. Null = toutes les espèces du cache TaxRef proposées. */
    val idListeTaxonomieRestreinte: Int? = null,
    /** Borne min (brute serveur, cf [fr.ariegenature.geonat.network.MonitoringApi
     *  .MonitoringPropertySchema.minValue]). Évaluée par [ValidationExpr] côté renderer pour
     *  afficher un message d'erreur sous le champ et bloquer le submit le cas échéant. */
    val minValue: String? = null,
    /** Borne max (cf [minValue]). */
    val maxValue: String? = null,
)