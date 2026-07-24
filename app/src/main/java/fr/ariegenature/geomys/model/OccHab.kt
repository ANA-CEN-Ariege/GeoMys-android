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

import java.util.UUID

/**
 * Module OccHab (relevés d'habitats) : une STATION géolocalisée qui porte 1..N HABITATS
 * (codes HABREF). Modèle FIXE côté serveur (gn_module_occhab) — cf. le module Occtax pour
 * le patron (store local dédié + upload monolithique), pas le moteur schema-driven du
 * Monitoring. Payload d'envoi = un Feature GeoJSON (POST /api/occhab/stations/).
 *
 * Les champs de nomenclature sont stockés en `id_nomenclature` (Int) tel que l'API OccHab
 * les attend (`id_nomenclature_*`), avec le libellé associé pour l'affichage. Tous les champs
 * ont un défaut (Gson instancie sans constructeur → un JSON plus ancien que le champ le laisse
 * à sa valeur par défaut, cf. la normalisation de [fr.ariegenature.geomys.store.OccHabStore]).
 */
data class OccHabHabitat(
    val id: String = UUID.randomUUID().toString(),
    /** Code HABREF (obligatoire côté serveur). 0 = non renseigné (bloque l'envoi). */
    var cdHab: Int = 0,
    /** Libellé HABREF (affichage) aligné sur [cdHab]. */
    var habitatLabel: String = "",
    /** Nom cité de l'habitat (`nom_cite`, obligatoire). Défaut = libellé HABREF si vide. */
    var nomCite: String = "",
    /** Déterminateur (`determiner`). */
    var determiner: String? = null,
    /** Pourcentage de recouvrement (`recovery_percentage`). */
    var recouvrement: Double? = null,
    /** Précision technique (`technical_precision`). */
    var precisionTechnique: String? = null,
    // ── Nomenclatures habitat (id_nomenclature ; null = non renseigné) ──
    /** Type de détermination (`id_nomenclature_determination_type`). */
    var idNomTypeDetermination: Int? = null,
    /** Technique de collecte (`id_nomenclature_collection_technique`) — OBLIGATOIRE côté
     *  serveur, a un défaut serveur (`In situ`) appliqué si null à l'envoi. */
    var idNomTechniqueCollecte: Int? = null,
    /** Abondance (`id_nomenclature_abundance`). */
    var idNomAbondance: Int? = null,
    /** Sensibilité (`id_nomenclature_sensitivity` — attention, colonne DB mal orthographiée
     *  côté serveur, mais la clé JSON reste `id_nomenclature_sensitivity`). */
    var idNomSensibilite: Int? = null,
    /** Intérêt communautaire (`id_nomenclature_community_interest`). */
    var idNomInteretCommunautaire: Int? = null,
)

data class OccHabStation(
    val id: String = UUID.randomUUID().toString(),
    /** id_station attribué par le serveur après un envoi réussi (ou pour une station lue
     *  depuis le serveur en consultation). null = jamais envoyée / créée localement. */
    var idStationServeur: Int? = null,
    /** Date de création locale (epoch millis) — sert au tri de « Mes stations ». */
    val date: Long = System.currentTimeMillis(),
    // ── Géométrie (Point ou Polygon, en 4326) ──
    /** `"Point"` (défaut) ou `"Polygon"`. Le module OccHab n'utilise pas la ligne. */
    var geometryType: String = "Point",
    /** Latitude du point, ou centroïde du polygone (affichage carte). */
    var latitude: Double = 0.0,
    /** Longitude du point, ou centroïde du polygone (affichage carte). */
    var longitude: Double = 0.0,
    /** Sommets du polygone sérialisés en JSON (`List<DoubleArray>` [lon, lat]). Null pour un
     *  Point (latitude/longitude font foi). Même convention que [Observation.geometryCoordsJson]. */
    var geometryCoordsJson: String? = null,
    // ── Champs station ──
    /** Jeu de données (`id_dataset`, obligatoire). null → défaut de config. */
    var idDataset: Int? = null,
    /** Observateurs (`observers`, tableau d'`id_role`). Vide → utilisateur connecté. */
    var observateursIds: List<Int> = emptyList(),
    /** Libellés des observateurs (affichage), alignés sur [observateursIds]. */
    var observateursNoms: List<String> = emptyList(),
    /** Observateurs en texte libre (`observers_txt`) — utilisé si le serveur impose
     *  `OCCHAB.OBSERVER_AS_TXT`. null/"" = non renseigné. */
    var observateursTxt: String? = null,
    /** Nom de la station (`station_name`). */
    var stationName: String? = null,
    /** Commentaire (`comment`). */
    var comment: String? = null,
    /** Date+heure de début du relevé (→ `date_min`). null = [date]. */
    var dateMin: Long? = null,
    /** Date+heure de fin du relevé (→ `date_max`). null = même que le début. */
    var dateMax: Long? = null,
    /** Altitude min/max (`altitude_min`/`altitude_max`). */
    var altitudeMin: Int? = null,
    var altitudeMax: Int? = null,
    /** Profondeur min/max (`depth_min`/`depth_max`). */
    var profondeurMin: Int? = null,
    var profondeurMax: Int? = null,
    /** Surface en m² (`area`). */
    var surface: Long? = null,
    /** Précision de localisation en mètres (`precision`). */
    var precision: Int? = null,
    // ── Nomenclatures station (id_nomenclature ; null = non renseigné) ──
    /** Exposition (`id_nomenclature_exposure`). */
    var idNomExposition: Int? = null,
    /** Méthode de calcul de la surface (`id_nomenclature_area_surface_calculation`). */
    var idNomCalculSurface: Int? = null,
    /** Type d'objet géographique (`id_nomenclature_geographic_object`, a un défaut serveur). */
    var idNomObjetGeographique: Int? = null,
    /** Type de sol (`id_nomenclature_type_sol`, a un défaut serveur). */
    var idNomTypeSol: Int? = null,
    /** Type de mosaïque d'habitat (`id_nomenclature_type_mosaique_habitat`). */
    var idNomTypeMosaique: Int? = null,
    // ── Habitats de la station (1..N) ──
    var habitats: List<OccHabHabitat> = emptyList(),
    // ── État d'envoi (mêmes sémantiques que Sortie) ──
    var envoyeGeoNature: Boolean = false,
    /** true dès qu'une station provient du serveur (consultation lecture seule) et non d'une
     *  saisie locale — elle n'est ni rééditable ni renvoyable dans le MVP. */
    var origineServeur: Boolean = false,
    /** Message du dernier échec d'envoi (humanisé). Null si jamais échoué / envoi réussi. */
    var derniereErreurEnvoi: String? = null,
)
