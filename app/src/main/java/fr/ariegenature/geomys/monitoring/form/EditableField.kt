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

package fr.ariegenature.geomys.monitoring.form

/** Type de widget d'un champ de formulaire dynamique gn_module_monitoring.
 *  POC volontairement minimaliste — on étend au fil de l'eau quand on rencontre des protocoles
 *  qui ont besoin de plus (NOMENCLATURE_TYPE, OBSERVERS, MEDIA, RADIO, CHECKBOX…).
 *  Mapping de référence depuis le `type_widget` déclaré dans `/api/monitorings/config/<code>`
 *  fait dans [WidgetMapping.kt]. */
enum class ViewType {
    TEXT,
    TEXTAREA,
    NUMBER,
    DATE,
    /** Sélecteur d'heure (TimePickerDialog Material). Valeur retournée : "HH:MM". */
    TIME,
    /** Date + heure (widget serveur `datetime`). Enchaîne un DatePickerDialog puis un
     *  TimePickerDialog. Valeur retournée : "yyyy-MM-dd HH:mm:ss" (format accepté par le
     *  backend GeoNature, cf. formatDateTime de gn_mobile_monitoring). */
    DATETIME,
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
    /** Pièce jointe média (photo) — widget serveur `medias`. Rendu : bouton "Ajouter une
     *  photo" + nom du fichier sélectionné + bouton ✕. La valeur retournée par lireValeurs
     *  est l'URI String du fichier copié localement (ou null si rien sélectionné). L'upload
     *  effectif vers /api/gn_commons/media a lieu à l'envoi serveur côté OutboxEnvoi, après
     *  création de l'objet parent (le serveur attend uuid_attached_row pour le rattacher). */
    MEDIA,
}

/** Valeur d'une option pour un widget SELECT (et plus tard NOMENCLATURE_TYPE / RADIO).
 *  [value] est la valeur technique envoyée à l'API, [label] est ce que voit l'utilisateur. */
data class PropertyValue(
    val value: String,
    val label: String,
    /** `cd_nomenclature` de l'option (datalists nomenclature uniquement, null sinon).
     *  Exposé au moteur d'expressions sous `<code>__cd` pour évaluer les conditions
     *  `meta.nomenclatures[value.X].cd_nomenclature == '…'` des schémas (loutre, blaireau…). */
    val cdNomenclature: String? = null,
)

/** Un champ éditable d'un formulaire dynamique. Construit à partir du schéma serveur
 *  ([fr.ariegenature.geomys.network.MonitoringApi.MonitoringSchemaObjet]) ou en dur pour les
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
    /** Expression `required` DYNAMIQUE du schéma (`({value}) => …`) : le champ n'est
     *  obligatoire que lorsque l'expression est vraie au regard des valeurs courantes
     *  (ex. champs végétation requis seulement au passage 2). Complète [obligatoire]. */
    val obligatoireExpr: String? = null,
    /** Pour les champs TAXON : id_liste UsersHub qui restreint les taxons proposés à
     *  l'autocomplete. Vient typiquement de `schema.idListTaxonomy` du protocole ou du
     *  `dataset.idTaxaList` rattaché. Null = toutes les espèces du cache TaxRef proposées. */
    val idListeTaxonomieRestreinte: Int? = null,
    /** Borne min (brute serveur, cf [fr.ariegenature.geomys.network.MonitoringApi
     *  .MonitoringPropertySchema.minValue]). Évaluée par [ValidationExpr] côté renderer pour
     *  afficher un message d'erreur sous le champ et bloquer le submit le cas échéant. */
    val minValue: String? = null,
    /** Borne max (cf [minValue]). */
    val maxValue: String? = null,
    /** Pour les widgets MEDIA : table Postgres à laquelle le média est rattaché côté
     *  gn_commons.t_medias (ex. `gn_monitoring.t_base_visits` pour le widget medias d'une
     *  visite). Lu depuis `schema_dot_table` du schéma serveur, résolu en id_table_location
     *  via /api/gn_commons/get_id_table_location/<schema_dot_table> au moment de l'upload. */
    val schemaDotTable: String? = null,
    /** Masque le champ dans le formulaire tout en conservant sa valeur (pré-sélectionnée via
     *  [value]) dans le payload. Utilisé quand une datalist n'expose qu'une seule option
     *  (ex. un protocole rattaché à un unique jeu de données) : un sélecteur à choix unique
     *  est inutile, on auto-sélectionne et on masque. [lireValeurs] collecte quand même la
     *  valeur (il itère toutes les vues, pas seulement les visibles). */
    val masque: Boolean = false,
)