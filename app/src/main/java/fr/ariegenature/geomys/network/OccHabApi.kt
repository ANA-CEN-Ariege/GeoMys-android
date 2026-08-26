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

import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** Droits de l'utilisateur sur le module OccHab (issus du cruved de `gn_commons/modules`). */
data class OccHabAcces(
    /** Module installé sur le serveur ET visible par l'utilisateur (au moins un droit). */
    val disponible: Boolean,
    /** cruved C > 0 : peut créer des stations. */
    val peutCreer: Boolean,
    /** cruved R > 0 : peut lire les stations. */
    val peutLire: Boolean,
) {
    companion object {
        val ABSENT = OccHabAcces(disponible = false, peutCreer = false, peutLire = false)
    }
}

/**
 * Accès réseau au module OccHab : détection du module (droits), et lecture des stations
 * existantes côté serveur (consultation lecture seule). L'envoi vit dans [OccHabUpload].
 */
object OccHabApi {

    const val MODULE_CODE = "OCCHAB"

    // Codes mnémoniques des nomenclatures OccHab (contraintes CHECK du module gn_module_occhab).
    // Utilisés pour restreindre la synchro (GeoNatureSync.synchroniserNomenclatures) aux listes
    // pertinentes. NAT_OBJ_GEO est partagé avec Occtax (déjà synchronisé).
    val MNEMONIQUES_STATION = listOf(
        "EXPOSITION",             // id_nomenclature_exposure
        "NAT_OBJ_GEO",            // id_nomenclature_geographic_object
        "METHOD_CALCUL_SURFACE",  // id_nomenclature_area_surface_calculation
        "TYPE_SOL",               // id_nomenclature_type_sol
        "MOSAIQUE_HAB",           // id_nomenclature_type_mosaique_habitat
    )
    val MNEMONIQUES_HABITAT = listOf(
        "DETERMINATION_TYP_HAB",  // id_nomenclature_determination_type
        "TECHNIQUE_COLLECT_HAB",  // id_nomenclature_collection_technique
        "ABONDANCE_HAB",          // id_nomenclature_abundance
        "SENSIBILITE",            // id_nomenclature_sensitivity
        "HAB_INTERET_COM",        // id_nomenclature_community_interest
    )
    val MNEMONIQUES: Set<String> = (MNEMONIQUES_STATION + MNEMONIQUES_HABITAT).toSet()

    /**
     * Altitudes min/max (MNT serveur) d'une géométrie via `POST /api/geo/info` — le même
     * appel que le web quand on dessine une station (form-service.ts `patchGeoValue` →
     * `getGeoInfo`). BEST-EFFORT : null si hors-ligne, non authentifié, erreur ou réponse
     * sans bloc `altitude` — l'appelant laisse alors les champs vides (saisie manuelle).
     * [geometryGeoJson] = objet GeoJSON *geometry* ({"type":"Point"/"Polygon","coordinates":…}),
     * enveloppé ici en Feature comme le fait le client web.
     */
    suspend fun altitudesPourGeometrie(
        config: GeoNatureConfig,
        geometryGeoJson: JSONObject,
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: return@withContext null
            val feature = JSONObject()
                .put("type", "Feature")
                .put("geometry", geometryGeoJson)
                .put("properties", JSONObject())
            val conn = HttpClient.postJson(URL("$base/api/geo/info"), token, cookies)
            conn.outputStream.use { it.write(feature.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return@withContext null
            val texte = conn.inputStream.bufferedReader().use { it.readText() }
            val alt = JSONObject(texte).optJSONObject("altitude") ?: return@withContext null
            val min = alt.opt("altitude_min")?.toString()?.toDoubleOrNull()?.toInt()
            val max = alt.opt("altitude_max")?.toString()?.toDoubleOrNull()?.toInt()
            if (min == null || max == null) null else min to max
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Détection GÉNÉRIQUE des droits par module via `GET /api/gn_commons/modules` (UN seul
     * appel pour tous les [codes]). N'inclut dans la map QUE les modules trouvés dans la
     * réponse : un module absent (pas installé / aucun droit) n'y figure pas — l'appelant
     * choisit son défaut (ABSENT pour OccHab, « autorisé » pour Occtax afin de ne pas
     * verrouiller l'app sur un serveur qui ne publie pas ce endpoint). Best-effort : map
     * vide sur toute erreur.
     */
    suspend fun detecterModules(
        config: GeoNatureConfig,
        codes: Set<String>,
    ): Map<String, OccHabAcces> = withContext(Dispatchers.IO) {
        val base = config.urlServeur.trim().trimEnd('/')
        val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
            ?: return@withContext emptyMap()
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = HttpClient.get(URL("$base/api/gn_commons/modules"), token, cookies, 15000)
            if (conn.responseCode != 200) return@withContext emptyMap()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            val resultat = mutableMapOf<String, OccHabAcces>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val code = m.optString("module_code")
                if (code !in codes) continue
                val cruved = m.optJSONObject("cruved")
                val c = cruved?.optInt("C", 0) ?: 0
                val r = cruved?.optInt("R", 0) ?: 0
                resultat[code] = OccHabAcces(disponible = true, peutCreer = c > 0, peutLire = r > 0)
            }
            resultat
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // ne PAS avaler l'annulation (sinon un sync annulé réinitialise les drapeaux).
        } catch (_: Exception) {
            emptyMap()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Valeurs par défaut des nomenclatures OccHab (`GET /api/occhab/defaultNomenclatures`) :
     * map mnémonique→id_nomenclature (ex. `TECHNIQUE_COLLECT_HAB` → « In situ »). Pré-remplit les
     * sélecteurs d'un nouvel habitat, comme le web. Best-effort : map vide si échec.
     */
    suspend fun chargerDefautsNomenclatures(config: GeoNatureConfig): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: return@withContext emptyMap()
            try {
                val conn = HttpClient.get(URL("$base/api/occhab/defaultNomenclatures"), token, cookies, 15000)
                if (conn.responseCode != 200) return@withContext emptyMap()
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                val out = HashMap<String, Int>()
                val it = obj.keys()
                while (it.hasNext()) {
                    val mnem = it.next()
                    val id = obj.optJSONObject(mnem)?.optInt("id_nomenclature", -1)?.takeIf { v -> v > 0 }
                    if (id != null) out[mnem] = id
                }
                out
            } catch (_: Exception) { emptyMap() }
        }

    /**
     * Charge les stations existantes du serveur.
     * `GET /api/occhab/stations/?format=geojson&habitats=1&nomenclatures=1` → FeatureCollection.
     * [idDataset] filtre optionnellement par jeu de données. Les stations renvoyées portent
     * `origineServeur = true` : affichées en consultation (gris) sur la carte, importables dans
     * la saisie courante pour être modifiées puis renvoyées en MISE À JOUR (cf. [OccHabUpload]).
     */
    suspend fun chargerStations(config: GeoNatureConfig, idDataset: Int? = null): List<OccHabStation> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, idRole, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)
            val url = buildString {
                append("$base/api/occhab/stations/?format=geojson&habitats=1&nomenclatures=1")
                if (idDataset != null && idDataset > 0) append("&id_dataset=$idDataset")
            }
            val conn = HttpClient.get(URL(url), token, cookies, 30000)
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = try {
                    (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText()
                } catch (_: Exception) { null }
                conn.disconnect()
                throw GNErreur.EnvoiEchoue(code, body?.take(200) ?: "HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            // SEULEMENT les stations de l'UTILISATEUR — filtre CLIENT : le backend ne propose
            // AUCUN filtre par personne sur cette route (Station.filter_by_params ne connaît
            // que id_dataset/cd_hab/date_low/date_up/id_import — un id_digitiser en query est
            // ignoré silencieusement, vérifié dans models.py). Un CRUVED « lire » large
            // (portée 2/3) renvoie donc les stations d'autrui : on ne garde que celles où le
            // compte est NUMÉRISATEUR ou OBSERVATEUR (bug terrain 2026-08-26).
            parserFeatureCollection(text, idRoleFiltre = idRole)
        }

    private val dateParsers = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US),
    )

    private fun parserDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (fmt in dateParsers) {
            try { return fmt.parse(s)?.time } catch (_: Exception) {}
        }
        return null
    }

    // ── Lectures sûres des properties (schéma StationSchema, include_fk=True) ──
    // optInt/optString d'org.json confondent « absent », « null » et 0/"null" : on passe par
    // opt() + isNull pour distinguer un champ réellement porté d'un champ vide — indispensable
    // depuis que la réédition RENVOIE ces champs au serveur (rien ne doit être inventé/écrasé).

    private fun entierOuNull(o: JSONObject, cle: String): Int? =
        if (o.isNull(cle)) null else (o.opt(cle) as? Number)?.toInt()

    private fun longOuNull(o: JSONObject, cle: String): Long? =
        if (o.isNull(cle)) null else (o.opt(cle) as? Number)?.toLong()

    private fun texteOuNull(o: JSONObject, cle: String): String? =
        if (o.isNull(cle)) null else (o.opt(cle) as? String)?.takeIf { it.isNotBlank() }

    /** id d'une nomenclature : la COLONNE `id_nomenclature_*` d'abord (toujours dumpée par le
     *  schéma serveur, include_fk=True), sinon l'OBJET imbriqué (présent quand la requête passe
     *  `nomenclatures=1`) — robustesse si un serveur n'expose que l'un des deux. */
    private fun idNomenclature(o: JSONObject, colonne: String, objetImbrique: String): Int? =
        entierOuNull(o, colonne)
            ?: o.optJSONObject(objetImbrique)?.let { entierOuNull(it, "id_nomenclature") }

    /**
     * Parse la FeatureCollection des stations serveur en capturant TOUT ce que la RÉÉDITION doit
     * préserver (une station importée puis renvoyée en mise à jour ne doit RIEN effacer côté
     * serveur) : identifiants (id_station, unique_id_sinp_station, id_habitat,
     * unique_id_sinp_hab), dates, observateurs, altitudes/profondeurs/surface/précision,
     * nomenclatures station et habitat, textes libres.
     */
    internal fun parserFeatureCollection(text: String, idRoleFiltre: Int? = null): List<OccHabStation> {
        val root = try { JSONObject(text) } catch (_: Exception) { return emptyList() }
        val features = root.optJSONArray("features") ?: return emptyList()
        val stations = mutableListOf<OccHabStation>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: JSONObject()
            if (idRoleFiltre != null && idRoleFiltre > 0) {
                val estNumerisateur = props.optInt("id_digitiser", -1) == idRoleFiltre
                val estObservateur = props.optJSONArray("observers")?.let { obs ->
                    (0 until obs.length()).any { j ->
                        obs.optJSONObject(j)?.optInt("id_role", -1) == idRoleFiltre
                    }
                } ?: false
                // Propriétaire indéterminable (champs absents du schéma serveur) → on ÉCARTE :
                // mieux vaut manquer une station que d'afficher celles d'autrui.
                if (!estNumerisateur && !estObservateur) continue
            }
            val (type, lat, lon, coordsJson) = parserGeometrie(f.optJSONObject("geometry"))
            val dateMin = parserDate(props.optString("date_min").takeIf { it.isNotBlank() })
            val dateMax = parserDate(props.optString("date_max").takeIf { it.isNotBlank() })
            // Observateurs : ids + noms complets (UserSchema : nom_complet, sinon NOM prénom) —
            // sans eux, une mise à jour réduirait la liste serveur à l'utilisateur connecté.
            val obsIds = mutableListOf<Int>()
            val obsNoms = mutableListOf<String>()
            props.optJSONArray("observers")?.let { obs ->
                for (j in 0 until obs.length()) {
                    val o = obs.optJSONObject(j) ?: continue
                    val idRole = entierOuNull(o, "id_role")?.takeIf { it > 0 } ?: continue
                    obsIds.add(idRole)
                    obsNoms.add(
                        texteOuNull(o, "nom_complet")
                            ?: listOfNotNull(texteOuNull(o, "nom_role"), texteOuNull(o, "prenom_role"))
                                .joinToString(" ").ifBlank { "#$idRole" }
                    )
                }
            }
            val habitats = mutableListOf<OccHabHabitat>()
            val habArr = props.optJSONArray("habitats")
            if (habArr != null) {
                for (j in 0 until habArr.length()) {
                    val h = habArr.optJSONObject(j) ?: continue
                    val cdHab = h.optInt("cd_hab", 0)
                    val label = h.optJSONObject("habref")?.optString("lb_hab_fr", "")
                        ?.ifBlank { h.optString("nom_cite", "") } ?: h.optString("nom_cite", "")
                    habitats.add(OccHabHabitat(
                        // id serveur + UUID SINP : renvoyés à l'update pour que le serveur METTE À
                        // JOUR cet habitat (sinon il le supprimerait et en recréerait un autre).
                        idHabitatServeur = entierOuNull(h, "id_habitat")?.takeIf { it > 0 },
                        uuidHabitat = texteOuNull(h, "unique_id_sinp_hab"),
                        cdHab = cdHab,
                        habitatLabel = label,
                        nomCite = h.optString("nom_cite", ""),
                        determiner = texteOuNull(h, "determiner"),
                        recouvrement = h.optDouble("recovery_percentage").takeIf { !it.isNaN() },
                        precisionTechnique = texteOuNull(h, "technical_precision"),
                        idNomTypeDetermination = idNomenclature(
                            h, "id_nomenclature_determination_type", "nomenclature_determination_type"),
                        idNomTechniqueCollecte = idNomenclature(
                            h, "id_nomenclature_collection_technique", "nomenclature_collection_technique"),
                        idNomAbondance = idNomenclature(
                            h, "id_nomenclature_abundance", "nomenclature_abundance"),
                        idNomSensibilite = idNomenclature(
                            h, "id_nomenclature_sensitivity", "nomenclature_sensitivity"),
                        idNomInteretCommunautaire = idNomenclature(
                            h, "id_nomenclature_community_interest", "nomenclature_community_interest"),
                    ))
                }
            }
            stations.add(OccHabStation(
                // UUID SINP du serveur CONSERVÉ : un update le renvoie tel quel (en générer un
                // nouveau écraserait unique_id_sinp_station côté serveur).
                uuidStation = texteOuNull(props, "unique_id_sinp_station")
                    ?: java.util.UUID.randomUUID().toString(),
                idStationServeur = props.optInt("id_station", -1).takeIf { it > 0 },
                date = dateMin ?: System.currentTimeMillis(),
                geometryType = type,
                latitude = lat,
                longitude = lon,
                geometryCoordsJson = coordsJson,
                idDataset = props.optInt("id_dataset", -1).takeIf { it > 0 },
                observateursIds = obsIds,
                observateursNoms = obsNoms,
                observateursTxt = texteOuNull(props, "observers_txt"),
                stationName = texteOuNull(props, "station_name"),
                comment = texteOuNull(props, "comment"),
                dateMin = dateMin,
                dateMax = dateMax,
                altitudeMin = entierOuNull(props, "altitude_min"),
                altitudeMax = entierOuNull(props, "altitude_max"),
                profondeurMin = entierOuNull(props, "depth_min"),
                profondeurMax = entierOuNull(props, "depth_max"),
                surface = longOuNull(props, "area"),
                precision = entierOuNull(props, "precision"),
                idNomExposition = idNomenclature(
                    props, "id_nomenclature_exposure", "nomenclature_exposure"),
                idNomCalculSurface = idNomenclature(
                    props, "id_nomenclature_area_surface_calculation", "nomenclature_area_surface_calculation"),
                idNomObjetGeographique = idNomenclature(
                    props, "id_nomenclature_geographic_object", "nomenclature_geographic_object"),
                idNomTypeSol = idNomenclature(
                    props, "id_nomenclature_type_sol", "nomenclature_type_sol"),
                // NB : le nom de la relation serveur est `type_mosaique_habitat` (sans préfixe).
                idNomTypeMosaique = idNomenclature(
                    props, "id_nomenclature_type_mosaique_habitat", "type_mosaique_habitat"),
                habitats = habitats,
                envoyeGeoNature = true,
                origineServeur = true,
            ))
        }
        return stations
    }

    /** Résultat du parsing d'une géométrie GeoJSON : type app, point représentatif, et
     *  sommets JSON `[[lon,lat], …]` (polygone/ligne) pour le tracé. */
    private data class GeomParse(
        val type: String, val lat: Double, val lon: Double, val coordsJson: String?,
    )

    private fun parserGeometrie(geom: JSONObject?): GeomParse {
        if (geom == null) return GeomParse("Point", 0.0, 0.0, null)
        val type = geom.optString("type", "Point")
        val coords = geom.optJSONArray("coordinates") ?: return GeomParse("Point", 0.0, 0.0, null)
        return try {
            when (type) {
                "Point" -> GeomParse("Point", coords.getDouble(1), coords.getDouble(0), null)
                "Polygon" -> {
                    // coordinates = [ anneau extérieur [ [lon,lat], … ] ]
                    val ring = coords.getJSONArray(0)
                    val sommets = JSONArray()
                    var sLat = 0.0; var sLon = 0.0; var n = 0
                    for (k in 0 until ring.length()) {
                        val pt = ring.getJSONArray(k)
                        val lon = pt.getDouble(0); val lat = pt.getDouble(1)
                        sommets.put(JSONArray().put(lon).put(lat))
                        sLat += lat; sLon += lon; n++
                    }
                    val cLat = if (n > 0) sLat / n else 0.0
                    val cLon = if (n > 0) sLon / n else 0.0
                    GeomParse("Polygon", cLat, cLon, sommets.toString())
                }
                "LineString" -> {
                    val sommets = JSONArray()
                    var sLat = 0.0; var sLon = 0.0; var n = 0
                    for (k in 0 until coords.length()) {
                        val pt = coords.getJSONArray(k)
                        val lon = pt.getDouble(0); val lat = pt.getDouble(1)
                        sommets.put(JSONArray().put(lon).put(lat))
                        sLat += lat; sLon += lon; n++
                    }
                    GeomParse("LineString", if (n > 0) sLat / n else 0.0, if (n > 0) sLon / n else 0.0, sommets.toString())
                }
                else -> GeomParse("Point", 0.0, 0.0, null)
            }
        } catch (_: Exception) {
            GeomParse("Point", 0.0, 0.0, null)
        }
    }
}
