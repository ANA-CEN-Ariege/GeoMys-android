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
    /** id_habitat attribué par le SERVEUR (habitat lu depuis GeoNature). En MISE À JOUR d'une
     *  station (POST /stations/<id>/), le renvoyer fait mettre à jour l'habitat existant au lieu
     *  d'en créer un doublon — un habitat sans id est créé, un habitat omis est supprimé côté
     *  serveur. Null pour un habitat créé localement (jamais envoyé en création). */
    var idHabitatServeur: Int? = null,
    /** Identifiant SINP de l'habitat (`unique_id_sinp_hab`) tel que lu sur le serveur — renvoyé
     *  tel quel à la mise à jour pour que le serveur ne le régénère pas. Null en local. */
    var uuidHabitat: String? = null,
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
    /** Précision technique (`technical_precision`) — part HUMAINE seule : le bloc balisé
     *  « [ANA-EVAL] {json} [/ANA-EVAL] » du plugin QGIS ANA (occhab-qgis) en est EXTRAIT au
     *  parsing serveur (cf. [anaEvalJson]). */
    var precisionTechnique: String? = null,
    /** Contenu JSON NORMALISÉ du bloc ANA-EVAL porté par `technical_precision` côté serveur
     *  (champs métier ANA / Natura 2000, cf. [fr.ariegenature.geomys.util.AnaEval]). Extrait à
     *  l'import ([fr.ariegenature.geomys.network.OccHabApi.parserFeatureCollection]) et
     *  RE-FUSIONNÉ dans le payload à l'envoi (OccHabUpload) — le bloc survit ainsi aux
     *  rééditions. Null = pas de bloc (habitat local ou serveur sans bloc) : standard inchangé. */
    var anaEvalJson: String? = null,
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
    /** Identifiant SINP stable généré côté client (`unique_id_sinp_station`), envoyé dès la
     *  création. Sert d'ancre d'idempotence : le serveur OccHab n'imposant AUCUNE contrainte
     *  d'unicité (POST = toujours une insertion), c'est la seule protection contre un doublon —
     *  après un envoi INCERTAIN (requête émise mais réponse perdue), un ré-envoi interroge
     *  d'abord le serveur sur cet UUID pour ne pas recréer la station. Cf. [envoiIncertain]. */
    var uuidStation: String = UUID.randomUUID().toString(),
    /** id_station attribué par le serveur après un envoi réussi (ou pour une station lue
     *  depuis le serveur en consultation). null = jamais envoyée / créée localement. Quand il
     *  est connu, un (ré)envoi part en MISE À JOUR (POST /stations/<id>/), jamais en création. */
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
    /** Commentaire (`comment`) — part HUMAINE seule : le bloc « [ANA-EVAL] {json} [/ANA-EVAL] »
     *  du plugin QGIS ANA (occhab-qgis) en est EXTRAIT au parsing serveur (cf. [anaEvalJson]). */
    var comment: String? = null,
    /** Contenu JSON NORMALISÉ du bloc ANA-EVAL porté par `comment` côté serveur (champs métier
     *  ANA / Natura 2000, cf. [fr.ariegenature.geomys.util.AnaEval]). Extrait à l'import et
     *  RE-FUSIONNÉ dans le payload à l'envoi (OccHabUpload) — il survit ainsi au flux des
     *  détails de session, qui réécrit `comment` à chaque sauvegarde. Null = pas de bloc
     *  (station locale ou serveur sans bloc) : comportement standard strictement inchangé. */
    var anaEvalJson: String? = null,
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
    /** true dès qu'une station provient du serveur et non d'une saisie locale : affichée en
     *  consultation (gris) sur la carte, elle peut être IMPORTÉE dans la saisie courante pour
     *  modification — elle repartira alors en mise à jour grâce à [idStationServeur]. */
    var origineServeur: Boolean = false,
    /** Message du dernier échec d'envoi (humanisé). Null si jamais échoué / envoi réussi. */
    var derniereErreurEnvoi: String? = null,
    /** true après une tentative d'envoi au statut INCERTAIN (réseau coupé APRÈS l'émission de
     *  la requête : le serveur a peut-être créé la station). Le prochain envoi commence alors
     *  par une vérification d'existence côté serveur (anti-doublon, cf. [uuidStation]). Effacé
     *  dès qu'un envoi est confirmé, ou qu'un échec NET (rejet serveur) prouve la non-création. */
    var envoiIncertain: Boolean = false,
    /** [empreinteContenu] de la station TELLE QU'IMPORTÉE du serveur (ou telle qu'envoyée, pour
     *  une station remise en édition), détails de session fusionnés. Non null = la station
     *  n'entre dans « Mes stations » qu'à la PREMIÈRE modification réelle (demande terrain
     *  2026-08-27) : [fr.ariegenature.geomys.store.OccHabStore.upsertStation] ignore une station
     *  dont le contenu est encore identique à cette empreinte. Null pour une station créée
     *  localement (toujours persistée) ; effacé à l'envoi confirmé. */
    var empreinteOrigine: String? = null,
) {
    /**
     * Empreinte du CONTENU métier (ce qui part au serveur), HORS état d'envoi et HORS champs
     * DÉRIVÉS de la géométrie : surface auto et altitudes MNT sont recalculées à chaque
     * « Valider » avec des arrondis différents de ceux du serveur, et le centroïde d'un polygone
     * est recalculé localement — les inclure ferait passer une station intacte pour modifiée.
     * Les coordonnées sont renormalisées (Double.toString) pour ne pas dépendre du formatage du
     * JSON d'origine. Limite assumée : une correction MANUELLE des seules altitudes/surface n'est
     * pas vue comme une modification. Cf. [empreinteOrigine].
     */
    fun empreinteContenu(): String = buildString {
        append(geometryType).append('|')
        if (geometryType == "Polygon" && !geometryCoordsJson.isNullOrEmpty()) {
            append(coordsNormalisees(geometryCoordsJson))
        } else {
            append(latitude).append(',').append(longitude)
        }
        append('|').append(idDataset)
        append('|').append(observateursIds.sorted())
        append('|').append(observateursTxt.orEmpty().trim())
        append('|').append(stationName.orEmpty().trim())
        append('|').append(comment.orEmpty().trim())
        append('|').append(anaEvalJson.orEmpty().trim())
        append('|').append(dateMin).append('|').append(dateMax)
        append('|').append(profondeurMin).append('|').append(profondeurMax)
        append('|').append(precision)
        append('|').append(idNomExposition).append('|').append(idNomCalculSurface)
        append('|').append(idNomObjetGeographique).append('|').append(idNomTypeSol)
        append('|').append(idNomTypeMosaique)
        habitats.forEach { h ->
            append("|H:").append(h.idHabitatServeur).append(',').append(h.cdHab)
            append(',').append(h.nomCite.trim())
            append(',').append(h.determiner.orEmpty().trim())
            append(',').append(h.recouvrement)
            append(',').append(h.precisionTechnique.orEmpty().trim())
            append(',').append(h.anaEvalJson.orEmpty().trim())
            append(',').append(h.idNomTypeDetermination).append(',').append(h.idNomTechniqueCollecte)
            append(',').append(h.idNomAbondance).append(',').append(h.idNomSensibilite)
            append(',').append(h.idNomInteretCommunautaire)
        }
    }

    /** true si la station porte une géométrie exploitable : polygone avec sommets, ou point non
     *  nul. Faux pour une `OccHabStation()` vierge — ex. ViewModel reconstruit après la mort du
     *  process : les écrans habitats refusent alors de persister une station fantôme (0,0)
     *  (audit 2026-08-27). */
    fun geometrieDefinie(): Boolean = when (geometryType) {
        "Polygon" -> !geometryCoordsJson.isNullOrEmpty()
        else -> latitude != 0.0 || longitude != 0.0
    }

    private fun coordsNormalisees(json: String?): String = try {
        val arr = org.json.JSONArray(json)
        buildString {
            for (i in 0 until arr.length()) {
                val c = arr.getJSONArray(i)
                append(c.getDouble(0)).append(',').append(c.getDouble(1)).append(';')
            }
        }
    } catch (_: Exception) { json.orEmpty() }
}

/**
 * SAISIE OccHab = groupe de [stations] saisies dans une même session (tuile OccHab), sur le modèle
 * d'Occtax où une `Sortie` regroupe des `Observation`. Persistée d'un bloc dans « Mes stations »,
 * envoyée d'un bloc, et rééditable (ajouter / modifier ses stations).
 *
 * L'état d'envoi vit à DEUX niveaux : chaque [OccHabStation] garde le sien ([OccHabStation
 * .envoyeGeoNature]/uuidStation…) = anti-doublon PAR station (comme `Observation.envoyeeServeur`) ;
 * [envoyeGeoNature] au niveau saisie est DÉRIVÉ (vrai quand toutes les stations sont envoyées).
 */
data class OccHabSaisie(
    val id: String = UUID.randomUUID().toString(),
    /** Date de création locale (epoch millis) — tri + affichage de « Mes stations ». */
    val date: Long = System.currentTimeMillis(),
    var stations: List<OccHabStation> = emptyList(),
    /** true si TOUTES les stations de la saisie ont été envoyées (dérivé, recalculé au store). */
    var envoyeGeoNature: Boolean = false,
    /** Message du dernier échec d'envoi de la saisie (humanisé) — cadre rouge. */
    var derniereErreurEnvoi: String? = null,
)
