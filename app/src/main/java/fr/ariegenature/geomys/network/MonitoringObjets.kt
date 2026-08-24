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

import fr.ariegenature.geomys.network.MonitoringApi.MonitoringEnfant
import fr.ariegenature.geomys.network.MonitoringApi.MonitoringObjet
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.MonitoringCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL

/** Objets de la hiérarchie monitoring (sites, groupes de sites, visites, observations…) :
 *  drill-down enfants/fiches via `/api/monitorings/object/…` (+ cache disque), aplatissement
 *  des propriétés, heuristiques de nommage, labels/genres/chemins résolus depuis le cache
 *  local, formatage de géométrie et tri des listes. Extrait de [MonitoringApi] (découpage
 *  du god-object) — [MonitoringApi.chargerEnfants] et [MonitoringApi.chargerObjet] restent
 *  les façades appelées par les écrans. */
object MonitoringObjets {

    /** GET /api/monitorings/object/<module_code>/module?depth=1 — récupère le module et la liste
     *  de ses enfants directs avec leurs propriétés brutes. Map `object_type → [enfants]`, map
     *  vide si aucun enfant. **Throw** [GNErreur.AuthEchouee] si l'auth tombe, et
     *  [GNErreur.EnvoiEchoue] avec le code HTTP sur 403/404/500 — l'appelant peut humaniser via
     *  [humaniserErreurReseau]. */
    suspend fun chargerEnfants(config: GeoNatureConfig, moduleCode: String): Map<String, List<MonitoringEnfant>> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val text = try {
                val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                if (auth == null) {
                    MonitoringCache.getJson(MonitoringCache.keyEnfants(moduleCode)) ?: throw GNErreur.AuthEchouee(401)
                } else {
                    val (token, _, cookies) = auth
                    val url = URL("$base/api/monitorings/object/$moduleCode/module?depth=1")
                    val conn = HttpClient.get(url, token, cookies, 15000)
                    val code = conn.responseCode
                    if (code != 200) throw GNErreur.EnvoiEchoue(code, "Enfants du module $moduleCode")
                    val brut = conn.inputStream.bufferedReader().readText()
                    // Valider AVANT de mettre en cache : un 200 non-JSON (portail captif, page
                    // d'erreur proxy) écrit tel quel empoisonnait le cache offline — resservi à
                    // chaque repli jusqu'au prochain fetch réussi (audit N6).
                    runCatching { JSONObject(brut) }.getOrNull()?.let {
                        MonitoringCache.setJson(MonitoringCache.keyEnfants(moduleCode), brut)
                    }
                    brut
                }
            } catch (e: IOException) {
                MonitoringCache.getJson(MonitoringCache.keyEnfants(moduleCode)) ?: throw e
            }
            val obj = try { JSONObject(text) } catch (_: Exception) {
                throw GNErreur.EnvoiEchoue(0, "Enfants $moduleCode : JSON illisible")
            }
            val children = obj.optJSONObject("children") ?: return@withContext emptyMap<String, List<MonitoringEnfant>>()
            val result = linkedMapOf<String, List<MonitoringEnfant>>()
            val it = children.keys()
            while (it.hasNext()) {
                val type = it.next()
                val arr = children.optJSONArray(type) ?: continue
                val liste = mutableListOf<MonitoringEnfant>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optInt("id", item.optInt("${type}_id", -1))
                    val props = item.optJSONObject("properties")
                    val proprietesPlates = aplatirProprietes(props)
                    val nom = extraireNomHeuristique(proprietesPlates, type, id)
                    val geoJson = item.optJSONObject("geometry")?.toString()
                    liste.add(MonitoringEnfant(id, nom, proprietesPlates, geoJson))
                }
                result[type] = liste
            }
            result
        }

    /** Aplatit le bloc `properties` d'un objet monitoring en `Map<String, String>`. Filtre les
     *  valeurs null/objets/tableaux : on garde uniquement les scalaires (string, number, bool)
     *  utiles à l'affichage. */
    internal fun aplatirProprietes(props: JSONObject?): Map<String, String> {
        if (props == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        val it = props.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = props.opt(k)
            when (v) {
                null, JSONObject.NULL -> { /* skip */ }
                is String -> if (v.isNotEmpty()) map[k] = v
                is Number, is Boolean -> map[k] = v.toString()
                else -> { /* on ignore arrays/objects pour l'instant */ }
            }
        }
        return map
    }

    /** Fallback heuristique pour le nom d'un enfant quand le schéma n'est pas disponible.
     *  Essaie `base_<type>_name`, `<type>_name`, `base_site_name`, `name`, `label`. Sinon `#id`. */
    internal fun extraireNomHeuristique(proprietes: Map<String, String>, type: String, id: Int): String {
        val candidats = listOf("base_${type}_name", "${type}_name", "base_site_name", "name", "label")
        for (c in candidats) proprietes[c]?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (id > 0) "#$id" else "—"
    }

    /** Résout le label "humain" d'un objet serveur à partir de la fiche en cache local.
     *  Utilisé par la liste des saisies en attente pour afficher le nom du parent au lieu
     *  de "#id". Retourne null si la fiche n'a pas été mise en cache, ou si l'heuristique
     *  ne trouve qu'un fallback "#id". Pas d'appel réseau. */
    fun labelObjetEnCache(moduleCode: String, objectType: String, id: Int): String? {
        if (id <= 0) return null
        val json = MonitoringCache.getJson(MonitoringCache.keyObjet(moduleCode, objectType, id))
            ?: return null
        return try {
            val obj = JSONObject(json)
            val props = aplatirProprietes(obj.optJSONObject("properties"))
            val nom = extraireNomHeuristique(props, objectType, id)
            nom.takeIf { it != "#$id" && it != "—" }
        } catch (_: Exception) { null }
    }

    /** Lit dans le schéma cache de [moduleCode] le `label` humain d'un type d'objet (par ex.
     *  "Site", "Station", "Visite"). Retourne null si le schéma n'est pas en cache ou si le
     *  type n'a pas de label déclaré. Pas d'appel réseau. */
    fun labelTypeEnCache(moduleCode: String, type: String): String? {
        val json = MonitoringCache.getJson(MonitoringCache.keySchema(moduleCode)) ?: return null
        return try {
            val v = JSONObject(json).optJSONObject(type) ?: return null
            v.optString("label", "").takeIf { it.isNotEmpty() }
                ?: v.optString("label_list", "").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /** Lit dans le schéma cache de [moduleCode] le `genre` ("M"/"F") d'un type d'objet, pour
     *  accorder les phrases (« cette visite » / « ce passage »). Null si absent. Pas de réseau. */
    fun genreTypeEnCache(moduleCode: String, type: String): String? {
        val json = MonitoringCache.getJson(MonitoringCache.keySchema(moduleCode)) ?: return null
        return try {
            JSONObject(json).optJSONObject(type)?.optString("genre", "")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /** Libellé d'action « Nouvelle visite » / « Nouveau passage » / « Nouvel inventaire » pour
     *  créer un objet de type [type] dans [moduleCode], accordé au genre déclaré par le schéma
     *  (label serveur via [labelTypeEnCache], genre via [genreTypeEnCache]). Repli « Nouvelle
     *  entrée » si le type n'a pas de label en cache. Sert aux écrans carte/fiche/suivi. */
    fun libelleNouveau(moduleCode: String, type: String): String {
        val mot = labelTypeEnCache(moduleCode, type)?.lowercase() ?: return "Nouvelle entrée"
        val masculin = genreTypeEnCache(moduleCode, type).equals("M", ignoreCase = true)
        val voyelle = mot.firstOrNull()?.let {
            it in setOf('a', 'à', 'â', 'e', 'é', 'è', 'ê', 'i', 'î', 'o', 'ô', 'u', 'ù', 'û', 'h', 'y')
        } == true
        val adj = when { !masculin -> "Nouvelle"; voyelle -> "Nouvel"; else -> "Nouveau" }
        return "$adj $mot"
    }

    /** Lit dans le schéma cache de [moduleCode] le couple (parent_type, id_field_name du
     *  parent) pour un type donné. Retourne null si le schéma n'est pas dispo ou si l'objet
     *  n'a pas de parent. L'idFieldName est porté par le SCHÉMA DU PARENT — c'est le nom de
     *  la propriété qui contient l'id du parent dans la fiche enfant. */
    private fun parentTypeEtIdField(moduleCode: String, type: String): Pair<String, String>? {
        val json = MonitoringCache.getJson(MonitoringCache.keySchema(moduleCode)) ?: return null
        return try {
            val schema = JSONObject(json)
            val v = schema.optJSONObject(type) ?: return null
            val parentType = v.optString("parent_type", "")
                .ifEmpty { v.optJSONArray("parent_types")?.optString(0, "").orEmpty() }
                .takeIf { it.isNotEmpty() } ?: return null
            val parentObj = schema.optJSONObject(parentType) ?: return null
            val idField = parentObj.optString("id_field_name", "")
                .takeIf { it.isNotEmpty() } ?: return null
            parentType to idField
        } catch (_: Exception) { null }
    }

    /** Remonte la chaîne des ancêtres serveur d'un objet en s'appuyant uniquement sur le
     *  cache local (schéma + fiches). Retourne la liste des (type, label) du parent direct
     *  vers le plus haut ancêtre. Liste vide si l'objet n'a pas de parent ou si le cache ne
     *  permet pas de résoudre la chaîne. Safety net à 5 niveaux pour éviter une boucle. */
    fun chaineParentsEnCache(
        moduleCode: String,
        objectType: String,
        id: Int,
    ): List<Triple<String, Int, String>> {
        val result = mutableListOf<Triple<String, Int, String>>()
        var typeCourant = objectType
        var idCourant = id
        val visites = mutableSetOf<Pair<String, Int>>()
        var profondeur = 0
        while (visites.add(typeCourant to idCourant) && profondeur++ < 5) {
            val (parentType, idField) = parentTypeEtIdField(moduleCode, typeCourant) ?: break
            val ficheJson = MonitoringCache.getJson(
                MonitoringCache.keyObjet(moduleCode, typeCourant, idCourant),
            ) ?: break
            val parentIdStr = try {
                val props = aplatirProprietes(JSONObject(ficheJson).optJSONObject("properties"))
                props[idField]
            } catch (_: Exception) { null } ?: break
            val parentId = parentIdStr.toIntOrNull() ?: break
            val label = labelObjetEnCache(moduleCode, parentType, parentId)
                ?: "$parentType #$parentId"
            result.add(Triple(parentType, parentId, label))
            typeCourant = parentType
            idCourant = parentId
        }
        return result
    }

    /** GET /api/monitorings/object/<module_code>/<object_type>/<id>?depth=1 — fiche d'un objet
     *  (site, visite, observation, …) avec ses propriétés et ses enfants directs.
     *  **Throw** [GNErreur.AuthEchouee] sur défaut d'auth, [GNErreur.EnvoiEchoue] sur HTTP != 200
     *  ou parse cassé (le code est ainsi exploitable côté UI pour humaniser le message). */
    suspend fun chargerObjet(
        config: GeoNatureConfig,
        moduleCode: String,
        objectType: String,
        id: Int,
    ): MonitoringObjet =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val text = try {
                val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                if (auth == null) {
                    MonitoringCache.getJson(MonitoringCache.keyObjet(moduleCode, objectType, id))
                        ?: throw GNErreur.AuthEchouee(401)
                } else {
                    val (token, _, cookies) = auth
                    val url = URL("$base/api/monitorings/object/$moduleCode/$objectType/$id?depth=1")
                    val conn = HttpClient.get(url, token, cookies, 15000)
                    val code = conn.responseCode
                    if (code != 200) throw GNErreur.EnvoiEchoue(code, "$objectType #$id")
                    val brut = conn.inputStream.bufferedReader().readText()
                    // Valider AVANT de cacher — même protection anti-portail-captif que
                    // chargerEnfants (audit N6).
                    runCatching { JSONObject(brut) }.getOrNull()?.let {
                        MonitoringCache.setJson(MonitoringCache.keyObjet(moduleCode, objectType, id), brut)
                    }
                    brut
                }
            } catch (e: IOException) {
                MonitoringCache.getJson(MonitoringCache.keyObjet(moduleCode, objectType, id)) ?: throw e
            }
            val obj = try { JSONObject(text) } catch (_: Exception) {
                throw GNErreur.EnvoiEchoue(0, "$objectType #$id : JSON illisible")
            }
            val proprietes = aplatirProprietes(obj.optJSONObject("properties"))
            val enfants = linkedMapOf<String, List<MonitoringEnfant>>()
            obj.optJSONObject("children")?.let { childrenObj ->
                val it = childrenObj.keys()
                while (it.hasNext()) {
                    val ctype = it.next()
                    val arr = childrenObj.optJSONArray(ctype) ?: continue
                    val liste = mutableListOf<MonitoringEnfant>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val cid = item.optInt("id", item.optInt("${ctype}_id", -1))
                        val cprops = aplatirProprietes(item.optJSONObject("properties"))
                        val cnom = extraireNomHeuristique(cprops, ctype, cid)
                        val cgeo = item.optJSONObject("geometry")?.toString()
                        liste.add(MonitoringEnfant(cid, cnom, cprops, cgeo))
                    }
                    enfants[ctype] = liste
                }
            }
            val geoObj = obj.optJSONObject("geometry")
            MonitoringObjet(
                type = objectType,
                id = id,
                moduleCode = moduleCode,
                proprietes = proprietes,
                enfants = enfants,
                geometrie = formatGeometrie(geoObj),
                geometrieGeoJson = geoObj?.toString(),
            )
        }

    /** Convertit un objet GeoJSON en libellé court pour affichage. Point → "lat°N/S, lon°E/W",
     *  Polygon/MultiPolygon → "Polygone (N sommets)", autres → le type GeoJSON brut. */
    internal fun formatGeometrie(geo: JSONObject?): String? {
        if (geo == null) return null
        val type = geo.optString("type", "").ifEmpty { return null }
        val coords = geo.opt("coordinates")
        return when (type) {
            "Point" -> {
                val arr = coords as? JSONArray ?: return type
                if (arr.length() < 2) return type
                val lon = arr.optDouble(0, Double.NaN)
                val lat = arr.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return type
                val ns = if (lat >= 0) "N" else "S"
                val ew = if (lon >= 0) "E" else "W"
                "%.5f° %s, %.5f° %s".format(Math.abs(lat), ns, Math.abs(lon), ew)
            }
            "Polygon" -> {
                val nb = (coords as? JSONArray)?.optJSONArray(0)?.length() ?: 0
                if (nb > 0) "Polygone ($nb sommets)" else "Polygone"
            }
            "MultiPolygon" -> "MultiPolygone"
            "LineString" -> {
                val nb = (coords as? JSONArray)?.length() ?: 0
                if (nb > 0) "Ligne ($nb points)" else "Ligne"
            }
            "MultiPoint" -> {
                val nb = (coords as? JSONArray)?.length() ?: 0
                if (nb > 0) "$nb points" else "MultiPoint"
            }
            else -> type
        }
    }

    /** Trie une liste d'enfants selon les critères déclarés par le schéma (champ `sorts`).
     *  Tente une comparaison numérique d'abord, fallback comparaison de strings (les dates ISO
     *  YYYY-MM-DD se trient correctement en string). Sans critères : tri alpha par nom. */
    fun trierEnfants(enfants: List<MonitoringEnfant>, sorts: List<Pair<String, String>>): List<MonitoringEnfant> {
        if (sorts.isEmpty()) return enfants.sortedBy { it.nom.lowercase() }
        val comp = Comparator<MonitoringEnfant> { a, b ->
            var r = 0
            for ((prop, dir) in sorts) {
                val va = a.proprietes[prop] ?: ""
                val vb = b.proprietes[prop] ?: ""
                val da = va.toDoubleOrNull()
                val db = vb.toDoubleOrNull()
                r = if (da != null && db != null) da.compareTo(db) else va.compareTo(vb)
                if (dir == "desc") r = -r
                if (r != 0) break
            }
            r
        }
        return enfants.sortedWith(comp)
    }
}
