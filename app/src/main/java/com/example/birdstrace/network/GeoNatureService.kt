package com.example.birdstrace.network

import com.example.birdstrace.model.Observation
import com.example.birdstrace.model.Sortie
import com.example.birdstrace.model.Taxon
import com.example.birdstrace.store.GeoNatureConfig
import com.example.birdstrace.store.NomenclatureCache
import com.example.birdstrace.store.NomValeur
import com.example.birdstrace.store.TaxRefCache
import com.example.birdstrace.store.TaxRefEntry
import com.example.birdstrace.store.TaxrefRestriction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GeoNatureDataset(val id: Int, val nom: String)
data class GeoNatureListe(val id: Int, val nom: String)

data class ObsExplorer(
    val nomCite: String,
    val nomVern: String,
    val nomSci: String,
    val latitude: Double,
    val longitude: Double,
    val date: String,
    val nombre: Int,
    val taxon: Taxon = Taxon.OISEAU
)

sealed class GNErreur(message: String) : Exception(message) {
    class UrlInvalide : GNErreur("URL du serveur invalide")
    class AuthEchouee(code: Int) : GNErreur("Authentification refusée (HTTP $code)")
    class EnvoiEchoue(code: Int, msg: String) : GNErreur("Envoi échoué HTTP $code : $msg")
    class AucuneObservationCompatible : GNErreur("Aucune observation n'a de cd_nom résolu.")
}

object GeoNatureService {

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

    suspend fun testerConnexion(config: GeoNatureConfig): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val base = config.urlServeur.trim().trimEnd('/')
                val url = URL("$base/api/auth/login")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("login", config.login).put("password", config.motDePasse).toString()
                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val code = conn.responseCode
                val ct = conn.getHeaderField("Content-Type") ?: ""
                if (!ct.contains("json")) {
                    return@withContext Pair(false, "Mauvaise URL : réponse HTML reçue. Essayez d'ajouter ou retirer /GeoNature à l'URL.")
                }
                when (code) {
                    200 -> {
                        val json = JSONObject(conn.inputStream.bufferedReader().readText())
                        val token = extractToken(json)
                        if (token != null) Pair(true, "Connexion réussie")
                        else Pair(false, "Token absent de la réponse")
                    }
                    401, 403 -> Pair(false, "Identifiants incorrects (HTTP $code)")
                    else -> Pair(false, "Erreur serveur HTTP $code")
                }
            } catch (e: Exception) {
                Pair(false, "Impossible de joindre le serveur : ${e.message}")
            }
        }

    suspend fun chargerDatasets(config: GeoNatureConfig): List<GeoNatureDataset> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _) = login(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)

            val url = URL("$base/api/meta/datasets")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode != 200) throw GNErreur.EnvoiEchoue(conn.responseCode, "Impossible de charger les jeux de données")

            val parsed = try { JSONArray(conn.inputStream.bufferedReader().readText()) } catch (e: Exception) {
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                obj.optJSONArray("data") ?: obj.optJSONArray("items") ?: obj.optJSONArray("results") ?: JSONArray()
            }

            val result = mutableListOf<GeoNatureDataset>()
            for (i in 0 until parsed.length()) {
                val d = parsed.getJSONObject(i)
                val id = d.optInt("id_dataset", -1)
                val nom = d.optString("dataset_name", "")
                if (id > 0 && nom.isNotEmpty()) result.add(GeoNatureDataset(id, nom))
            }
            result
        }

    suspend fun chargerListesTaxons(config: GeoNatureConfig): List<GeoNatureListe> =
        withContext(Dispatchers.IO) {
            try {
                val base = config.urlServeur.trim().trimEnd('/')
                val url = URL("$base/api/taxhub/api/biblistes")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) return@withContext emptyList()
                val text = conn.inputStream.bufferedReader().readText()
                val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                    val obj = JSONObject(text)
                    obj.optJSONArray("data") ?: obj.optJSONArray("items") ?: obj.optJSONArray("results") ?: return@withContext emptyList()
                }
                val result = mutableListOf<GeoNatureListe>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = item.optInt("id_liste", -1)
                    val nom = item.optString("nom_liste", "")
                    if (id > 0 && nom.isNotEmpty()) result.add(GeoNatureListe(id, nom))
                }
                result.sortedBy { it.nom }
            } catch (_: Exception) { emptyList() }
        }

    suspend fun envoyer(sortie: Sortie, config: GeoNatureConfig): Triple<Int, Int, Int?> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, idRole, cookies) = loginAvecCookies(base, config.login, config.motDePasse)
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

            val nomenclatures = mutableMapOf<String, Map<String, Int>>()
            for (type in listOf("METH_OBS", "SEXE", "STADE_VIE", "STATUT_BIO", "ETA_BIO",
                                "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN")) {
                nomenclatures[type] = resolverNomenclatures(base, token, cookies, type)
            }

            for (obs in obsValides) {
                val d = Date(obs.date)
                val properties = JSONObject().apply {
                    put("id_dataset", datasetId)
                    put("date_min", dateFmt.format(d))
                    put("date_max", dateFmt.format(d))
                    put("hour_min", heureFmt.format(d))
                    put("hour_max", heureFmt.format(d))
                    put("additional_fields", JSONObject())
                    put("meta_device_entry", "mobile")
                    put("t_occurrences_occtax", JSONArray())
                    idRole?.let { put("observers", JSONArray().put(it)) }
                }

                val geometry = JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(obs.longitude).put(obs.latitude))

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
                    val body2 = try { (conn1.errorStream ?: conn1.inputStream)?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    derniereErreur = parseErreur(code1, body2)
                    continue
                }
                val resp1 = JSONObject(conn1.inputStream.bufferedReader().readText())
                val idReleve = resp1.optInt("id", -1).takeIf { it > 0 }
                    ?: resp1.optJSONObject("properties")?.optInt("id_releve_occtax", -1)?.takeIf { it > 0 }
                    ?: resp1.optInt("id_releve_occtax", -1).takeIf { it > 0 }
                if (idReleve == null) {
                    derniereErreur = "id absent de la réponse relevé"
                    continue
                }
                if (premierIdReleve == null) premierIdReleve = idReleve

                val counting = JSONObject().apply {
                    put("count_min", obs.nombre)
                    put("count_max", obs.nombre)
                    obs.sexe?.let { code ->
                        resolverIdNomenclature(code, "SEXE", SEXE_LABELS, nomenclatures)
                            ?.let { put("id_nomenclature_sex", it) }
                    }
                    obs.stadeVie?.let { code ->
                        resolverIdNomenclature(code, "STADE_VIE", STADE_LABELS, nomenclatures)
                            ?.let { put("id_nomenclature_life_stage", it) }
                    }
                    obs.objDenbr?.let { code ->
                        resolverIdNomenclature(code, "OBJ_DENBR", OBJ_DENBR_LABELS, nomenclatures)
                            ?.let { put("id_nomenclature_obj_count", it) }
                    }
                    obs.typDenbr?.let { code ->
                        resolverIdNomenclature(code, "TYP_DENBR", TYP_DENBR_LABELS, nomenclatures)
                            ?.let { put("id_nomenclature_type_count", it) }
                    }
                }

                val occ = JSONObject().apply {
                    put("cd_nom", obs.cdNom!!)
                    put("nom_cite", obs.espece)
                    if (obs.notes.isNotEmpty()) put("comment", obs.notes)
                    put("cor_counting_occtax", JSONArray().put(counting))
                    val codeTechnique = obs.techniqueObs ?: "0"
                    resolverIdNomenclature(codeTechnique, "METH_OBS", METH_OBS_LABELS, nomenclatures)
                        ?.let { put("id_nomenclature_obs_technique", it) }
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
                } else {
                    val body2 = try { (conn2.errorStream ?: conn2.inputStream)?.bufferedReader()?.readText() } catch (_: Exception) { null }
                    derniereErreur = parseErreur(code2, body2)
                }
            }

            if (nbCrees == 0) throw GNErreur.EnvoiEchoue(0, derniereErreur ?: "Aucun relevé créé")
            Triple(nbCrees, obsValides.size, premierIdReleve)
        }

    suspend fun verifierVersionTaxRef(config: GeoNatureConfig): String? =
        withContext(Dispatchers.IO) {
            try {
                val base = config.urlServeur.trim().trimEnd('/')
                val url = URL("$base/api/taxhub/api/taxref/version")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) return@withContext null
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                obj.opt("version")?.toString()
                    ?: obj.opt("taxref_version")?.toString()
                    ?: obj.opt("num_version")?.toString()
            } catch (e: Exception) { null }
        }

    suspend fun synchroniserTaxRef(
        config: GeoNatureConfig,
        progression: (Int, Int) -> Unit
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val listeId = config.taxaListeId.trim().toIntOrNull()?.takeIf { it > 0 }
            ?: return@withContext Pair(0, "Aucune liste de taxons configurée — saisissez un id_liste dans les paramètres GeoNature.")

        val base = config.urlServeur.trim().trimEnd('/')
        val pageSize = 1000
        val entrees = mutableMapOf<String, TaxRefEntry>()
        val groupeMap = mutableMapOf<Int, String>()
        val cdNomsOiseaux    = mutableSetOf<Int>()
        val cdNomsMammiferes = mutableSetOf<Int>()
        val cdNomsReptiles   = mutableSetOf<Int>()
        val cdNomsPlantes    = mutableSetOf<Int>()
        var totalRecu = 0
        var pagesRecues = 0

        progression(0, 1)

        var page = 1
        while (true) {
            try {
                val url = URL("$base/api/taxhub/api/taxref?orderby=cd_nom&fields=listes&id_liste=$listeId&limit=$pageSize&page=$page")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 60000
                conn.readTimeout = 60000
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code != 200) {
                    if (pagesRecues == 0) {
                        val preview = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null } ?: ""
                        return@withContext Pair(0, "Erreur HTTP $code — $preview")
                    }
                    break
                }
                val text = conn.inputStream.bufferedReader().readText()
                val array: JSONArray = try {
                    val obj = JSONObject(text)
                    obj.optJSONArray("items") ?: obj.optJSONArray("data") ?: obj.optJSONArray("results") ?: JSONArray(text)
                } catch (_: Exception) {
                    try { JSONArray(text) } catch (_: Exception) {
                        if (pagesRecues == 0) return@withContext Pair(0, "Réponse non-JSON : ${text.take(200)}")
                        break
                    }
                }

                if (array.length() == 0) break
                pagesRecues++
                totalRecu += array.length()
                progression(entrees.size, 0)

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val cdNom = item.optInt("cd_nom", -1).takeIf { it > 0 } ?: continue
                    val lbNom = item.optString("lb_nom", "").takeIf { it.isNotEmpty() } ?: continue
                    val nomVernRaw = if (item.isNull("nom_vern")) "" else item.optString("nom_vern", "")
                    val groupe = item.optString("group2_inpn", "")

                    for (partie in nomVernRaw.split(",")) {
                        val nomNettoye = partie.trim()
                            .replace(Regex("""\s*\((Le|La|Les|L'|L'|Un|Une)\)\s*$"""), "")
                        val cle = TaxRefCache.normaliser(nomNettoye)
                        if (cle.isNotEmpty()) entrees[cle] = TaxRefEntry(cdNom, lbNom, nomNettoye)
                    }
                    val cleSci = TaxRefCache.normaliser(lbNom)
                    if (cleSci.isNotEmpty()) entrees[cleSci] = TaxRefEntry(cdNom, lbNom, nomVernRaw.split(",").firstOrNull()?.trim())

                    if (groupe.isNotEmpty()) groupeMap[cdNom] = groupe
                    when (groupe) {
                        "Oiseaux"    -> cdNomsOiseaux.add(cdNom)
                        "Mammifères" -> cdNomsMammiferes.add(cdNom)
                        "Reptiles"   -> cdNomsReptiles.add(cdNom)
                        else         -> if (groupe.isNotEmpty()) cdNomsPlantes.add(cdNom)
                    }
                }

                if (array.length() < pageSize) break
                page++
            } catch (e: Exception) {
                if (pagesRecues == 0) return@withContext Pair(0, "Erreur réseau : ${e.message}")
                break
            }
        }

        if (pagesRecues == 0) return@withContext Pair(0, "Aucun résultat — la liste $listeId est peut-être vide ou inexistante.")
        if (entrees.isNotEmpty()) TaxRefCache.ajouter(entrees)
        if (groupeMap.isNotEmpty()) TaxRefCache.ajouterGroupes(groupeMap)
        val nbO = cdNomsOiseaux.size
        val nbM = cdNomsMammiferes.size
        val nbR = cdNomsReptiles.size
        val nbP = cdNomsPlantes.size
        val comptes = TaxRefCache.comptesGroupes.toMutableMap()
        if (nbO > 0) comptes["Oiseaux"]    = nbO
        if (nbM > 0) comptes["Mammifères"] = nbM
        if (nbR > 0) comptes["Reptiles"]   = nbR
        if (nbP > 0) comptes["Plantes"]    = nbP
        TaxRefCache.comptesGroupes = comptes
        verifierVersionTaxRef(config)?.let { TaxRefCache.versionSauvegardee = it }
        val msg = buildString {
            append("${entrees.size} taxons indexés — $nbO oiseaux")
            if (nbM > 0) append(", $nbM mammifères")
            if (nbR > 0) append(", $nbR reptiles")
            if (nbP > 0) append(", $nbP plantes")
        }
        Pair(entrees.size, msg)
    }

    suspend fun recupererObsExplorer(
        config: GeoNatureConfig,
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double
    ): List<ObsExplorer> = withContext(Dispatchers.IO) {
        val base = config.urlServeur.trim().trimEnd('/')
        val (token, _) = login(base, config.login, config.motDePasse)
            ?: throw GNErreur.AuthEchouee(401)

        val dateLow = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(System.currentTimeMillis() - 365L * 24 * 3600 * 1000))

        val bboxJson = """{"type":"Polygon","coordinates":[[[$minLon,$minLat],[$maxLon,$minLat],[$maxLon,$maxLat],[$minLon,$maxLat],[$minLon,$minLat]]]}"""
        val bboxEncoded = java.net.URLEncoder.encode(bboxJson, "UTF-8")

        val url = URL("$base/api/synthese/for_web?bounding_box=$bboxEncoded&date_low=$dateLow&limit=1000")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")

        if (conn.responseCode != 200) throw GNErreur.EnvoiEchoue(conn.responseCode, "Erreur synthèse")

        parseObsExplorer(conn.inputStream.bufferedReader().readText(), minLon, minLat, maxLon, maxLat)
    }

    private fun parseObsExplorer(
        text: String,
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double
    ): List<ObsExplorer> {
        val result = mutableListOf<ObsExplorer>()
        val cdNomsCache = TaxRefCache.tousLesCdNoms()
        val groupeParCdNom = TaxRefCache.tousLesGroupes()
        val groupesSupports = setOf("Oiseaux", "Mammifères", "Reptiles")
        try {
            val root = JSONObject(text)
            val array: JSONArray = root.optJSONArray("features")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("items")
                ?: root.optJSONArray("results")
                ?: return result

            for (i in 0 until array.length()) {
                val feat = array.getJSONObject(i)
                var lat = Double.NaN; var lon = Double.NaN
                feat.optJSONObject("geometry")?.optJSONArray("coordinates")?.let {
                    lon = it.optDouble(0, Double.NaN); lat = it.optDouble(1, Double.NaN)
                }
                val props = feat.optJSONObject("properties") ?: feat
                if (lat.isNaN()) lat = props.optDouble("latitude", Double.NaN).let {
                    if (it.isNaN()) props.optDouble("lat", Double.NaN) else it
                }
                if (lon.isNaN()) lon = props.optDouble("longitude", Double.NaN).let {
                    if (it.isNaN()) props.optDouble("lon", Double.NaN) else it
                }
                if (lat.isNaN() || lon.isNaN()) continue
                if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) continue

                val group = props.optString("group2_inpn").ifEmpty {
                    props.optString("taxonomy_group2_inpn").ifEmpty {
                        props.optString("group_inpn", "")
                    }
                }
                val taxon: Taxon
                if (group.isNotEmpty()) {
                    if (group !in groupesSupports) continue
                    taxon = when {
                        group.contains("Mammif", ignoreCase = true) -> Taxon.MAMMIFERE
                        group.contains("Reptile", ignoreCase = true) -> Taxon.REPTILE
                        else -> Taxon.OISEAU
                    }
                } else {
                    val cdNom = props.optInt("cd_nom", -1).takeIf { it > 0 } ?: continue
                    val cachedGroupe = groupeParCdNom[cdNom.toString()]
                    if (cachedGroupe != null) {
                        if (cachedGroupe !in groupesSupports) continue
                        taxon = when {
                            cachedGroupe.contains("Mammif", ignoreCase = true) -> Taxon.MAMMIFERE
                            cachedGroupe.contains("Reptile", ignoreCase = true) -> Taxon.REPTILE
                            else -> Taxon.OISEAU
                        }
                    } else {
                        if (cdNomsCache.isNotEmpty() && cdNom !in cdNomsCache) continue
                        taxon = Taxon.OISEAU
                    }
                }

                result.add(obsFromProps(props, lat, lon, taxon))
            }
        } catch (_: Exception) {}
        return result
    }

    private fun obsFromProps(props: JSONObject, lat: Double, lon: Double, taxon: Taxon = Taxon.OISEAU): ObsExplorer {
        val cdNom = props.optInt("cd_nom", -1)
        var nomVern = props.optString("nom_vern").ifEmpty { 
            props.optString("nom_vernaculaires").ifEmpty { 
                props.optString("common_name", "") 
            }
        }
        
        // Si GeoNature ne donne pas de nom vernaculaire, on cherche dans notre cache local
        if (nomVern.isEmpty() && cdNom > 0) {
            nomVern = TaxRefCache.getVernaculaireParCdNom(cdNom) ?: ""
        }

        return ObsExplorer(
            nomCite = props.optString("nom_cite").ifEmpty {
                props.optString("lb_nom").ifEmpty { props.optString("nom_valide", "Espèce inconnue") }
            },
            nomVern = nomVern,
            nomSci = props.optString("lb_nom").ifEmpty { props.optString("nom_cite", "") },
            latitude = lat,
            longitude = lon,
            date = props.optString("date_min").ifEmpty { props.optString("date_obs", "") },
            nombre = props.optInt("count_min", 1).coerceAtLeast(1),
            taxon = taxon
        )
    }

    // Résout un code interne ou un id_nomenclature direct vers l'id GeoNature
    private fun resolverIdNomenclature(
        code: String,
        type: String,
        labels: Map<String, String>,
        nomenclatures: Map<String, Map<String, Int>>
    ): Int? {
        // Si le code est déjà un id_nomenclature (depuis NomenclatureCache)
        code.toIntOrNull()?.let { return it }
        // Sinon résolution via label stable
        return nomenclatures[type]?.get(labels[code] ?: code.lowercase())
    }

    suspend fun synchroniserNomenclatures(config: GeoNatureConfig): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = loginAvecCookies(base, config.login, config.motDePasse)
                ?: return@withContext Pair(0, "Authentification échouée")

            try {
                val url = URL("$base/api/nomenclatures/nomenclatures/taxonomy")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)

                val code = conn.responseCode
                if (code != 200) return@withContext Pair(0, "HTTP $code")

                val text = conn.inputStream.bufferedReader().readText()
                val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                    val obj = JSONObject(text)
                    obj.optJSONArray("items") ?: obj.optJSONArray("data")
                        ?: return@withContext Pair(0, "Format inattendu")
                }

                val typesVoulus = setOf("METH_OBS", "SEXE", "STADE_VIE", "STATUT_BIO", "ETA_BIO",
                                        "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN")
                val result = mutableMapOf<String, List<NomValeur>>()

                for (i in 0 until array.length()) {
                    val typeObj = array.getJSONObject(i)
                    val mnem = typeObj.optString("mnemonique", "")
                    if (mnem !in typesVoulus) continue

                    val valuesArray = typeObj.optJSONArray("values") ?: continue
                    val valeurs = mutableListOf<NomValeur>()

                    for (j in 0 until valuesArray.length()) {
                        val v = valuesArray.getJSONObject(j)
                        val id = v.optInt("id_nomenclature", -1).takeIf { it > 0 } ?: continue
                        val label = v.optString("label_default", "").ifEmpty { v.optString("label_fr", "") }
                        if (label.isEmpty()) continue

                        val taxrefArray = v.optJSONArray("taxref")
                        val restrictions = mutableListOf<TaxrefRestriction>()
                        if (taxrefArray != null) {
                            for (k in 0 until taxrefArray.length()) {
                                val r = taxrefArray.getJSONObject(k)
                                val regne = r.optString("regne", r.optString("kingdom", ""))
                                val group2 = r.optString("group2_inpn", "")
                                restrictions.add(TaxrefRestriction(regne, group2))
                            }
                        }
                        valeurs.add(NomValeur(id, label, restrictions))
                    }

                    if (valeurs.isNotEmpty()) result[mnem] = valeurs
                }

                if (result.isNotEmpty()) NomenclatureCache.setAll(result)
                val total = result.values.sumOf { it.size }
                val resume = result.entries.joinToString(" | ") { (t, vals) ->
                    val nbTr = vals.count { it.taxref.isNotEmpty() }
                    "$t:${vals.size}val/${nbTr}taxref"
                }
                Pair(total, resume)
            } catch (e: Exception) {
                Pair(0, "Erreur : ${e.message}")
            }
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

    // Retourne (token, idRole, cookies) — cookies à renvoyer avec les appels suivants
    private fun loginAvecCookies(base: String, login: String, password: String): Triple<String?, Int?, String>? {
        return try {
            val url = URL("$base/api/auth/login")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("login", login).put("password", password).toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            if (conn.responseCode != 200) return null
            val ct = conn.getHeaderField("Content-Type") ?: ""
            if (!ct.contains("json")) return null
            // Capturer tous les cookies Set-Cookie (comme URLSession.ephemeral dans OrniTrace)
            val cookies = conn.headerFields["Set-Cookie"]
                ?.joinToString("; ") { it.substringBefore(";").trim() }
                ?: ""
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val token = extractToken(json)
            val userJson = json.optJSONObject("user")
            val idRole = userJson?.optInt("id_role", -1)?.takeIf { it > 0 }
            Triple(token, idRole, cookies)
        } catch (e: Exception) { null }
    }

    // Conservé pour testerConnexion et chargerDatasets
    private fun login(base: String, login: String, password: String): Pair<String?, Int?>? =
        loginAvecCookies(base, login, password)?.let { (token, idRole, _) -> Pair(token, idRole) }

    private fun extractToken(json: JSONObject): String? {
        val userJson = json.optJSONObject("user")
        return json.optString("access_token").takeIf { it.isNotEmpty() }
            ?: json.optString("token").takeIf { it.isNotEmpty() }
            ?: userJson?.optString("access_token").takeIf { !it.isNullOrEmpty() }
            ?: userJson?.optString("token").takeIf { !it.isNullOrEmpty() }
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