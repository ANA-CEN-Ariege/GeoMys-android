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

import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.TaxRefCache
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dataset GeoNature.
 *  - [idTaxaList] (`id_taxa_list` côté `gn_meta.t_datasets`) : liste UsersHub imposée.
 *  - [actif] : flag `active` côté serveur. On charge même les inactifs (un protocole de
 *    monitoring peut référencer un dataset inactif côté OCCTAX) — le filtrage est fait
 *    à l'affichage selon le contexte (config OCCTAX = actif seulement, monitoring = libre).
 *  - [moduleCodes] : codes des modules auxquels le dataset est rattaché côté serveur
 *    (tableau `modules` du payload). Vide si l'instance n'expose pas cette info. */
data class GeoNatureDataset(
    val id: Int,
    val nom: String,
    val idTaxaList: Int? = null,
    val actif: Boolean = true,
    val moduleCodes: List<String> = emptyList(),
)
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

    /** IDs des jeux de données sur lesquels l'utilisateur a le droit de **créer** des observations
     *  pour [moduleCode] (CRUVED C), via `POST /api/meta/datasets` body `{"create":"OCCTAX"}` —
     *  exactement le filtre du formulaire web (cf. app officielle gn_mobile_core). Sert à NE proposer
     *  en saisie que les datasets créables. Set vide en cas d'échec → pas de restriction (filet). */
    suspend fun chargerIdsDatasetsCreables(config: GeoNatureConfig, moduleCode: String = "OCCTAX"): Set<Int> =
        withContext(Dispatchers.IO) {
            try {
                val base = config.urlServeur.trim().trimEnd('/')
                val (token, _) = GeoNatureAuth.login(base, config.login, config.motDePasse) ?: return@withContext emptySet()
                // Read timeout généreux : sur les serveurs à beaucoup de jeux de données (2500+),
                // la sérialisation côté serveur dépasse largement 10s (cf. chargerDatasets).
                val conn = HttpClient.postJson(URL("$base/api/meta/datasets?active=true"), token, timeoutMs = 15000, readTimeoutMs = 90000)
                conn.outputStream.use { it.write("""{"create":"$moduleCode"}""".toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode != 200) return@withContext emptySet()
                // Streaming (cf. chargerDatasets) : on n'extrait que les id_dataset.
                java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { r -> lireDatasetsStream(r).mapTo(HashSet()) { it.id } }
                }
            } catch (_: Exception) { emptySet() }
        }

    suspend fun chargerDatasets(config: GeoNatureConfig): List<GeoNatureDataset> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _) = GeoNatureAuth.login(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)

            // Filtre serveur : `active=true` + `module_code=OCCTAX` (périmètre LECTURE — on garde
            // large pour que le cache serve aussi à la résolution des noms côté monitoring).
            // La restriction aux datasets CRÉABLES (CRUVED C) est appliquée à l'AFFICHAGE Occtax
            // via [chargerIdsDatasetsCreables] + GeoNatureConfig.datasetsCreablesOcctax.
            // `fields=modules` ramène le tableau des modules rattachés (utile au tracking).
            val url = URL("$base/api/meta/datasets?active=true&module_code=OCCTAX&fields=modules")
            // Read timeout généreux (90s) : `meta/datasets` n'est PAS paginé côté serveur et
            // `fields=modules` ajoute un joinedload + sérialisation imbriquée PAR jeu de données.
            // Sur un serveur à 2500+ jeux, la réponse dépasse les 10s par défaut → « blocage »/timeout.
            val conn = HttpClient.get(url, token, timeoutMs = 15000, readTimeoutMs = 90000)

            if (conn.responseCode != 200) throw GNErreur.EnvoiEchoue(conn.responseCode, "Impossible de charger les jeux de données")

            // Lecture en STREAMING (JsonReader) : on ne matérialise pas toute la réponse en une
            // String géante + JSONArray (crucial sur 2500+ jeux × fields=modules → réponse lourde).
            val result = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { lireDatasetsStream(it) }
            }
            // Tri alphabétique insensible à la casse + diacritiques pour un classement
            // intuitif quel que soit l'ordre serveur.
            result.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.FRANCE).apply {
                strength = java.text.Collator.PRIMARY
            }) { it.nom })
        }

    /** Lit en STREAMING la réponse `meta/datasets` : tolère le tableau à la racine OU enveloppé
     *  sous `data`/`items`/`results`. Ne charge jamais toute la réponse en mémoire d'un bloc. */
    private fun lireDatasetsStream(r: JsonReader): MutableList<GeoNatureDataset> {
        val result = mutableListOf<GeoNatureDataset>()
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> lireTableauDatasets(r, result)
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    if (result.isEmpty() && r.nextName() in setOf("data", "items", "results") &&
                        r.peek() == JsonToken.BEGIN_ARRAY
                    ) lireTableauDatasets(r, result) else r.skipValue()
                }
                r.endObject()
            }
            else -> r.skipValue()
        }
        return result
    }

    private fun lireTableauDatasets(r: JsonReader, dst: MutableList<GeoNatureDataset>) {
        r.beginArray()
        while (r.hasNext()) parseUnDataset(r)?.let(dst::add)
        r.endArray()
    }

    private fun parseUnDataset(r: JsonReader): GeoNatureDataset? {
        var id = -1; var nom = ""; var actif = true; var idTaxaList: Int? = null
        val modules = mutableListOf<String>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id_dataset" -> id = lireIntOuNull(r) ?: -1
                "dataset_name" -> nom = lireStringOuVide(r)
                "active" -> actif = lireBoolDefaut(r, true)
                "id_taxa_list" -> idTaxaList = lireIntOuNull(r)?.takeIf { it > 0 }
                "modules" -> if (r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray()
                    while (r.hasNext()) {
                        when (r.peek()) {
                            JsonToken.STRING -> r.nextString().takeIf { it.isNotEmpty() }?.let(modules::add)
                            JsonToken.BEGIN_OBJECT -> {
                                var mc = ""
                                r.beginObject()
                                while (r.hasNext()) { if (r.nextName() == "module_code") mc = lireStringOuVide(r) else r.skipValue() }
                                r.endObject()
                                if (mc.isNotEmpty()) modules.add(mc)
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endArray()
                } else r.skipValue()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return if (id > 0 && nom.isNotEmpty()) GeoNatureDataset(id, nom, idTaxaList, actif, modules) else null
    }

    private fun lireIntOuNull(r: JsonReader): Int? = when (r.peek()) {
        JsonToken.NUMBER -> r.nextInt()
        JsonToken.STRING -> r.nextString().toIntOrNull()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }

    private fun lireStringOuVide(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.NULL -> { r.nextNull(); "" }
        else -> { r.skipValue(); "" }
    }

    private fun lireBoolDefaut(r: JsonReader, defaut: Boolean): Boolean = when (r.peek()) {
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.STRING -> r.nextString().toBoolean()
        JsonToken.NULL -> { r.nextNull(); defaut }
        else -> { r.skipValue(); defaut }
    }

    suspend fun chargerListesTaxons(config: GeoNatureConfig): List<GeoNatureListe> =
        withContext(Dispatchers.IO) {
            val url = URL("${config.urlTaxhub}/api/biblistes")
            val conn = HttpClient.get(url, timeoutMs = 10000)
            val code = conn.responseCode
            if (code != 200) throw GNErreur.EnvoiEchoue(code, "Listes taxons : HTTP $code")
            val text = conn.inputStream.bufferedReader().readText()
            val array: JSONArray = text.parserTableauJson("data", "items", "results")
                ?: throw GNErreur.EnvoiEchoue(code, "Listes taxons : format JSON inattendu")
            val result = mutableListOf<GeoNatureListe>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optInt("id_liste", -1)
                val nom = item.optString("nom_liste", "")
                if (id > 0 && nom.isNotEmpty()) result.add(GeoNatureListe(id, nom))
            }
            result.sortedBy { it.nom }
        }

    /** Récupère la liste des observateurs Occtax.
     *
     *  Comme le **formulaire web Occtax** : on restreint à la LISTE D'OBSERVATEURS configurée du
     *  module (menu UsersHub `OCCTAX.id_observers_list`, via `/api/users/menu/<id>`) — liste curée,
     *  donc **sans les doublons de comptes** que renvoie `/api/users/roles` (un même « Prénom Nom »
     *  pouvant correspondre à plusieurs `id_role`). Repli sur `/api/users/roles` (tous les rôles) si
     *  la config ou le menu ne sont pas disponibles (compat. multi-serveurs). */
    suspend fun chargerObservateurs(config: GeoNatureConfig): List<GeoNatureObservateur> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)

            // Aligné sur le web : liste d'observateurs configurée du module si disponible.
            val idListe = idListeObservateursOcctax(base, token, cookies)
            if (idListe != null) {
                val depuisMenu = chargerObservateursDeMenu(base, token, cookies, idListe)
                if (!depuisMenu.isNullOrEmpty()) return@withContext depuisMenu
            }

            // Repli : tous les rôles (comportement historique).
            val url = URL("$base/api/users/roles")
            val conn = HttpClient.get(url, token, cookies, 10000)
            val code = conn.responseCode
            if (code != 200) throw GNErreur.EnvoiEchoue(code, "Observateurs : HTTP $code")

            val text = conn.inputStream.bufferedReader().readText()
            val array: JSONArray = text.parserTableauJson("data", "items", "results")
                ?: throw GNErreur.EnvoiEchoue(code, "Observateurs : format JSON inattendu")
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

    /** Id du menu UsersHub « liste d'observateurs » du module Occtax, lu dans la config serveur
     *  (`/api/gn_commons/config` → `OCCTAX.id_observers_list`) — la MÊME source que le web. null si
     *  absent/illisible (→ repli sur tous les rôles). */
    private fun idListeObservateursOcctax(base: String, token: String?, cookies: String): Int? =
        try {
            val conn = HttpClient.get(URL("$base/api/gn_commons/config"), token, cookies, 10000)
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().readText())
                .optJSONObject("OCCTAX")?.optInt("id_observers_list", -1)?.takeIf { it > 0 }
        } catch (_: Exception) { null }

    /** Observateurs d'une liste UsersHub (`/api/users/menu/<id>`) : `id_role` + `nom_complet`
     *  (format « NOM Prénom », tel qu'affiché par le web). null si échec → l'appelant retombe sur
     *  `/users/roles`. */
    private fun chargerObservateursDeMenu(
        base: String, token: String?, cookies: String, idListe: Int,
    ): List<GeoNatureObservateur>? =
        try {
            val conn = HttpClient.get(URL("$base/api/users/menu/$idListe"), token, cookies, 10000)
            if (conn.responseCode != 200) null
            else {
                val arr = conn.inputStream.bufferedReader().readText()
                    .parserTableauJson("data", "items", "results") ?: JSONArray()
                val res = mutableListOf<GeoNatureObservateur>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val idRole = item.optInt("id_role", -1).takeIf { it > 0 } ?: continue
                    val nomComplet = item.optString("nom_complet", "").trim().ifEmpty {
                        listOf(item.optString("nom_role", "").trim(), item.optString("prenom_role", "").trim())
                            .filter { it.isNotEmpty() }.joinToString(" ")
                    }
                    if (nomComplet.isNotEmpty()) res.add(GeoNatureObservateur(idRole, nomComplet))
                }
                res.sortedBy { it.nomComplet.lowercase() }
            }
        } catch (_: Exception) { null }

    suspend fun recupererObsExplorer(
        config: GeoNatureConfig,
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double
    ): List<ObsExplorer> = withContext(Dispatchers.IO) {
        val base = config.urlServeur.trim().trimEnd('/')
        val (token, _) = GeoNatureAuth.login(base, config.login, config.motDePasse)
            ?: throw GNErreur.AuthEchouee(401)

        // Profondeur d'historique pilotée serveur (settings.area_observation_duration), défaut 365 j.
        val dateMin = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(System.currentTimeMillis() - config.dureeObservationJours * 86_400_000L))

        // `/synthese/for_web` lit ses filtres dans le CORPS JSON (`request.json`) — comme le web.
        // En GET ils sont IGNORÉS (le serveur renvoyait les 1000 premières obs de TOUTE la synthèse,
        // ensuite filtrées seulement côté client). On POST donc :
        //  - `geoIntersection` : l'emprise visible, en **Feature** GeoJSON (le backend refuse une
        //    géométrie nue : seuls `Feature`/`FeatureCollection` sont acceptés) ;
        //  - `date_min` : borne basse (1 an).
        // `limit` reste en paramètre d'URL (lu dans request.args).
        val emprise = """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[$minLon,$minLat],[$maxLon,$minLat],[$maxLon,$maxLat],[$minLon,$maxLat],[$minLon,$minLat]]]}}"""
        val body = """{"geoIntersection":$emprise,"date_min":"$dateMin"}"""

        val url = URL("$base/api/synthese/for_web?limit=1000")
        val conn = HttpClient.postJson(url, token, timeoutMs = 20000)
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

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
            val array: JSONArray = text.parserTableauJson("features", "data", "items", "results")
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