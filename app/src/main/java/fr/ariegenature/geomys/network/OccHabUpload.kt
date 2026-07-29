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

package fr.ariegenature.geomys.network

import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Résultat de l'envoi d'une station OccHab (`OccHabUpload.envoyer`). */
data class OccHabEnvoiResult(
    /** id_station attribué par le serveur (extrait de la réponse GeoJSON). null si non retrouvé. */
    val idStationServeur: Int?,
    /** Nombre d'habitats effectivement envoyés (ceux avec un cd_hab renseigné). */
    val nbHabitats: Int,
    /** true si la station existait DÉJÀ côté serveur (créée lors d'une tentative précédente au
     *  statut incertain) : aucun POST n'a été refait — anti-doublon. */
    val dejaPresente: Boolean = false,
)

/**
 * Envoi d'une station OccHab (station + habitats) vers `POST /api/occhab/stations/`.
 *
 * Contrairement à Occtax (relevé puis N occurrences + médias), OccHab envoie TOUT en un seul
 * POST : le corps est un Feature GeoJSON dont les `properties` portent les champs de la station,
 * les `observers` (tableau d'`{id_role}`) et les `habitats` imbriqués. Les nomenclatures sont
 * envoyées en `id_nomenclature_*` (le modèle les stocke déjà ainsi). Pas de médias au MVP.
 */
object OccHabUpload {

    suspend fun envoyer(station: OccHabStation, config: GeoNatureConfig): OccHabEnvoiResult =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, idRole, cookies) =
                GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                    ?: throw GNErreur.AuthEchouee(401)

            // Jeu de données : celui de la station (choisi à la saisie) sinon le défaut de config.
            val datasetId = station.idDataset?.takeIf { it > 0 }
                ?: config.idDataset.trim().toIntOrNull()?.takeIf { it > 0 }
                ?: throw GNErreur.EnvoiEchoue(0, "id_dataset invalide")
            if (!config.datasetAcceptablePourEnvoi(datasetId)) throw GNErreur.DatasetInvalide(datasetId)

            // Habitats OPTIONNELS : une station peut être envoyée sans aucun habitat — le serveur
            // l'accepte (`habitats` n'est pas `required` dans StationSchema, `validate_habitats` ne
            // contrôle que l'appartenance ; parité web où l'on ajoute les habitats après). On ne
            // garde que ceux portant un code HABREF valide (les incomplets sont ignorés) ; le
            // tableau `habitats` peut donc être vide.
            val habitatsValides = station.habitats.filter { it.cdHab > 0 }

            // Anti-doublon : une tentative précédente s'est soldée par un statut INCERTAIN (réseau
            // coupé après l'émission) → la station a peut-être déjà été créée. Le serveur OccHab
            // n'imposant aucune contrainte d'unicité, on interroge par notre UUID stable AVANT de
            // re-POSTer. Trouvée → on renvoie son id sans rien recréer. Absente / serveur muet →
            // on procède au POST (si le serveur est injoignable, rien n'a pu être créé).
            if (station.envoiIncertain && (station.idStationServeur ?: 0) <= 0) {
                verifierStationExistante(base, token, cookies, station.uuidStation)?.let { idExistant ->
                    return@withContext OccHabEnvoiResult(
                        idStationServeur = idExistant,
                        nbHabitats = habitatsValides.size,
                        dejaPresente = true,
                    )
                }
            }

            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dateMin = Date(station.dateMin ?: station.date)
            val dateMax = Date(station.dateMax ?: station.dateMin ?: station.date)

            // Géométrie : mutualisée avec Occtax (Point / Polygon, [lon,lat], fermeture d'anneau).
            val geometry = GeoNatureUpload.construireGeometrie(
                type = station.geometryType,
                coordsJson = station.geometryCoordsJson,
                lat = station.latitude,
                lon = station.longitude,
            )

            // Observateurs : tableau d'{id_role}. Vide → utilisateur connecté (id_digitiser reste
            // posé côté serveur). observers_txt renseigné en plus si la station en porte un.
            val observateurs = station.observateursIds.filter { it > 0 }
                .ifEmpty { listOfNotNull(idRole) }
            val observersArr = JSONArray().apply {
                observateurs.forEach { put(JSONObject().put("id_role", it)) }
            }

            val habitatsArr = JSONArray()
            habitatsValides.forEach { h ->
                habitatsArr.put(JSONObject().apply {
                    put("cd_hab", h.cdHab)
                    // nom_cite obligatoire : repli sur le libellé HABREF si l'utilisateur ne l'a pas édité.
                    put("nom_cite", h.nomCite.ifBlank { h.habitatLabel }.ifBlank { "Habitat ${h.cdHab}" })
                    h.determiner?.takeIf { it.isNotBlank() }?.let { put("determiner", it) }
                    h.recouvrement?.let { put("recovery_percentage", it) }
                    h.precisionTechnique?.takeIf { it.isNotBlank() }?.let { put("technical_precision", it) }
                    h.idNomTypeDetermination?.let { put("id_nomenclature_determination_type", it) }
                    // collection_technique : obligatoire côté serveur mais a un défaut serveur —
                    // on l'omet si non renseigné pour laisser le serveur appliquer son défaut.
                    h.idNomTechniqueCollecte?.let { put("id_nomenclature_collection_technique", it) }
                    h.idNomAbondance?.let { put("id_nomenclature_abundance", it) }
                    h.idNomSensibilite?.let { put("id_nomenclature_sensitivity", it) }
                    h.idNomInteretCommunautaire?.let { put("id_nomenclature_community_interest", it) }
                })
            }

            val properties = JSONObject().apply {
                put("id_dataset", datasetId)
                // UUID SINP stable côté client : ancre d'idempotence pour la vérification
                // d'existence d'un ré-envoi (cf. verifierStationExistante). Le serveur le persiste
                // tel quel (colonne unique_id_sinp_station) au lieu d'en générer un.
                station.uuidStation.takeIf { it.isNotBlank() }?.let { put("unique_id_sinp_station", it) }
                put("date_min", dateFmt.format(dateMin))
                put("date_max", dateFmt.format(dateMax))
                station.stationName?.takeIf { it.isNotBlank() }?.let { put("station_name", it) }
                station.comment?.takeIf { it.isNotBlank() }?.let { put("comment", it) }
                station.altitudeMin?.let { put("altitude_min", it) }
                station.altitudeMax?.let { put("altitude_max", it) }
                station.profondeurMin?.let { put("depth_min", it) }
                station.profondeurMax?.let { put("depth_max", it) }
                station.surface?.let { put("area", it) }
                station.precision?.let { put("precision", it) }
                station.idNomExposition?.let { put("id_nomenclature_exposure", it) }
                station.idNomCalculSurface?.let { put("id_nomenclature_area_surface_calculation", it) }
                station.idNomObjetGeographique?.let { put("id_nomenclature_geographic_object", it) }
                station.idNomTypeSol?.let { put("id_nomenclature_type_sol", it) }
                station.idNomTypeMosaique?.let { put("id_nomenclature_type_mosaique_habitat", it) }
                station.observateursTxt?.takeIf { it.isNotBlank() }?.let { put("observers_txt", it) }
                put("observers", observersArr)
                put("habitats", habitatsArr)
            }

            val body = JSONObject()
                .put("type", "Feature")
                .put("geometry", geometry)
                .put("properties", properties)
                .toString()

            // Édition d'une station déjà envoyée : POST sur /stations/<id>/ (Phase 3+). Au MVP,
            // idStationServeur est null pour une saisie locale → création sur /stations/.
            val urlStr = station.idStationServeur?.takeIf { it > 0 }
                ?.let { "$base/api/occhab/stations/$it/" }
                ?: "$base/api/occhab/stations/"
            val conn = HttpClient.postJson(URL(urlStr), token, cookies, 30000)
            val code = try {
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                // Coupure réseau autour du POST : impossible de savoir si le serveur a committé
                // (la réponse peut se perdre APRÈS création). Statut INCERTAIN → le prochain envoi
                // vérifiera l'existence par UUID avant de recréer (anti-doublon). Ne jamais
                // requalifier ça en échec net : ce serait inviter à un re-POST aveugle.
                throw GNErreur.EnvoiIncertain(
                    "Réseau interrompu pendant l'envoi de la station — statut incertain " +
                        "(${e.message ?: e.javaClass.simpleName}). La station a peut-être été enregistrée.")
            }
            if (code !in 200..299) {
                val bodyErr = try {
                    (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText()
                } catch (_: Exception) { null }
                conn.disconnect()
                throw GNErreur.EnvoiEchoue(code, parseErreur(code, bodyErr))
            }
            val respText = try { conn.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
            conn.disconnect()

            // Réponse = la station en GeoJSON Feature. id_station en top-level `id` ou dans properties.
            val idStation = try {
                val resp = JSONObject(respText)
                resp.optInt("id", -1).takeIf { it > 0 }
                    ?: resp.optJSONObject("properties")?.optInt("id_station", -1)?.takeIf { it > 0 }
            } catch (_: Exception) { null }

            OccHabEnvoiResult(idStationServeur = idStation, nbHabitats = habitatsValides.size)
        }

    /**
     * Cherche une station déjà créée côté serveur portant [uuid] (`unique_id_sinp_station`).
     * `GET /api/occhab/stations/?format=json`. Renvoie son id_station si trouvée, sinon null —
     * y compris si le serveur est injoignable ou refuse la lecture (CRUVED R absent) : l'appelant
     * procédera alors au POST, ce qui reste sûr (un serveur muet n'a rien pu créer).
     */
    private fun verifierStationExistante(base: String, token: String?, cookies: String, uuid: String): Int? {
        if (uuid.isBlank()) return null
        return try {
            val conn = HttpClient.get(URL("$base/api/occhab/stations/?format=json"), token, cookies, 30000)
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return null }
            val text = try { conn.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
            conn.disconnect()
            trouverIdParUuid(text, uuid)
        } catch (_: Exception) { null }
    }

    /** Extrait l'id_station d'une station portant [uuid] dans la réponse de la liste des stations.
     *  Gère le format `json` (tableau d'objets) et un repli `geojson` (FeatureCollection). */
    internal fun trouverIdParUuid(text: String, uuid: String): Int? {
        val cible = uuid.trim().lowercase()
        if (cible.isEmpty()) return null
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            val root = try { JSONObject(text) } catch (_: Exception) { return null }
            root.optJSONArray("features") ?: return null
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val props = o.optJSONObject("properties") ?: o
            val u = props.optString("unique_id_sinp_station", "").trim().lowercase()
            if (u.isNotEmpty() && u == cible) {
                val id = props.optInt("id_station", -1).takeIf { it > 0 }
                    ?: o.optInt("id_station", -1).takeIf { it > 0 }
                    ?: o.optInt("id", -1).takeIf { it > 0 }
                if (id != null) return id
            }
        }
        return null
    }

    private fun parseErreur(code: Int, body: String?): String {
        if (body != null) {
            try {
                val j = JSONObject(body)
                return j.optString("description").takeIf { it.isNotEmpty() }
                    ?: j.optString("msg").takeIf { it.isNotEmpty() }
                    ?: j.optString("message").takeIf { it.isNotEmpty() }
                    ?: "HTTP $code"
            } catch (_: Exception) {}
        }
        return "Erreur HTTP $code"
    }
}
