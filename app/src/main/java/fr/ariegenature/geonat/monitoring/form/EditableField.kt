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
    SELECT,
    /** Multi-sélection : utilisé pour les datalists `multiple: true` (observers, dataset multi,
     *  types de sites). Rendu via dialog cases à cocher, valeur retournée = List<String>. */
    SELECT_MULTIPLE,
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
)