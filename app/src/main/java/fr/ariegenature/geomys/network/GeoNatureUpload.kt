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

import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.OcctaxFieldsConfig
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

/** Résultat d'un envoi (`GeoNatureUpload.envoyer`).
 *  Les 3 premiers champs sont compatibles avec l'ancien `Triple<Int, Int, Int?>` via `component1/2/3()`. */
data class EnvoiResult(
    /** Nombre d'occurrences créées avec succès côté serveur. */
    val nbCrees: Int,
    /** Nombre total d'occurrences candidates. */
    val nbTotal: Int,
    /** Premier id_releve_occtax créé (pour navigation). */
    val premierIdReleve: Int?,
    /** Nombre de médias uploadés avec succès vers gn_commons. */
    val mediasOK: Int = 0,
    /** Nombre de médias dont l'upload a échoué. */
    val mediasKO: Int = 0,
    /** Message d'erreur du premier média en échec (debug — surfacé dans le toast). */
    val mediaErreurMsg: String? = null,
    /** Relevés créés côté serveur dont toutes les occurrences ont échoué ET dont le DELETE
     *  de rollback a aussi échoué — restent vides côté GeoNature et doivent être supprimés
     *  manuellement. Vide en cas de succès complet ou de rollback réussi. */
    val relevesOrphelins: List<Int> = emptyList(),
)

object GeoNatureUpload {

    private const val TAG_MEDIA = "GeoMysMedia"
    private val nomenclatureCache = mutableMapOf<String, Int>()

    /** Vide les caches mémoire process-wide : ids de nomenclatures résolus + id_table_location
     *  cor_counting_occtax. À appeler quand l'URL/login/mdp serveur changent — sinon on
     *  réutilise des ids issus de l'instance précédente (les id_nomenclature et
     *  id_table_location varient d'un GeoNature à l'autre). */
    fun invaliderCaches() {
        nomenclatureCache.clear()
        idTableLocationCountingCache = null
    }

    // Les correspondances code interne → label minuscule stable (pour résoudre l'id_nomenclature
    // serveur, qui varie d'une instance à l'autre) vivent désormais dans le registre unique
    // OcctaxFieldsConfig (champ `uploadLabels`), comme les clés d'upload (`uploadKey`).

    suspend fun envoyer(sortie: Sortie, config: GeoNatureConfig): EnvoiResult =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, idRole, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)

            val obsValides = sortie.observations.filter { it.cdNom != null }
            if (obsValides.isEmpty()) throw GNErreur.AucuneObservationCompatible()

            val datasetId = config.idDataset.trim().toIntOrNull()?.takeIf { it > 0 }
                ?: throw GNErreur.EnvoiEchoue(0, "id_dataset invalide : \"${config.idDataset}\"")
            // Garde : un id_dataset absent du serveur courant (typiquement hérité d'un autre
            // serveur après changement d'URL) provoque une violation de clé étrangère côté
            // GeoNature → 500 opaque. On le détecte ici pour remonter un message clair plutôt
            // qu'une « Erreur serveur (HTTP 500) ». Si le cache datasets est vide, datasetValide
            // renvoie true (on ne bloque pas faute de pouvoir trancher).
            if (!config.datasetValide) throw GNErreur.DatasetInvalide(datasetId)

            // Nettoyage des relevés orphelins d'envois PRÉCÉDENTS (rollback DELETE qui avait
            // échoué — typiquement coupure réseau totale type mode avion) : on retente leur
            // suppression maintenant que le réseau répond. Best-effort : un échec les laisse
            // mémorisés pour le prochain envoi.
            fr.ariegenature.geomys.store.RelevesOrphelins.liste(base).forEach { idOrphelin ->
                if (tenterSupprimerReleve(base, token, cookies, idOrphelin)) {
                    fr.ariegenature.geomys.store.RelevesOrphelins.retirer(base, idOrphelin)
                }
            }

            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val heureFmt = SimpleDateFormat("HH:mm", Locale.US)

            var nbCrees = 0
            var premierIdReleve: Int? = null
            var derniereErreur: String? = null
            var dernierCodeErreur: Int = 0
            // Compteurs média (gn_commons).
            var mediasOK = 0
            var mediasKO = 0
            var mediaErreurMsg: String? = null
            // Relevés créés côté serveur pour lesquels le POST de l'occurrence a échoué.
            // Ils restent vides sur GeoNature et doivent être signalés à l'utilisateur.
            val relevesOrphelins = mutableListOf<Int>()

            val nomenclatures = mutableMapOf<String, Map<String, Int>>()
            // Types à résoudre : dérivés du registre unique (+ TYPE_MEDIA), même source que la synchro.
            val typesVoulus = OcctaxFieldsConfig.mnemoniques() + "TYPE_MEDIA"

            // Si le cache est vide, on tente une synchro automatique
            if (!NomenclatureCache.estDisponible) {
                try { GeoNatureSync.synchroniserNomenclatures(config) } catch (_: Exception) {}
            }

            for (type in typesVoulus) {
                val fromCache = NomenclatureCache.get(type)
                nomenclatures[type] = if (fromCache.isNotEmpty()) {
                    fromCache.associate { normaliserLabelNomenclature(it.label) to it.id }
                } else {
                    resolverNomenclatures(base, token, cookies, type)
                }
            }

            // Regroupement explicite par releveId : la saisie multi-taxons stampille
            // toutes ses obs avec un même UUID, qui devient ici un seul relevé GeoNature
            // avec N occurrences. Pour les obs sans releveId (saisie mono-taxon, import GPX),
            // on utilise obs.id comme clé : chaque obs forme son propre groupe d'1 élément.
            val groupes = obsValides.groupBy { it.releveId ?: it.id }

            for ((_, groupe) in groupes) {
                // Coordonnées du relevé : la première obs du groupe (toutes identiques pour
                // un batch multi-taxons issu de SaisieObservationFragment).
                val lat = groupe.first().latitude
                val lon = groupe.first().longitude
                // Plage temporelle : par défaut, couvre toutes les obs du groupe (dates de saisie).
                // Si « Détails du relevé » porte une date explicite (dateDebutReleve), elle PRIME
                // (saisie a posteriori) ; dateFinReleve borne la fin, sinon = début.
                val debutOverride = groupe.first().dateDebutReleve
                val dateMin = debutOverride?.let { Date(it) } ?: Date(groupe.minOf { it.date })
                val dateMax = (groupe.first().dateFinReleve ?: debutOverride)?.let { Date(it) }
                    ?: Date(groupe.maxOf { it.date })

                // additional_fields du relevé : on prend les valeurs portées par la 1re obs du
                // groupe (toutes les obs d'un même releveId portent la même copie — édition côté
                // CaracterisationFragment).
                val additionalReleve = jsonDepuisMap(groupe.first().additionalFieldsReleve)
                // Override par relevé (saisis via « Détails du relevé »), sinon valeurs par défaut :
                //  - jeu de données : obs.idDatasetReleve sinon le dataset de la config ;
                //  - observateurs : obs.observateursReleveIds (tableau, multi-observateurs) sinon
                //    l'unique legacy obs.observateurReleveId, sinon l'utilisateur connecté
                //    (id_digitiser reste l'auteur de la saisie côté app).
                val datasetGroupe = groupe.first().idDatasetReleve?.takeIf { it > 0 } ?: datasetId
                // Garde sur l'override de relevé : un jeu de données choisi dans « Détails du
                // relevé » mais absent du serveur courant part en FK invalide → 500 opaque. On le
                // détecte ici (comme la garde globale config.datasetValide) et on saute le groupe
                // avec un message clair plutôt qu'une « erreur serveur ».
                if (!config.datasetAcceptablePourEnvoi(datasetGroupe)) {
                    dernierCodeErreur = 0
                    derniereErreur = "Jeu de données $datasetGroupe (choisi dans « Détails du relevé ») introuvable sur ce serveur."
                    continue
                }
                val observateursGroupe = groupe.first().let { o ->
                    o.observateursReleveIds.filter { it > 0 }
                        .ifEmpty { listOfNotNull(o.observateurReleveId?.takeIf { it > 0 }) }
                        .ifEmpty { listOfNotNull(idRole) }
                }
                val properties = JSONObject().apply {
                    put("id_dataset", datasetGroupe)
                    put("date_min", dateFmt.format(dateMin))
                    put("date_max", dateFmt.format(dateMax))
                    put("hour_min", heureFmt.format(dateMin))
                    put("hour_max", heureFmt.format(dateMax))
                    put("additional_fields", additionalReleve)
                    put("meta_device_entry", "mobile")
                    put("t_occurrences_occtax", JSONArray())
                    if (idRole != null) put("id_digitiser", idRole)
                    if (observateursGroupe.isNotEmpty()) {
                        put("observers", JSONArray().apply { observateursGroupe.forEach { put(it) } })
                    }
                    // Commentaire libre du relevé.
                    groupe.first().commentReleve?.takeIf { it.isNotBlank() }?.let { put("comment", it) }
                    // Habitat du relevé (code HABREF).
                    groupe.first().cdHabReleve?.takeIf { it > 0 }?.let { put("cd_hab", it) }
                    // Type de regroupement (nomenclature TYP_GRP, niveau relevé).
                    groupe.first().typGrpReleve?.takeIf { it.isNotEmpty() }?.let { code ->
                        resolverIdNomenclature(code, "TYP_GRP", emptyMap(), nomenclatures)
                            ?.let { put("id_nomenclature_grp_typ", it) }
                    }
                    // Champs relevé supplémentaires pilotés par form_fields : entiers
                    // (altitude/profondeur/précision), textes (lieu / technique de regroupement) et
                    // nomenclature (nature de l'objet géo → NAT_OBJ_GEO). Omis si vide/illisible.
                    val extraReleve = groupe.first().champsReleveExtra
                    extraReleve["altitude_min"]?.trim()?.toIntOrNull()?.let { put("altitude_min", it) }
                    extraReleve["altitude_max"]?.trim()?.toIntOrNull()?.let { put("altitude_max", it) }
                    extraReleve["depth_min"]?.trim()?.toIntOrNull()?.let { put("depth_min", it) }
                    extraReleve["depth_max"]?.trim()?.toIntOrNull()?.let { put("depth_max", it) }
                    extraReleve["precision"]?.trim()?.toIntOrNull()?.let { put("precision", it) }
                    extraReleve["place_name"]?.trim()?.takeIf { it.isNotEmpty() }?.let { put("place_name", it) }
                    extraReleve["grp_method"]?.trim()?.takeIf { it.isNotEmpty() }?.let { put("grp_method", it) }
                    extraReleve["geo_object_nature"]?.takeIf { it.isNotEmpty() }?.let { code ->
                        resolverIdNomenclature(code, "NAT_OBJ_GEO", emptyMap(), nomenclatures)
                            ?.let { put("id_nomenclature_geo_object_nature", it) }
                    }
                    extraReleve["tech_collect"]?.takeIf { it.isNotEmpty() }?.let { code ->
                        resolverIdNomenclature(code, "TECHNIQUE_OBS", emptyMap(), nomenclatures)
                            ?.let { put("id_nomenclature_tech_collect_campanule", it) }
                    }
                }

                // Construction de la géométrie selon le type stocké côté Observation.
                // - Point : coordonnées [lon, lat] (rétrocompat — toutes les obs sans geometryType).
                // - LineString : coordonnées [[lon,lat], …] depuis geometryCoordsJson.
                // - Polygon : coordonnées [[[lon,lat], …, [lon,lat]]] (anneau extérieur ;
                //   on ferme automatiquement si le dernier sommet ≠ premier).
                val premiereObs = groupe.first()
                val geometry = construireGeometrie(
                    type = premiereObs.geometryType,
                    coordsJson = premiereObs.geometryCoordsJson,
                    lat = lat,
                    lon = lon,
                )

                val body1 = JSONObject()
                    .put("geometry", geometry)
                    .put("status", "to_sync")
                    .put("properties", properties)
                    .toString()

                val urlReleve = URL("$base/api/occtax/OCCTAX/only/releve")
                val conn1 = HttpClient.postJson(urlReleve, token, cookies, 30000)
                // Coupure réseau (timeout, socket fermée…) pendant le POST du relevé : aucun id
                // serveur connu → on enregistre l'erreur et on passe au groupe suivant, la sortie
                // reste ré-envoyable telle quelle. Sans ce garde-fou l'IOException remontait brute
                // (pas de GNErreur, pas de message humanisé).
                val code1 = try {
                    OutputStreamWriter(conn1.outputStream).use { it.write(body1) }
                    conn1.responseCode
                } catch (e: IOException) {
                    conn1.disconnect()
                    dernierCodeErreur = 0
                    derniereErreur = "Réseau interrompu pendant la création du relevé (${e.message ?: e.javaClass.simpleName})"
                    continue
                }
                if (code1 !in 200..299) {
                    val bodyErr = try { (conn1.errorStream ?: conn1.inputStream)?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    conn1.disconnect()
                    dernierCodeErreur = code1
                    derniereErreur = parseErreur(code1, bodyErr)
                    continue
                }
                val resp1Text = try { conn1.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
                conn1.disconnect() // réponse relevé consommée — libère la connexion (lot multi-taxons).
                val resp1 = try { JSONObject(resp1Text) } catch (_: Exception) {
                    derniereErreur = "Réponse relevé non-JSON"
                    continue
                }

                // Extraction de l'ID robuste (identique iOS)
                val idReleve = resp1.optInt("id", -1).takeIf { it > 0 }
                    ?: resp1.optJSONObject("properties")?.optInt("id_releve_occtax", -1)?.takeIf { it > 0 }
                    ?: resp1.optInt("id_releve_occtax", -1).takeIf { it > 0 }

                if (idReleve == null) {
                    val keys = try {
                        val kList = mutableListOf<String>()
                        val it = resp1.keys()
                        while(it.hasNext()) kList.add(it.next())
                        kList.sorted().joinToString(",")
                    } catch (_: Exception) { "?" }
                    derniereErreur = "id absent de la réponse (clés: $keys)"
                    continue
                }
                if (premierIdReleve == null) premierIdReleve = idReleve

                // Une occurrence par taxon — toutes attachées au même relevé.
                var nbReussisGroupe = 0
                // Ids des médias gn_commons uploadés pour ce groupe : sert au rollback si
                // toutes les occurrences du groupe échouent (pattern gn_mobile_occtax officiel).
                val mediaIdsGroupe = mutableListOf<Int>()
                for (obs in groupe) {
                    // ── Étape A : upload des médias par counting AVANT le POST de l'occurrence ──
                    // (chaque media uploadé renvoie un JSON Media à inclure dans le counting).
                    val mediasParCounting = mutableListOf<List<JSONObject>>()
                    val urisCounting0 = obs.mediaUrisCounting0
                    val urisAdditionnels = obs.denombrementsAdditionnels.map { it.mediaUris }
                    val besoinMedia = urisCounting0.isNotEmpty() || urisAdditionnels.any { it.isNotEmpty() }
                    if (besoinMedia) {
                        val (idTableLoc, errTable) = resoudreIdTableLocationCounting(base, token, cookies)
                        val typesMediaOcctax = nomenclatures["TYPE_MEDIA"] ?: emptyMap()
                        val author = config.observateurDefautNom.ifEmpty {
                            config.nomUtilisateur.ifEmpty { config.login }
                        }
                        fun upload(uris: List<String>): List<JSONObject> = uris.mapNotNull { uri ->
                            if (idTableLoc == null) {
                                mediasKO++; if (mediaErreurMsg == null) mediaErreurMsg = errTable
                                return@mapNotNull null
                            }
                            // Inférence du mime depuis l'extension du fichier copié localement.
                            val mimeHint = try {
                                val path = android.net.Uri.parse(uri).path ?: ""
                                java.net.URLConnection.guessContentTypeFromName(path) ?: "image/jpeg"
                            } catch (_: Exception) { "image/jpeg" }
                            val idTypeMedia = idTypeMediaPour(typesMediaOcctax, mimeHint)
                            val (json, err) = uploaderMediaFile(
                                base = base, token = token, cookies = cookies,
                                mediaPath = uri, author = author, titre = obs.espece,
                                idTableLocation = idTableLoc, idTypeMedia = idTypeMedia,
                            )
                            if (json != null) {
                                mediasOK++
                                // Capture l'id_media pour rollback éventuel (cf. mediaIdsGroupe).
                                val idMedia = json.optInt("id_media", -1).takeIf { it > 0 }
                                    ?: json.optInt("id", -1).takeIf { it > 0 }
                                if (idMedia != null) mediaIdsGroupe.add(idMedia)
                                json
                            }
                            else { mediasKO++; if (mediaErreurMsg == null) mediaErreurMsg = err; null }
                        }
                        mediasParCounting.add(upload(urisCounting0))
                        urisAdditionnels.forEach { mediasParCounting.add(upload(it)) }
                    }

                    // ── Étape B : construction + POST du JSON occurrence avec medias[] inclus ──
                    val occ = buildOccurrence(obs, nomenclatures, mediasParCounting)

                    val urlOcc = URL("$base/api/occtax/OCCTAX/releve/$idReleve/occurrence")
                    val conn2 = HttpClient.postJson(urlOcc, token, cookies, 30000)
                    // Coupure réseau pendant le POST de l'occurrence : comptée comme un échec
                    // ordinaire (code2 = -1) pour que le rollback du relevé en fin de groupe
                    // s'applique. Sans ce garde-fou l'IOException remontait brute et laissait
                    // un relevé vide côté GeoNature, sans signalement d'orphelin.
                    val code2 = try {
                        OutputStreamWriter(conn2.outputStream).use { it.write(occ.toString()) }
                        conn2.responseCode
                    } catch (e: IOException) {
                        dernierCodeErreur = 0
                        derniereErreur = "Réseau interrompu pendant l'envoi de l'occurrence (${e.message ?: e.javaClass.simpleName})"
                        -1
                    }
                    if (code2 in 200..299) {
                        nbCrees++
                        nbReussisGroupe++
                    } else if (code2 > 0) {
                        val bodyErr = try { (conn2.errorStream ?: conn2.inputStream)?.bufferedReader()?.readText() } catch (_: Exception) { null }
                        dernierCodeErreur = code2
                        derniereErreur = parseErreur(code2, bodyErr)
                    }
                    conn2.disconnect() // libère la connexion à chaque occurrence (lot multi-taxons).
                }

                // Rollback : si aucune occurrence n'a abouti, on supprime le relevé côté
                // serveur (et les médias déjà uploadés pour ce groupe) pour ne pas laisser
                // un relevé vide dans Occtax. Pattern repris de gn_mobile_occtax officiel
                // (SynchronizeObservationRecordRepositoryImpl). Si le DELETE échoue lui-même
                // (réseau, droit, etc.), on signale le relevé comme vraiment orphelin.
                if (nbReussisGroupe == 0) {
                    val mediasSupprimes = mediaIdsGroupe.count {
                        tenterSupprimerMedia(base, token, cookies, it)
                    }
                    mediasOK -= mediasSupprimes
                    val releveSupprime = tenterSupprimerReleve(base, token, cookies, idReleve)
                    if (!releveSupprime) {
                        relevesOrphelins.add(idReleve)
                        // Mémorisé pour suppression automatique au prochain envoi (cf. début
                        // d'envoyer) — un « relevé sans taxon » ne doit pas rester sur le
                        // serveur en comptant sur une suppression manuelle.
                        fr.ariegenature.geomys.store.RelevesOrphelins.ajouter(base, listOf(idReleve))
                    }
                }
            }

            if (nbCrees == 0) {
                val msg = buildString {
                    append(derniereErreur ?: "Aucun relevé créé")
                    if (relevesOrphelins.isNotEmpty()) {
                        append(" — ${relevesOrphelins.size} relevé(s) vide(s) créé(s) côté GeoNature ")
                        append("(id : ${relevesOrphelins.joinToString(", ")}) ; ")
                        append("leur suppression sera retentée automatiquement au prochain envoi.")
                    }
                }
                throw GNErreur.EnvoiEchoue(dernierCodeErreur, msg)
            }
            EnvoiResult(
                nbCrees = nbCrees, nbTotal = obsValides.size, premierIdReleve = premierIdReleve,
                mediasOK = mediasOK, mediasKO = mediasKO, mediaErreurMsg = mediaErreurMsg,
                relevesOrphelins = relevesOrphelins.toList(),
            )
        }

    internal fun buildOccurrence(
        obs: Observation,
        nomenclatures: Map<String, Map<String, Int>>,
        mediasParCounting: List<List<JSONObject>> = emptyList(),
    ): JSONObject {
        // Counting #0 — issu des champs flat de Observation (saisie mono-taxon ou 1er dénombrement multi).
        val counting0 = buildCounting(
            nombreMin = obs.nombre,
            nombreMax = obs.nombreMax ?: obs.nombre,
            sexe = obs.sexe,
            stadeVie = obs.stadeVie,
            objDenbr = obs.objDenbr,
            typDenbr = obs.typDenbr,
            medias = mediasParCounting.getOrNull(0) ?: emptyList(),
            additionalFields = obs.additionalFieldsCounting0,
            nomenclatures = nomenclatures,
        )
        val countings = JSONArray().put(counting0)
        // Countings supplémentaires (mode multi-taxon).
        obs.denombrementsAdditionnels.forEachIndexed { i, d ->
            countings.put(buildCounting(
                nombreMin = d.nombreMin,
                nombreMax = d.nombreMax,
                sexe = d.sexe,
                stadeVie = d.stadeVie,
                objDenbr = d.objDenbr,
                typDenbr = d.typDenbr,
                medias = mediasParCounting.getOrNull(i + 1) ?: emptyList(),
                additionalFields = d.additionalFields,
                nomenclatures = nomenclatures,
            ))
        }

        return JSONObject().apply {
            put("cd_nom", obs.cdNom!!)
            put("nom_cite", obs.espece)
            if (obs.notes.isNotEmpty()) put("comment", obs.notes)
            put("cor_counting_occtax", countings)
            // Champs de nomenclature occurrence : pilotés par le registre unique (svKey → valeur,
            // code/uploadKey/uploadLabels). METH_OBS prend "0" par défaut (présence non qualifiée).
            val valeursOcc = mapOf(
                "statutObs" to obs.statutObs,
                "techniqueObs" to (obs.techniqueObs ?: "0"),
                "etaBio" to obs.etaBio,
                "comportement" to obs.comportement,
                "statutBio" to obs.statutBio,
                "methDetermin" to obs.methDetermin,
                "preuveExist" to obs.preuveExist,
                "naturalite" to obs.naturalite,
                // Champs occurrence pilotés form_fields (portés dans champsOccExtra par clé form_fields).
                "sourceStatus" to (obs.champsOccExtra["source_status"] ?: ""),
                "blurring" to (obs.champsOccExtra["blurring"] ?: ""),
            )
            OcctaxFieldsConfig.REGISTRE
                .filter { it.niveau == OcctaxFieldsConfig.Niveau.INFORMATION }
                .forEach { f ->
                    valeursOcc[f.svKey]?.let { code ->
                        resolverIdNomenclature(code, f.code, f.uploadLabels, nomenclatures)
                            ?.let { put(f.uploadKey, it) }
                    }
                }
            obs.determinateur?.takeIf { it.isNotEmpty() }?.let { put("determiner", it) }
            // Preuves (texte) pilotées form_fields.
            obs.champsOccExtra["digital_proof"]?.takeIf { it.isNotEmpty() }?.let { put("digital_proof", it) }
            obs.champsOccExtra["non_digital_proof"]?.takeIf { it.isNotEmpty() }?.let { put("non_digital_proof", it) }
            if (obs.additionalFieldsOccurrence.isNotEmpty()) {
                put("additional_fields", jsonDepuisMap(obs.additionalFieldsOccurrence))
            }
        }
    }

    /** Convertit une Map<field_name, valeur stringifiée> en JSONObject typé.
     *  "true"/"false" → bool, entiers/décimaux → number, sinon string. */
    /** Construit le GeoJSON `geometry` à envoyer au serveur GeoNature pour un relevé.
     *  - `type == "Point"` ou null → [lon, lat] depuis lat/lon (rétrocompat).
     *  - `type == "LineString"` → coordsJson est `[[lon,lat], …]` (≥ 2 sommets).
     *  - `type == "Polygon"` → coordsJson est `[[lon,lat], …]` (anneau extérieur) ; on
     *    referme automatiquement si le dernier sommet diffère du premier et on wrappe
     *    dans le tableau supplémentaire requis par le format GeoJSON Polygon
     *    (`[[[lon,lat], …]]`). */
    internal fun construireGeometrie(
        type: String?,
        coordsJson: String?,
        lat: Double,
        lon: Double,
    ): JSONObject {
        if (type.isNullOrEmpty() || type == "Point" || coordsJson.isNullOrEmpty()) {
            return JSONObject()
                .put("type", "Point")
                .put("coordinates", JSONArray().put(lon).put(lat))
        }
        val coords = JSONArray(coordsJson) // [[lon,lat], …]
        return when (type) {
            "LineString" -> JSONObject()
                .put("type", "LineString")
                .put("coordinates", coords)
            "Polygon" -> {
                // Fermeture automatique de l'anneau si nécessaire.
                if (coords.length() >= 3) {
                    val premier = coords.getJSONArray(0)
                    val dernier = coords.getJSONArray(coords.length() - 1)
                    if (premier.getDouble(0) != dernier.getDouble(0) ||
                        premier.getDouble(1) != dernier.getDouble(1)) {
                        coords.put(JSONArray().put(premier.getDouble(0)).put(premier.getDouble(1)))
                    }
                }
                JSONObject()
                    .put("type", "Polygon")
                    .put("coordinates", JSONArray().put(coords))
            }
            else -> JSONObject()
                .put("type", "Point")
                .put("coordinates", JSONArray().put(lon).put(lat))
        }
    }

    internal fun jsonDepuisMap(map: Map<String, String>): JSONObject = JSONObject().apply {
        for ((k, v) in map) {
            if (v.isEmpty()) continue
            when {
                v.equals("true", ignoreCase = true) -> put(k, true)
                v.equals("false", ignoreCase = true) -> put(k, false)
                v.toIntOrNull() != null -> put(k, v.toInt())
                v.toDoubleOrNull() != null -> put(k, v.toDouble())
                else -> put(k, v)
            }
        }
    }

    private fun buildCounting(
        nombreMin: Int,
        nombreMax: Int,
        sexe: String?,
        stadeVie: String?,
        objDenbr: String?,
        typDenbr: String?,
        medias: List<JSONObject> = emptyList(),
        additionalFields: Map<String, String> = emptyMap(),
        nomenclatures: Map<String, Map<String, Int>>,
    ): JSONObject = JSONObject().apply {
        put("count_min", nombreMin)
        put("count_max", nombreMax.coerceAtLeast(nombreMin))
        // Champs de nomenclature dénombrement : pilotés par le registre unique.
        val valeursCnt = mapOf("sexe" to sexe, "stadeVie" to stadeVie, "objDenbr" to objDenbr, "typDenbr" to typDenbr)
        OcctaxFieldsConfig.REGISTRE
            .filter { it.niveau == OcctaxFieldsConfig.Niveau.COUNTING }
            .forEach { f ->
                valeursCnt[f.svKey]?.let { code ->
                    resolverIdNomenclature(code, f.code, f.uploadLabels, nomenclatures)
                        ?.let { put(f.uploadKey, it) }
                }
            }
        // medias[] : objets renvoyés par POST /api/gn_commons/media — le serveur lie au counting
        // par référence (id_media). Le pattern suit gn_mobile_occtax/TaxonRecordJsonWriter.
        if (medias.isNotEmpty()) {
            val arr = JSONArray()
            medias.forEach { arr.put(it) }
            put("medias", arr)
        }
        if (additionalFields.isNotEmpty()) {
            put("additional_fields", jsonDepuisMap(additionalFields))
        }
    }

    // Résout un code interne ou un id_nomenclature direct vers l'id GeoNature.
    // ATTENTION ordre des étapes : les ids serveur passent en PREMIER. Sinon un id serveur
    // qui ressemble à un code interne (ex : Adulte a id=3 côté serveur, alors que notre code
    // interne "3" représente "juvénile") serait mal traduit et on enverrait Juvénile.
    /** Normalise un libellé de nomenclature pour un appariement ROBUSTE entre instances GeoNature :
     *  minuscule, sans accents, et sans un éventuel préfixe de code « NN - » (certaines instances
     *  préfixent le label par un code — ex. l'ANA pour le comportement atlas : « 30 - Nidification… »).
     *  Évite qu'un champ soit silencieusement omis de l'envoi parce que le libellé du serveur diffère
     *  du nôtre par ce préfixe ou les accents (repli hors-ligne ; en ligne, c'est l'id serveur qui sert). */
    internal fun normaliserLabelNomenclature(s: String): String =
        java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace(Regex("^\\s*\\d+\\s*-\\s*"), "")
            .lowercase()
            .trim()

    internal fun resolverIdNomenclature(
        code: String,
        type: String,
        labels: Map<String, String>,
        nomenclatures: Map<String, Map<String, Int>>
    ): Int? {
        // 1. Si le code est déjà un id_nomenclature présent dans la nomenclature serveur
        //    (ex : sélectionné via NomenclatureCache), on l'utilise tel quel.
        val idAsInt = code.toIntOrNull()
        if (idAsInt != null) {
            val serverIds = nomenclatures[type]?.values
            if (serverIds?.contains(idAsInt) == true) {
                return idAsInt
            }
        }

        // 2. Sinon, si c'est un de nos codes internes ("0", "1", "2"…), on résout par le label.
        if (labels.containsKey(code)) {
            val label = normaliserLabelNomenclature(labels[code]!!)
            val resolu = nomenclatures[type]?.get(label)
            if (resolu == null) {
                android.util.Log.w(
                    TAG_MEDIA,
                    "Nomenclature $type : label '$label' (code interne '$code') absent de la nomenclature serveur → champ omis du payload"
                )
            }
            return resolu
        }

        // 3. Fallback : résolution via le texte brut.
        val resolu = nomenclatures[type]?.get(normaliserLabelNomenclature(code))
        if (resolu == null) {
            android.util.Log.w(
                TAG_MEDIA,
                "Nomenclature $type : code '$code' non résolu (ni id serveur, ni label connu, ni texte brut) → champ omis du payload"
            )
        }
        return resolu
    }

    /** Cache de l'id_table_location pour pr_occtax.cor_counting_occtax (résolu au premier appel).
     *  Endpoint dédié : `/api/gn_commons/get_id_table_location/pr_occtax.cor_counting_occtax`
     *  renvoie directement un Long (cf. gn_mobile_core). */
    @Volatile private var idTableLocationCountingCache: Long? = null

    private fun resoudreIdTableLocationCounting(base: String, token: String?, cookies: String): Pair<Long?, String?> {
        idTableLocationCountingCache?.let { return Pair(it, null) }
        return try {
            val url = URL("$base/api/gn_commons/get_id_table_location/pr_occtax.cor_counting_occtax")
            val conn = HttpClient.get(url, token, cookies, 10000)
            val code = conn.responseCode
            if (code != 200) {
                val body = try { (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
                android.util.Log.e(TAG_MEDIA, "get_id_table_location HTTP $code : $body")
                return Pair(null, "get_id_table_location HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().readText().trim()
            // L'endpoint renvoie soit un nombre nu, soit un nombre entre guillemets, soit un JSON.
            val id = text.trim('"').toLongOrNull()
                ?: try { JSONObject(text).optLong("id_table_location", -1L).takeIf { it > 0 } } catch (_: Exception) { null }
            if (id != null && id > 0) {
                idTableLocationCountingCache = id
                android.util.Log.i(TAG_MEDIA, "id_table_location pour cor_counting_occtax = $id")
                Pair(id, null)
            } else {
                android.util.Log.e(TAG_MEDIA, "Réponse get_id_table_location non parsable : $text")
                Pair(null, "Réponse get_id_table_location non parsable : ${text.take(100)}")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG_MEDIA, "Exception get_id_table_location", e)
            Pair(null, "Exception : ${e.message}")
        }
    }

    /** Variante monitoring de l'upload média : résout l'id_table_location depuis le
     *  `schema_dot_table` du champ medias (ex. `gn_monitoring.t_base_visits`) et passe un
     *  `uuid_attached_row` pour rattacher le média à l'objet créé côté gn_commons.t_medias.
     *  Diffère du flot OCCTAX qui embarque le media dans le payload du counting via media_id.
     *  Retourne (succes, messageErreur). */
    suspend fun uploaderMediaMonitoring(
        config: GeoNatureConfig,
        mediaPaths: List<String>,
        schemaDotTable: String,
        uuidAttachedRow: String,
        titre: String,
        author: String,
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        if (mediaPaths.isEmpty()) return@withContext Pair(true, null)
        val base = config.urlServeur.trim().trimEnd('/')
        val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
            ?: return@withContext Pair(false, "Authentification GeoNature échouée")
        val (token, _, cookies) = auth
        // id_table_location résolu une seule fois (même table cible pour tous les médias du champ).
        val (idTableLoc, errTable) = resoudreIdTableLocationPour(base, token, cookies, schemaDotTable)
        if (idTableLoc == null) return@withContext Pair(false, errTable ?: "id_table_location introuvable pour $schemaDotTable")
        // id_nomenclature_media_type : NOT NULL côté gn_commons.t_medias — son ABSENCE faisait
        // partir le POST en 500 opaque (« photo en échec », bug terrain ; l'objet, lui, était
        // créé). Résolution identique au flux OCCTAX : cache TYPE_MEDIA (labels stables entre
        // instances), repli sur la résolution live si le cache est vide.
        val typesMedia = fr.ariegenature.geomys.store.NomenclatureCache.get("TYPE_MEDIA")
            .associate { normaliserLabelNomenclature(it.label) to it.id }
            .ifEmpty { resolverNomenclatures(base, token, cookies, "TYPE_MEDIA") }
        // Upload séquentiel de chaque fichier, tous rattachés au même objet (uuid_attached_row).
        var nbOk = 0
        var premiereErreur: String? = null
        mediaPaths.forEachIndexed { i, mediaPath ->
            val mimeHint = try {
                val path = android.net.Uri.parse(mediaPath).path ?: ""
                java.net.URLConnection.guessContentTypeFromName(path) ?: "image/jpeg"
            } catch (_: Exception) { "image/jpeg" }
            val (json, err) = uploaderMediaFile(
                base = base, token = token, cookies = cookies,
                mediaPath = mediaPath, author = author,
                titre = if (mediaPaths.size > 1) "$titre (${i + 1})" else titre,
                idTableLocation = idTableLoc, idTypeMedia = idTypeMediaPour(typesMedia, mimeHint),
                uuidAttachedRow = uuidAttachedRow,
            )
            if (json != null) nbOk++ else if (premiereErreur == null) premiereErreur = err
        }
        if (nbOk == mediaPaths.size) Pair(true, null)
        else Pair(false, "$nbOk/${mediaPaths.size} média(s) envoyé(s)" + (premiereErreur?.let { " — $it" } ?: ""))
    }

    /** Mappe un type mime → id_nomenclature TYPE_MEDIA via les labels stables entre instances
     *  (« photo », « audio », « vidéo », variantes « fichier local » / « lien web »).
     *  [typesMedia] = label lowercase → id_nomenclature. Null si introuvable. */
    private fun idTypeMediaPour(typesMedia: Map<String, Int>, mime: String): Int? {
        val candidats = when {
            mime.startsWith("image/") -> listOf("photo", "photo (fichier local)", "photo (lien web)")
            mime.startsWith("audio/") -> listOf("audio", "audio (fichier local)", "audio (lien web)")
            mime.startsWith("video/") -> listOf("vidéo", "vidéo (fichier local)", "vidéo (lien web)", "video")
            else -> emptyList()
        }
        return candidats.firstNotNullOfOrNull { typesMedia[it] }
    }

    /** Résout l'id_table_location pour un `schema.table` arbitraire (ex.
     *  `gn_monitoring.t_base_visits`). Pas de cache : un même client peut envoyer des saisies
     *  pour plusieurs types d'objet (visit, observation, …) — un cache global se contenterait
     *  du premier hit. Pour le volume actuel (1 saisie = 1 ou 0 média), 1 appel HTTP additionnel
     *  n'est pas critique. */
    private fun resoudreIdTableLocationPour(
        base: String, token: String?, cookies: String, schemaDotTable: String,
    ): Pair<Long?, String?> = try {
        val url = URL("$base/api/gn_commons/get_id_table_location/$schemaDotTable")
        val conn = HttpClient.get(url, token, cookies, 10000)
        val code = conn.responseCode
        if (code != 200) {
            val body = try { (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
            Pair(null, "get_id_table_location/$schemaDotTable HTTP $code : ${body ?: ""}")
        } else {
            val text = conn.inputStream.bufferedReader().readText().trim()
            val id = text.trim('"').toLongOrNull()
                ?: try { JSONObject(text).optLong("id_table_location", -1L).takeIf { it > 0 } } catch (_: Exception) { null }
            if (id != null && id > 0) Pair(id, null)
            else Pair(null, "Réponse get_id_table_location non parsable : ${text.take(100)}")
        }
    } catch (e: Exception) {
        Pair(null, "Exception get_id_table_location : ${e.message}")
    }

    /** Upload multipart d'un fichier média vers `/api/gn_commons/media` (singulier).
     *  Retourne le JSON Media renvoyé par le serveur (à inclure dans le counting JSON), ou null si échec. */
    private fun uploaderMediaFile(
        base: String, token: String?, cookies: String,
        mediaPath: String, author: String, titre: String,
        idTableLocation: Long, idTypeMedia: Int?,
        /** Optionnel : rattache le média à un objet en passant son uuid (champ `uuid_attached_row`
         *  côté gn_commons.t_medias). Utilisé pour les médias monitoring liés à une visite, etc. */
        uuidAttachedRow: String? = null,
    ): Pair<JSONObject?, String?> {
        val file = try {
            val parsed = android.net.Uri.parse(mediaPath)
            val path = parsed.path ?: throw Exception("path null")
            java.io.File(path)
        } catch (e: Exception) {
            android.util.Log.e(TAG_MEDIA, "URI fichier invalide : $mediaPath", e)
            return Pair(null, "URI fichier invalide : $mediaPath")
        }
        if (!file.exists() || !file.canRead()) {
            android.util.Log.e(TAG_MEDIA, "Fichier inaccessible : ${file.absolutePath}")
            return Pair(null, "Fichier inaccessible : ${file.absolutePath}")
        }

        val mediaMime = (java.net.URLConnection.guessContentTypeFromName(file.name) ?: "image/jpeg")
        val boundary = "----GeoMysBoundary${System.currentTimeMillis()}"
        val url = URL("$base/api/gn_commons/media")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setRequestProperty("Accept", "application/json")
        // Pas de réutilisation keep-alive : évite le re-POST silencieux d'HttpURLConnection
        // sur connexion périmée → média uploadé en double (cf. HttpClient.postJson).
        conn.setRequestProperty("Connection", "close")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)

        fun part(field: String, value: String, sb: StringBuilder) {
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Disposition: form-data; name=\"").append(field).append("\"\r\n\r\n")
            sb.append(value).append("\r\n")
        }

        val prefix = StringBuilder().apply {
            part("id_table_location", idTableLocation.toString(), this)
            idTypeMedia?.let { part("id_nomenclature_media_type", it.toString(), this) }
            uuidAttachedRow?.takeIf { it.isNotEmpty() }?.let { part("uuid_attached_row", it, this) }
            part("author", author, this)
            part("title_fr", titre, this)
            part("description_fr", "Saisie GeoMys mobile", this)
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(file.name).append("\"\r\n")
            append("Content-Type: ").append(mediaMime).append("\r\n\r\n")
        }
        val suffix = "\r\n--$boundary--\r\n"

        return try {
            conn.outputStream.use { out ->
                out.write(prefix.toString().toByteArray(Charsets.UTF_8))
                file.inputStream().use { it.copyTo(out) }
                out.write(suffix.toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().readText()
                android.util.Log.i(TAG_MEDIA, "POST /api/gn_commons/media → $code OK (${file.length()} octets)")
                Pair(try { JSONObject(body) } catch (_: Exception) { JSONObject() }, null)
            } else {
                val body = try { (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText() } catch (_: Exception) { null }
                android.util.Log.e(TAG_MEDIA, "POST /api/gn_commons/media → HTTP $code : $body")
                Pair(null, "HTTP $code : ${body?.take(200) ?: "(corps vide)"}")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG_MEDIA, "Exception upload média", e)
            Pair(null, "Exception : ${e.message}")
        } finally {
            conn.disconnect() // libère la connexion à chaque média (lot multi-photos).
        }
    }

    /** DELETE /api/occtax/OCCTAX/releve/{id} — rollback d'un relevé créé dont aucune
     *  occurrence n'a abouti. Retourne true si le serveur a accepté la suppression
     *  (HTTP 2xx ou 404 = déjà absent). Pattern gn_mobile_occtax officiel. */
    private fun tenterSupprimerReleve(
        base: String, token: String?, cookies: String, idReleve: Int,
    ): Boolean = try {
        val url = URL("$base/api/occtax/OCCTAX/releve/$idReleve")
        val conn = HttpClient.delete(url, token, cookies, 15000)
        val code = conn.responseCode
        code in 200..299 || code == 404
    } catch (_: Exception) {
        false
    }

    /** DELETE /api/gn_commons/media/{id} — rollback d'un média uploadé sur gn_commons
     *  quand le relevé/occurrence parent est lui-même rollback. Évite des médias orphelins
     *  côté serveur. */
    private fun tenterSupprimerMedia(
        base: String, token: String?, cookies: String, idMedia: Int,
    ): Boolean = try {
        val url = URL("$base/api/gn_commons/media/$idMedia")
        val conn = HttpClient.delete(url, token, cookies, 15000)
        val code = conn.responseCode
        code in 200..299 || code == 404
    } catch (_: Exception) {
        false
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

    // Charge les nomenclatures d'un type : label_default minuscule → id_nomenclature.
    // Même logique qu'OrniTrace : les labels sont stables d'une instance GeoNature à l'autre,
    // contrairement aux cd_nomenclature qui varient.
    private fun resolverNomenclatures(base: String, token: String?, cookies: String, type: String): Map<String, Int> {
        val cached = nomenclatureCache.entries
            .filter { it.key.startsWith("$type:") }
            .associate { it.key.removePrefix("$type:") to it.value }
        if (cached.isNotEmpty()) return cached

        fun get(urlStr: String): String? = try {
            val conn = HttpClient.get(URL(urlStr), token, cookies, 10000)
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } catch (e: Exception) { null }

        fun buildLabelMap(array: JSONArray): Map<String, Int> {
            val map = mutableMapOf<String, Int>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optInt("id_nomenclature", -1).takeIf { it > 0 } ?: continue
                val label = normaliserLabelNomenclature(
                    item.optString("label_default", "").ifEmpty { item.optString("label_fr", "") })
                if (label.isNotEmpty()) map[label] = id
            }
            return map
        }

        fun extractArray(text: String): JSONArray? {
            try {
                val arr = JSONArray(text)
                // Cas : tableau de type-descripteurs avec "values" imbriqué
                arr.optJSONObject(0)?.optJSONArray("values")?.let { if (it.length() > 0) return it }
                return arr
            } catch (_: Exception) {}
            val obj = try { JSONObject(text) } catch (_: Exception) { return null }
            for (key in listOf("items", "data", "results", "values", "nomenclatures")) {
                val arr = obj.optJSONArray(key)
                if (arr != null && arr.length() > 0) return arr
            }
            return null
        }

        return try {
            // Étape 1 : récupérer id_type via /nomenclature/{TYPE}
            val text1 = get("$base/api/nomenclatures/nomenclature/$type") ?: return emptyMap()
            val typeObj = try { JSONObject(text1) }
                catch (_: Exception) { try { JSONArray(text1).optJSONObject(0) } catch (_: Exception) { null } }
            val idType = typeObj?.optInt("id_type", -1)?.takeIf { it > 0 }

            // Étape 2 : récupérer les valeurs — plusieurs formats d'URL possibles
            val urls = buildList {
                if (idType != null) {
                    add("$base/api/nomenclatures/nomenclature?id_type=$idType&limit=200")
                    add("$base/api/nomenclatures/nomenclatures?id_type=$idType&limit=200")
                }
                add("$base/api/nomenclatures/nomenclature?code_nomenclature_type=$type&limit=200")
                add("$base/api/nomenclatures/nomenclatures?code_type=$type&limit=200")
            }

            var map = emptyMap<String, Int>()
            for (url in urls) {
                val text2 = get(url) ?: continue
                val array = extractArray(text2) ?: continue
                map = buildLabelMap(array)
                if (map.isNotEmpty()) break
            }

            map.forEach { (label, id) -> nomenclatureCache["$type:$label"] = id }
            map
        } catch (e: Exception) { emptyMap() }
    }
}
