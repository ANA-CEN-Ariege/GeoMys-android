package fr.ariegenature.geonat.network

import fr.ariegenature.geonat.model.Observation
import fr.ariegenature.geonat.model.Sortie
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.NomenclatureCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GeoNatureUpload {

    private val nomenclatureCache = mutableMapOf<String, Int>()

    // Correspondance code interne → label minuscule stable (même logique qu'OrniTrace)
    // Les cd_nomenclature varient d'une instance GeoNature à l'autre ; les labels sont stables.
    private val SEXE_LABELS     = mapOf("1" to "mâle", "2" to "femelle", "5" to "indéterminé")
    private val STADE_LABELS    = mapOf("2" to "adulte", "3" to "juvénile", "4" to "immature")
    private val METH_OBS_LABELS = mapOf(
        "0" to "vu", "1" to "entendu", "2" to "vu et entendu", "4" to "chant", "5" to "indices de présence"
    )
    private val STATUT_BIO_LABELS = mapOf(
        "1" to "reproduction", "2" to "pas de reproduction",
        "3" to "hibernation", "4" to "estivation", "5" to "non déterminé", "6" to "inconnu"
    )
    private val ETA_BIO_LABELS = mapOf("1" to "vivant", "2" to "mort", "3" to "signe d'activité")
    private val PREUVE_EXIST_LABELS = mapOf("0" to "non", "1" to "oui", "2" to "non acquise", "3" to "inconnu")
    private val OBJ_DENBR_LABELS = mapOf(
        "1" to "individu", "2" to "couple", "3" to "nid", "4" to "famille", "5" to "groupe"
    )
    private val TYP_DENBR_LABELS = mapOf("1" to "exact", "2" to "estimé", "3" to "minimum", "4" to "maximum")
    private val COMPORTEMENT_LABELS = mapOf(
        "1"  to "chant",
        "2"  to "chasse/alimentation",
        "3"  to "repos",
        "4"  to "déplacement",
        "5"  to "passage en vol",
        "6"  to "migration",
        "7"  to "halte migratoire",
        "8"  to "hivernage",
        "9"  to "nourrissage des jeunes",
        "10" to "territorial",
        "11" to "accouplement",
        "12" to "30 - nidification possible",
        "13" to "40 - nidification probable",
        "14" to "50 - nidification certaine",
        "15" to "inconnu"
    )
    private val METH_DETERMIN_LABELS = mapOf(
        "1" to "examen visuel à distance",
        "2" to "examen auditif direct",
        "3" to "examen visuel sur photo ou vidéo",
        "4" to "examen auditif avec transformation électronique",
        "5" to "examen visuel de l'individu en main",
        "6" to "autre méthode de détermination"
    )

    suspend fun envoyer(sortie: Sortie, config: GeoNatureConfig): Triple<Int, Int, Int?> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, idRole, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)

            val obsValides = sortie.observations.filter { it.cdNom != null }
            if (obsValides.isEmpty()) throw GNErreur.AucuneObservationCompatible()

            val datasetId = config.idDataset.trim().toIntOrNull()?.takeIf { it > 0 }
                ?: throw GNErreur.EnvoiEchoue(0, "id_dataset invalide : \"${config.idDataset}\"")

            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val heureFmt = SimpleDateFormat("HH:mm", Locale.US)

            var nbCrees = 0
            var premierIdReleve: Int? = null
            var derniereErreur: String? = null
            var dernierCodeErreur: Int = 0
            // Relevés créés côté serveur pour lesquels le POST de l'occurrence a échoué.
            // Ils restent vides sur GeoNature et doivent être signalés à l'utilisateur.
            val relevesOrphelins = mutableListOf<Int>()

            val nomenclatures = mutableMapOf<String, Map<String, Int>>()
            val typesVoulus = listOf("METH_OBS", "STATUT_OBS", "SEXE", "STADE_VIE", "STATUT_BIO", "ETA_BIO",
                "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN")

            // Si le cache est vide, on tente une synchro automatique
            if (!NomenclatureCache.estDisponible) {
                try { GeoNatureSync.synchroniserNomenclatures(config) } catch (_: Exception) {}
            }

            for (type in typesVoulus) {
                val fromCache = NomenclatureCache.get(type)
                nomenclatures[type] = if (fromCache.isNotEmpty()) {
                    fromCache.associate { it.label.lowercase() to it.id }
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
                // Plage temporelle couvrant toutes les obs du groupe (date_min/max).
                val dateMin = Date(groupe.minOf { it.date })
                val dateMax = Date(groupe.maxOf { it.date })

                val properties = JSONObject().apply {
                    put("id_dataset", datasetId)
                    put("date_min", dateFmt.format(dateMin))
                    put("date_max", dateFmt.format(dateMax))
                    put("hour_min", heureFmt.format(dateMin))
                    put("hour_max", heureFmt.format(dateMax))
                    put("additional_fields", JSONObject())
                    put("meta_device_entry", "mobile")
                    put("t_occurrences_occtax", JSONArray())
                    if (idRole != null) {
                        put("id_digitiser", idRole)
                        put("observers", JSONArray().put(idRole))
                    }
                }

                val geometry = JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(lon).put(lat))

                val body1 = JSONObject()
                    .put("geometry", geometry)
                    .put("status", "to_sync")
                    .put("properties", properties)
                    .toString()

                val urlReleve = URL("$base/api/occtax/OCCTAX/only/releve")
                val conn1 = urlReleve.openConnection() as java.net.HttpURLConnection
                conn1.requestMethod = "POST"
                conn1.doOutput = true
                conn1.connectTimeout = 30000
                conn1.readTimeout = 30000
                conn1.setRequestProperty("Content-Type", "application/json")
                conn1.setRequestProperty("Accept", "application/json")
                if (token != null) conn1.setRequestProperty("Authorization", "Bearer $token")
                if (cookies.isNotEmpty()) conn1.setRequestProperty("Cookie", cookies)
                OutputStreamWriter(conn1.outputStream).use { it.write(body1) }

                val code1 = conn1.responseCode
                if (code1 !in 200..299) {
                    val bodyErr = try { (conn1.errorStream ?: conn1.inputStream)?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    dernierCodeErreur = code1
                    derniereErreur = parseErreur(code1, bodyErr)
                    continue
                }
                val resp1Text = try { conn1.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
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
                for (obs in groupe) {
                    val occ = buildOccurrence(obs, nomenclatures)

                    val urlOcc = URL("$base/api/occtax/OCCTAX/releve/$idReleve/occurrence")
                    val conn2 = urlOcc.openConnection() as java.net.HttpURLConnection
                    conn2.requestMethod = "POST"
                    conn2.doOutput = true
                    conn2.connectTimeout = 30000
                    conn2.readTimeout = 30000
                    conn2.setRequestProperty("Content-Type", "application/json")
                    conn2.setRequestProperty("Accept", "application/json")
                    if (token != null) conn2.setRequestProperty("Authorization", "Bearer $token")
                    if (cookies.isNotEmpty()) conn2.setRequestProperty("Cookie", cookies)
                    OutputStreamWriter(conn2.outputStream).use { it.write(occ.toString()) }

                    val code2 = conn2.responseCode
                    if (code2 in 200..299) {
                        nbCrees++
                        nbReussisGroupe++
                    } else {
                        val bodyErr = try { (conn2.errorStream ?: conn2.inputStream)?.bufferedReader()?.readText() } catch (_: Exception) { null }
                        dernierCodeErreur = code2
                        derniereErreur = parseErreur(code2, bodyErr)
                    }
                }

                // Relevé orphelin = créé côté serveur mais aucune occurrence n'a abouti.
                if (nbReussisGroupe == 0) relevesOrphelins.add(idReleve)
            }

            if (nbCrees == 0) {
                val msg = buildString {
                    append(derniereErreur ?: "Aucun relevé créé")
                    if (relevesOrphelins.isNotEmpty()) {
                        append(" — ${relevesOrphelins.size} relevé(s) vide(s) créé(s) côté GeoNature ")
                        append("(id : ${relevesOrphelins.joinToString(", ")}), à supprimer manuellement.")
                    }
                }
                throw GNErreur.EnvoiEchoue(dernierCodeErreur, msg)
            }
            Triple(nbCrees, obsValides.size, premierIdReleve)
        }

    private fun buildOccurrence(
        obs: Observation,
        nomenclatures: Map<String, Map<String, Int>>
    ): JSONObject {
        // Counting #0 — issu des champs flat de Observation (saisie mono-taxon ou 1er dénombrement multi).
        val counting0 = buildCounting(
            nombreMin = obs.nombre,
            nombreMax = obs.nombreMax ?: obs.nombre,
            sexe = obs.sexe,
            stadeVie = obs.stadeVie,
            objDenbr = obs.objDenbr,
            typDenbr = obs.typDenbr,
            uuidSinp = obs.uuidSinpCounting0,
            nomenclatures = nomenclatures,
        )
        val countings = JSONArray().put(counting0)
        // Countings supplémentaires (mode multi-taxon).
        for (d in obs.denombrementsAdditionnels) {
            countings.put(buildCounting(
                nombreMin = d.nombreMin,
                nombreMax = d.nombreMax,
                sexe = d.sexe,
                stadeVie = d.stadeVie,
                objDenbr = d.objDenbr,
                typDenbr = d.typDenbr,
                uuidSinp = d.uuidSinp,
                nomenclatures = nomenclatures,
            ))
        }

        return JSONObject().apply {
            put("cd_nom", obs.cdNom!!)
            put("nom_cite", obs.espece)
            if (obs.notes.isNotEmpty()) put("comment", obs.notes)
            put("cor_counting_occtax", countings)
            val codeTechnique = obs.techniqueObs ?: "0"
            resolverIdNomenclature(codeTechnique, "METH_OBS", METH_OBS_LABELS, nomenclatures)
                ?.let { put("id_nomenclature_obs_technique", it) }
            obs.statutObs?.let { code ->
                resolverIdNomenclature(code, "STATUT_OBS", emptyMap(), nomenclatures)
                    ?.let { put("id_nomenclature_observation_status", it) }
            }
            obs.statutBio?.let { code ->
                resolverIdNomenclature(code, "STATUT_BIO", STATUT_BIO_LABELS, nomenclatures)
                    ?.let { put("id_nomenclature_bio_status", it) }
            }
            obs.etaBio?.let { code ->
                resolverIdNomenclature(code, "ETA_BIO", ETA_BIO_LABELS, nomenclatures)
                    ?.let { put("id_nomenclature_bio_condition", it) }
            }
            obs.preuveExist?.let { code ->
                resolverIdNomenclature(code, "PREUVE_EXIST", PREUVE_EXIST_LABELS, nomenclatures)
                    ?.let { put("id_nomenclature_exist_proof", it) }
            }
            obs.preuveNumerique?.takeIf { it.isNotEmpty() }?.let { put("digital_proof", it) }
            obs.preuveNonNumerique?.takeIf { it.isNotEmpty() }?.let { put("non_digital_proof", it) }
            obs.comportement?.let { code ->
                resolverIdNomenclature(code, "OCC_COMPORTEMENT", COMPORTEMENT_LABELS, nomenclatures)
                    ?.let { put("id_nomenclature_behaviour", it) }
            }
            obs.methDetermin?.let { code ->
                resolverIdNomenclature(code, "METH_DETERMIN", METH_DETERMIN_LABELS, nomenclatures)
                    ?.let { put("id_nomenclature_determination_method", it) }
            }
            obs.determinateur?.takeIf { it.isNotEmpty() }?.let { put("determiner", it) }
        }
    }

    private fun buildCounting(
        nombreMin: Int,
        nombreMax: Int,
        sexe: String?,
        stadeVie: String?,
        objDenbr: String?,
        typDenbr: String?,
        uuidSinp: String?,
        nomenclatures: Map<String, Map<String, Int>>,
    ): JSONObject = JSONObject().apply {
        put("count_min", nombreMin)
        put("count_max", nombreMax.coerceAtLeast(nombreMin))
        sexe?.let { code ->
            resolverIdNomenclature(code, "SEXE", SEXE_LABELS, nomenclatures)
                ?.let { put("id_nomenclature_sex", it) }
        }
        stadeVie?.let { code ->
            resolverIdNomenclature(code, "STADE_VIE", STADE_LABELS, nomenclatures)
                ?.let { put("id_nomenclature_life_stage", it) }
        }
        objDenbr?.let { code ->
            resolverIdNomenclature(code, "OBJ_DENBR", OBJ_DENBR_LABELS, nomenclatures)
                ?.let { put("id_nomenclature_obj_count", it) }
        }
        typDenbr?.let { code ->
            resolverIdNomenclature(code, "TYP_DENBR", TYP_DENBR_LABELS, nomenclatures)
                ?.let { put("id_nomenclature_type_count", it) }
        }
        uuidSinp?.takeIf { it.isNotEmpty() }?.let { put("unique_id_sinp_occtax", it) }
    }

    // Résout un code interne ou un id_nomenclature direct vers l'id GeoNature
    private fun resolverIdNomenclature(
        code: String,
        type: String,
        labels: Map<String, String>,
        nomenclatures: Map<String, Map<String, Int>>
    ): Int? {
        // 1. Si c'est un de nos codes internes ("0", "1", "2"...), on DOIT résoudre par le label
        if (labels.containsKey(code)) {
            val label = labels[code]!!.lowercase()
            return nomenclatures[type]?.get(label)
        }

        // 2. Si le code est déjà un id_nomenclature (ex: sélectionné via NomenclatureCache)
        // On vérifie s'il existe dans la nomenclature récupérée du serveur
        val idAsInt = code.toIntOrNull()
        if (idAsInt != null) {
            val serverIds = nomenclatures[type]?.values
            if (serverIds?.contains(idAsInt) == true) {
                return idAsInt
            }
        }

        // 3. Sinon résolution via le texte brut (fallback)
        return nomenclatures[type]?.get(code.lowercase())
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
            val conn = URL(urlStr).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } catch (e: Exception) { null }

        fun buildLabelMap(array: JSONArray): Map<String, Int> {
            val map = mutableMapOf<String, Int>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optInt("id_nomenclature", -1).takeIf { it > 0 } ?: continue
                val label = item.optString("label_default", "")
                    .ifEmpty { item.optString("label_fr", "") }
                    .lowercase()
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
