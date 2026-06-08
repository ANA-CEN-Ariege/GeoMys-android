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

package fr.ariegenature.geomys.model

import java.util.Date
import java.util.UUID

enum class Taxon { OISEAU, MAMMIFERE, REPTILE, BATRACIEN, POISSON, INSECTE, FONGE, MOLLUSQUE, INVERTEBRES, PLANTE }

data class PointTrace(
    val latitude: Double,
    val longitude: Double
)

/** Un dénombrement (counting) GeoNature : 1 occurrence peut en avoir plusieurs.
 *  Chacun a sa propre tranche d'effectif + caractéristiques (sexe, stade…) + médias attachés. */
data class Denombrement(
    val id: String = UUID.randomUUID().toString(),
    var nombreMin: Int = 1,
    var nombreMax: Int = 1,
    var sexe: String? = null,
    var stadeVie: String? = null,
    var objDenbr: String? = null,
    var typDenbr: String? = null,
    /** URIs locales (file:///…) des photos attachées à ce counting. Uploadées à l'envoi
     *  via POST /api/gn_commons/media, puis référencées dans le JSON du counting. */
    var mediaUris: List<String> = emptyList(),
    /** Champs additionnels OCCTAX_DENOMBREMENT (configurables côté instance GN). */
    var additionalFields: Map<String, String> = emptyMap(),
)

data class Observation(
    val id: String = UUID.randomUUID().toString(),
    var espece: String,
    var taxon: Taxon? = null,   // null = OISEAU (rétrocompatibilité JSON existant)
    var cdNom: Int? = null,
    var latitude: Double,
    var longitude: Double,
    /** Type de la géométrie associée au relevé (partagée par toutes les obs d'un releveId).
     *  Valeurs : `"Point"` (défaut historique, latitude/longitude suffisent), `"LineString"`,
     *  `"Polygon"`. Null pour rétrocompatibilité JSON ancien (= Point). */
    var geometryType: String? = null,
    /** Coordonnées de la géométrie sérialisées en JSON (`List<DoubleArray>` au format
     *  [lon, lat]). Renseigné uniquement pour LineString et Polygon. Pour Point,
     *  null et latitude/longitude font foi. Toutes les obs d'un releveId partagent cette
     *  valeur (OCCTAX : un relevé = une géométrie + N occurrences). */
    var geometryCoordsJson: String? = null,
    var date: Long = System.currentTimeMillis(),
    var notes: String = "",
    // ── Champs du counting #0 (conservés en flat pour rétrocompat Gson + mono-taxon) ──
    var nombre: Int = 1,
    var sexe: String? = null,
    var stadeVie: String? = null,
    var objDenbr: String? = null,
    var typDenbr: String? = null,
    // ── Caractérisation de l'occurrence ──
    var techniqueObs: String? = null,
    var statutBio: String? = null,
    var etaBio: String? = null,
    var preuveExist: String? = null,
    var comportement: String? = null,
    var methDetermin: String? = null,
    var naturalite: String? = null,
    var determinateur: String? = null,
    // UUID partagé par les obs d'une même saisie multi-taxons : à l'envoi, elles seront
    // regroupées dans un seul relevé GeoNature (1 relevé = 1 point + N occurrences).
    // null pour les obs mono-taxon (saisie rapide, import GPX) → chacune devient son propre relevé.
    var releveId: String? = null,
    // ── Extras counting #0 (mode multi-taxon) ──
    /** Si null, le counting #0 utilise `nombre` pour count_min ET count_max. */
    var nombreMax: Int? = null,
    // ── Dénombrements supplémentaires (mode multi-taxon : countings 1..N) ──
    /** Le counting #0 est représenté par les champs flat ci-dessus ; cette liste
     *  contient les dénombrements supplémentaires éventuels. À l'envoi GeoNature,
     *  un cor_counting_occtax est émis pour chaque (counting #0 + entrées de cette liste). */
    var denombrementsAdditionnels: List<Denombrement> = emptyList(),
    // ── Statut d'observation ──
    var statutObs: String? = null,
    /** Photos attachées au counting #0 (URIs locales). Les countings additionnels portent
     *  leurs photos via Denombrement.mediaUris. */
    var mediaUrisCounting0: List<String> = emptyList(),
    // ── Champs additionnels (config gn_commons.t_additional_fields, dynamiques par instance) ──
    /** Champs additionnels du relevé (OCCTAX_RELEVE) — partagés entre toutes les obs d'un même releveId. */
    var additionalFieldsReleve: Map<String, String> = emptyMap(),
    /** Champs OCCURRENCE pilotés par form_fields du serveur (statut de la source, floutage, preuve
     *  numérique / non numérique). Clé = clé form_fields ; valeur = code nomenclature ou texte.
     *  Map unique pour ne pas multiplier les champs à travers tout le code. */
    var champsOccExtra: Map<String, String> = emptyMap(),
    /** Champs additionnels de l'occurrence (OCCTAX_OCCURRENCE). */
    var additionalFieldsOccurrence: Map<String, String> = emptyMap(),
    /** Champs additionnels OCCTAX_DENOMBREMENT pour le counting #0. */
    var additionalFieldsCounting0: Map<String, String> = emptyMap(),
    // ── Override par relevé (édités via « Détails du relevé »), partagés entre toutes les obs
    //    d'un même releveId. null = on retombe sur les valeurs par défaut de la config / du login. ──
    /** Jeu de données du relevé. null → `config.idDataset`. */
    var idDatasetReleve: Int? = null,
    /** Observateurs du relevé (→ `observers`, tableau d'id_role). Vide → utilisateur connecté. */
    var observateursReleveIds: List<Int> = emptyList(),
    /** Libellés des observateurs du relevé (affichage), alignés sur [observateursReleveIds]. */
    var observateursReleveNoms: List<String> = emptyList(),
    /** Observateur unique du relevé — legacy (saisies d'avant le multi-observateurs). Lu en repli
     *  si [observateursReleveIds] est vide ; plus jamais écrit. */
    var observateurReleveId: Int? = null,
    /** Libellé de l'observateur du relevé — legacy (cf. [observateurReleveId]). */
    var observateurReleveNom: String? = null,
    /** Commentaire libre du relevé (→ `comment` des properties). null/"" = non renseigné. */
    var commentReleve: String? = null,
    /** Habitat du relevé — code HABREF (→ `cd_hab`). null = non renseigné. */
    var cdHabReleve: Int? = null,
    /** Libellé de l'habitat (affichage), aligné sur [cdHabReleve]. */
    var habitatReleveLabel: String? = null,
    /** Type de regroupement du relevé (nomenclature TYP_GRP). null/"" = non renseigné. */
    var typGrpReleve: String? = null,
    /** Date+heure de DÉBUT du relevé (epoch millis), saisie dans « Détails ». null = on utilise les
     *  dates de saisie des obs (→ `date_min`/`hour_min`). */
    var dateDebutReleve: Long? = null,
    /** Date+heure de FIN du relevé (epoch millis). null = même que le début (→ `date_max`/`hour_max`). */
    var dateFinReleve: Long? = null,
    /** Champs relevé supplémentaires pilotés par `form_fields` du serveur (altitude_min/max,
     *  depth_min/max, place_name, precision, grp_method, geo_object_nature). Clé = clé `form_fields`,
     *  valeur = saisie en texte (entier pour les numériques, code mnémonique pour geo_object_nature).
     *  Vide = aucun renseigné. Map unique pour ne pas multiplier les champs à travers tout le code. */
    var champsReleveExtra: Map<String, String> = emptyMap(),
)

data class Sortie(
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    var pointsParcours: List<PointTrace> = emptyList(),
    var observations: List<Observation> = emptyList(),
    var distanceTotale: Double = 0.0,
    var envoyeGeoNature: Boolean = false,
    var estImportee: Boolean = false,
    /** Message du dernier ÉCHEC d'envoi GeoNature (humanisé). Null si jamais échoué ou si
     *  l'envoi a fini par réussir. Sert au marquage visuel (cadre rouge) dans « Mes saisies »
     *  pour qu'un échec ne passe pas inaperçu une fois le dialog d'erreur fermé. */
    var derniereErreurEnvoi: String? = null,
)