package fr.ariegenature.geonat.network

import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.TaxRefCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GeoNatureDataset(val id: Int, val nom: String)
data class GeoNatureListe(val id: Int, val nom: String)
/** Observateur (utilisateur GeoNature) chargé via `/api/users/menu/<id_liste>`. */
data class GeoNatureObservateur(val idRole: Int, val nomComplet: String)

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

object GeoNatureBrowse {

    suspend fun chargerDatasets(config: GeoNatureConfig): List<GeoNatureDataset> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _) = GeoNatureAuth.login(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)

            // Filtre côté serveur : ne récupère que les datasets actifs (active=true côté
            // GeoNature). On filtre aussi côté client en lisant le champ "active" au cas où
            // l'instance serveur ignore le paramètre.
            val url = URL("$base/api/meta/datasets?active=true")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode != 200) throw GNErreur.EnvoiEchoue(conn.responseCode, "Impossible de charger les jeux de données")

            val rawText = conn.inputStream.bufferedReader().readText()
            val parsed = try { JSONArray(rawText) } catch (_: Exception) {
                try {
                    val obj = JSONObject(rawText)
                    obj.optJSONArray("data") ?: obj.optJSONArray("items") ?: obj.optJSONArray("results") ?: JSONArray()
                } catch (_: Exception) { JSONArray() }
            }

            val result = mutableListOf<GeoNatureDataset>()
            for (i in 0 until parsed.length()) {
                val d = parsed.getJSONObject(i)
                val id = d.optInt("id_dataset", -1)
                val nom = d.optString("dataset_name", "")
                // active peut être absent (anciennes instances) — dans ce cas on garde.
                val actif = if (d.has("active") && !d.isNull("active")) d.optBoolean("active", true) else true
                if (id > 0 && nom.isNotEmpty() && actif) result.add(GeoNatureDataset(id, nom))
            }
            // Tri alphabétique insensible à la casse + diacritiques pour un classement
            // intuitif quel que soit l'ordre serveur.
            result.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.FRANCE).apply {
                strength = java.text.Collator.PRIMARY
            }) { it.nom })
        }

    suspend fun chargerListesTaxons(config: GeoNatureConfig): List<GeoNatureListe> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val url = URL("$base/api/taxhub/api/biblistes")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) throw GNErreur.EnvoiEchoue(code, "Listes taxons : HTTP $code")
            val text = conn.inputStream.bufferedReader().readText()
            val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                val obj = JSONObject(text)
                obj.optJSONArray("data") ?: obj.optJSONArray("items") ?: obj.optJSONArray("results")
                    ?: throw GNErreur.EnvoiEchoue(code, "Listes taxons : format JSON inattendu")
            }
            val result = mutableListOf<GeoNatureListe>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optInt("id_liste", -1)
                val nom = item.optString("nom_liste", "")
                if (id > 0 && nom.isNotEmpty()) result.add(GeoNatureListe(id, nom))
            }
            result.sortedBy { it.nom }
        }

    /** Récupère la liste des observateurs GeoNature via `/api/users/roles` (tous les utilisateurs,
     *  hors groupes). Pas de filtre par menu UsersHub : l'app récupère tout, l'utilisateur choisit
     *  son observateur par défaut dans la config. */
    suspend fun chargerObservateurs(config: GeoNatureConfig): List<GeoNatureObservateur> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)

            val url = URL("$base/api/users/roles")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
            val code = conn.responseCode
            if (code != 200) throw GNErreur.EnvoiEchoue(code, "Observateurs : HTTP $code")

            val text = conn.inputStream.bufferedReader().readText()
            val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                val obj = JSONObject(text)
                obj.optJSONArray("data") ?: obj.optJSONArray("items") ?: obj.optJSONArray("results")
                    ?: throw GNErreur.EnvoiEchoue(code, "Observateurs : format JSON inattendu")
            }
            val result = mutableListOf<GeoNatureObservateur>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val idRole = item.optInt("id_role", -1).takeIf { it > 0 } ?: continue
                if (item.optBoolean("groupe", false)) continue
                val prenom = item.optString("prenom_role", "").trim()
                val nom = item.optString("nom_role", "").trim()
                val nomComplet = listOf(prenom, nom).filter { it.isNotEmpty() }.joinToString(" ")
                    .ifEmpty { item.optString("nom_complet", "") }
                if (nomComplet.isNotEmpty()) result.add(GeoNatureObservateur(idRole, nomComplet))
            }
            result.sortedBy { it.nomComplet.lowercase() }
        }

    suspend fun recupererObsExplorer(
        config: GeoNatureConfig,
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double
    ): List<ObsExplorer> = withContext(Dispatchers.IO) {
        val base = config.urlServeur.trim().trimEnd('/')
        val (token, _) = GeoNatureAuth.login(base, config.login, config.motDePasse)
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
        val regneParCdNom = TaxRefCache.tousLesRegnes()
        val groupe1ParCdNom = TaxRefCache.tousLesGroupes1()
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
                // group1_inpn n'est généralement pas exposé par la synthèse → on retombe
                // sur le cache TaxRef indexé par cd_nom pour discriminer mollusques vs autres invertébrés.
                val group1Synthese = props.optString("group1_inpn", "")
                fun mapperTaxon(group2: String, cdNom: Int): Taxon {
                    when (group2) {
                        "Oiseaux"    -> return Taxon.OISEAU
                        "Mammifères" -> return Taxon.MAMMIFERE
                        "Reptiles"   -> return Taxon.REPTILE
                        "Amphibiens" -> return Taxon.BATRACIEN
                        "Poissons"   -> return Taxon.POISSON
                        "Insectes"   -> return Taxon.INSECTE
                    }
                    val cdNomStr = cdNom.toString()
                    val group1 = group1Synthese.ifEmpty { groupe1ParCdNom[cdNomStr] ?: "" }
                    if (group1 == "Mollusques") return Taxon.MOLLUSQUE
                    return when (regneParCdNom[cdNomStr]) {
                        "Fungi"   -> Taxon.FONGE
                        "Plantae" -> Taxon.PLANTE
                        else      -> Taxon.INVERTEBRES
                    }
                }

                val taxon: Taxon
                if (group.isNotEmpty()) {
                    taxon = mapperTaxon(group, props.optInt("cd_nom", -1))
                } else {
                    val cdNom = props.optInt("cd_nom", -1).takeIf { it > 0 } ?: continue
                    val cachedGroupe = groupeParCdNom[cdNom.toString()]
                    if (cachedGroupe != null) {
                        taxon = mapperTaxon(cachedGroupe, cdNom)
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
}