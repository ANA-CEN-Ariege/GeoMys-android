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

package fr.ariegenature.geomys.store

import com.google.gson.JsonParser

/**
 * Registre des champs de nomenclature standards d'Occtax (occurrence + dénombrement) et
 * pilotage de leur visibilité par la config serveur `settings.json` (sections
 * `nomenclature.information[]` / `nomenclature.counting[]`), récupérée via
 * `/api/gn_commons/t_mobile_apps` et mise en cache dans [GeoNatureConfig.settingsOcctaxJson].
 *
 * Aligné sur l'app mobile officielle (gn_mobile_occtax,
 * `features/settings/domain/{AppSettings,NomenclatureSettings,PropertySettings}.kt` et
 * `NomenclatureSettingsLocalDataSourceImpl`) :
 *  - le [REGISTRE] est la liste exhaustive des champs connus (source unique des libellés/codes
 *    de repli, auparavant dispersés dans les fragments) ;
 *  - une liste `information`/`counting` **non vide** côté serveur agit comme **whitelist
 *    ordonnée** (seuls les champs listés et `visible` sont affichés) ;
 *  - une liste **absente/vide** ⇒ tout le registre du niveau est affiché (cas du serveur ANA
 *    actuel, qui ne publie pas ces listes).
 *
 * La visibilité est globale (pas par taxon) ; le filtrage des VALEURS de nomenclature reste, lui,
 * par taxon via [NomenclatureCache.filtrerPourGroupes] (cf. [fr.ariegenature.geomys.ui.saisie.ChampsTaxon.groupesEtRegno]).
 *
 * NB : « piloté serveur » concerne la visibilité/ordre des champs CONNUS. Un champ standard
 * inédit nécessite toujours une entrée ici (+ sa clé d'upload dans GeoNatureUpload).
 */
object OcctaxFieldsConfig {

    enum class Niveau { INFORMATION, COUNTING }

    /**
     * Catalogue d'UN champ de nomenclature standard Occtax — source unique du mnémonique et de
     * tout ce qui s'y rattache (binding modèle, clé d'upload, libellés). Synchro, upload et écrans
     * de saisie en dérivent : un mnémonique n'apparaît donc qu'ici.
     *
     * @param code        mnémonique du type de nomenclature (ex. "METH_OBS").
     * @param label        libellé affiché au-dessus du spinner.
     * @param niveau       INFORMATION (occurrence) ou COUNTING (dénombrement).
     * @param svKey        nom du champ porteur côté modèle (`Observation`/`Denombrement`) ET clé
     *                     savedStateHandle échangée entre écrans (ex. "techniqueObs").
     * @param uploadKey    clé `id_nomenclature_*` du payload Occtax.
     * @param uploadLabels code interne → label minuscule stable, pour résoudre l'id_nomenclature
     *                     serveur à l'envoi (vide si la résolution se fait directement par id).
     * @param fallbackLabels/fallbackCodes  valeurs de repli (incluant l'entrée vide en tête)
     *                     utilisées seulement si la nomenclature n'est pas (encore) synchronisée.
     */
    data class OcctaxField(
        val code: String,
        val label: String,
        val niveau: Niveau,
        val svKey: String,
        val uploadKey: String,
        val uploadLabels: Map<String, String>,
        val fallbackLabels: List<String>,
        val fallbackCodes: List<String>,
    )

    val REGISTRE: List<OcctaxField> = listOf(
        OcctaxField(
            "STATUT_OBS", "Statut d'observation", Niveau.INFORMATION,
            svKey = "statutObs", uploadKey = "id_nomenclature_observation_status",
            uploadLabels = emptyMap(),
            fallbackLabels = listOf("Non renseigné", "Présent", "Non observé", "Présence probable", "Non recherché"),
            fallbackCodes = listOf("", "1", "2", "3", "4"),
        ),
        OcctaxField(
            "METH_OBS", "Technique d'observation", Niveau.INFORMATION,
            svKey = "techniqueObs", uploadKey = "id_nomenclature_obs_technique",
            uploadLabels = mapOf("0" to "vu", "1" to "entendu", "2" to "vu et entendu", "4" to "chant", "5" to "indices de présence"),
            fallbackLabels = listOf("Non renseignée", "Vu", "Entendu", "Vu et entendu", "Chant", "Indices de présence"),
            fallbackCodes = listOf("", "0", "1", "2", "4", "5"),
        ),
        OcctaxField(
            "ETA_BIO", "État biologique", Niveau.INFORMATION,
            svKey = "etaBio", uploadKey = "id_nomenclature_bio_condition",
            uploadLabels = mapOf("1" to "vivant", "2" to "mort", "3" to "signe d'activité"),
            fallbackLabels = listOf("Non renseigné", "Vivant", "Mort", "Signe d'activité"),
            fallbackCodes = listOf("", "1", "2", "3"),
        ),
        OcctaxField(
            "OCC_COMPORTEMENT", "Comportement", Niveau.INFORMATION,
            svKey = "comportement", uploadKey = "id_nomenclature_behaviour",
            uploadLabels = mapOf(
                "1" to "chant", "2" to "chasse/alimentation", "3" to "repos", "4" to "déplacement",
                "5" to "passage en vol", "6" to "migration", "7" to "halte migratoire", "8" to "hivernage",
                "9" to "nourrissage des jeunes", "10" to "territorial", "11" to "accouplement",
                "12" to "30 - nidification possible", "13" to "40 - nidification probable",
                "14" to "50 - nidification certaine", "15" to "inconnu",
            ),
            fallbackLabels = listOf("Non renseigné", "Chant", "Chasse / Alimentation", "Repos", "Déplacement",
                "Passage en vol", "Migration", "Halte migratoire", "Hivernage",
                "Nourrissage des jeunes", "Territorial", "Accouplement",
                "Nidification possible", "Nidification probable", "Nidification certaine", "Inconnu"),
            fallbackCodes = listOf("", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"),
        ),
        OcctaxField(
            "STATUT_BIO", "Statut biologique", Niveau.INFORMATION,
            svKey = "statutBio", uploadKey = "id_nomenclature_bio_status",
            uploadLabels = mapOf("1" to "reproduction", "2" to "pas de reproduction",
                "3" to "hibernation", "4" to "estivation", "5" to "non déterminé", "6" to "inconnu"),
            fallbackLabels = listOf("Non renseigné", "Reproduction", "Pas de reproduction", "Hivernation", "Estivation", "Non déterminé", "Inconnu"),
            fallbackCodes = listOf("", "1", "2", "3", "4", "5", "6"),
        ),
        OcctaxField(
            "METH_DETERMIN", "Méthode de détermination", Niveau.INFORMATION,
            svKey = "methDetermin", uploadKey = "id_nomenclature_determination_method",
            uploadLabels = mapOf(
                "1" to "examen visuel à distance", "2" to "examen auditif direct",
                "3" to "examen visuel sur photo ou vidéo", "4" to "examen auditif avec transformation électronique",
                "5" to "examen visuel de l'individu en main", "6" to "autre méthode de détermination",
            ),
            fallbackLabels = listOf("Non renseignée", "Visuel à distance", "Auditif direct", "Photo ou vidéo",
                "Auditif avec transformation électronique", "Individu en main", "Autre méthode"),
            fallbackCodes = listOf("", "1", "2", "3", "4", "5", "6"),
        ),
        OcctaxField(
            "PREUVE_EXIST", "Preuve d'existence", Niveau.INFORMATION,
            svKey = "preuveExist", uploadKey = "id_nomenclature_exist_proof",
            uploadLabels = mapOf("0" to "non", "1" to "oui", "2" to "non acquise", "3" to "inconnu"),
            fallbackLabels = listOf("Non renseignée", "Non", "Oui", "Non acquise", "Inconnu"),
            fallbackCodes = listOf("", "0", "1", "2", "3"),
        ),
        OcctaxField(
            "NATURALITE", "Naturalité", Niveau.INFORMATION,
            svKey = "naturalite", uploadKey = "id_nomenclature_naturalness",
            uploadLabels = emptyMap(),
            fallbackLabels = listOf("Non renseigné", "Cultivé/élevé", "Féral", "Inconnu", "Sauvage", "Subspontané"),
            fallbackCodes = listOf("", "1", "2", "3", "4", "5"),
        ),
        OcctaxField(
            "SEXE", "Sexe", Niveau.COUNTING,
            svKey = "sexe", uploadKey = "id_nomenclature_sex",
            uploadLabels = mapOf("1" to "mâle", "2" to "femelle", "5" to "indéterminé"),
            fallbackLabels = listOf("Non renseigné", "Mâle", "Femelle", "Indéterminé"),
            fallbackCodes = listOf("", "1", "2", "5"),
        ),
        OcctaxField(
            "STADE_VIE", "Stade de vie", Niveau.COUNTING,
            svKey = "stadeVie", uploadKey = "id_nomenclature_life_stage",
            uploadLabels = mapOf("2" to "adulte", "3" to "juvénile", "4" to "immature"),
            fallbackLabels = listOf("Non renseigné", "Adulte", "Juvénile", "Immature"),
            fallbackCodes = listOf("", "2", "3", "4"),
        ),
        OcctaxField(
            "OBJ_DENBR", "Objet du dénombrement", Niveau.COUNTING,
            svKey = "objDenbr", uploadKey = "id_nomenclature_obj_count",
            uploadLabels = mapOf("1" to "individu", "2" to "couple", "3" to "nid", "4" to "famille", "5" to "groupe"),
            fallbackLabels = listOf("Non renseigné", "Individu", "Couple", "Nid", "Famille", "Groupe"),
            fallbackCodes = listOf("", "1", "2", "3", "4", "5"),
        ),
        OcctaxField(
            "TYP_DENBR", "Type de dénombrement", Niveau.COUNTING,
            svKey = "typDenbr", uploadKey = "id_nomenclature_type_count",
            uploadLabels = mapOf("1" to "exact", "2" to "estimé", "3" to "minimum", "4" to "maximum"),
            fallbackLabels = listOf("Non renseigné", "Exact", "Estimé", "Minimum", "Maximum"),
            fallbackCodes = listOf("", "1", "2", "3", "4"),
        ),
    )

    /** Index par mnémonique, pour les écrans/upload qui résolvent un champ depuis son code. */
    val parCode: Map<String, OcctaxField> = REGISTRE.associateBy { it.code }

    /** Mnémoniques de nomenclature de saisie à synchroniser (cf. GeoNatureSync). N'inclut pas
     *  TYPE_MEDIA, qui n'est pas un champ de saisie. */
    fun mnemoniques(): List<String> = REGISTRE.map { it.code }

    private fun registrePour(niveau: Niveau): List<OcctaxField> = REGISTRE.filter { it.niveau == niveau }

    /** Une entrée de config serveur : mnémonique + visibilité. */
    private data class PropertySettings(val key: String, val visible: Boolean)

    /**
     * Champs à afficher pour [niveau], dans l'ordre, d'après la config serveur [settingsJson]
     * (le JSON de l'objet `nomenclature` du settings.json, tel que mis en cache). Liste serveur
     * vide/absente ⇒ tout le registre du niveau.
     */
    fun champsVisibles(settingsJson: String, niveau: Niveau): List<OcctaxField> {
        val cle = if (niveau == Niveau.INFORMATION) "information" else "counting"
        val props = parserListe(settingsJson, cle)
        val registre = registrePour(niveau)
        if (props.isEmpty()) return registre
        val parCode = registre.associateBy { it.code }
        return props.filter { it.visible }.mapNotNull { parCode[it.key] }
    }

    /**
     * Parse, de façon tolérante, une liste `nomenclature.<cle>` du settings.json. Chaque entrée
     * est soit une chaîne ("METH_OBS"), soit un objet ({"key":"METH_OBS","visible":true}).
     * Défaut `visible = true` (comme l'app officielle). Renvoie liste vide si absent/illisible.
     */
    private fun parserListe(settingsJson: String, cle: String): List<PropertySettings> {
        if (settingsJson.isBlank()) return emptyList()
        return try {
            val racine = JsonParser.parseString(settingsJson)
            if (!racine.isJsonObject) return emptyList()
            // Le JSON caché peut être soit l'objet `nomenclature` directement, soit le settings
            // complet contenant `nomenclature`. On gère les deux.
            val obj = racine.asJsonObject
            val nomenclature = when {
                obj.has(cle) -> obj
                obj.has("nomenclature") && obj.get("nomenclature").isJsonObject -> obj.getAsJsonObject("nomenclature")
                else -> return emptyList()
            }
            val arr = nomenclature.getAsJsonArray(cle) ?: return emptyList()
            arr.mapNotNull { el ->
                when {
                    el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                        PropertySettings(el.asString, visible = true)
                    el.isJsonObject -> {
                        val o = el.asJsonObject
                        val key = o.get("key")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                        val visible = o.get("visible")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
                        PropertySettings(key, visible)
                    }
                    else -> null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
