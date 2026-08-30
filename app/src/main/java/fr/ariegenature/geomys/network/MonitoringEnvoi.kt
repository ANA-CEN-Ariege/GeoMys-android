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

import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** Envoi des saisies monitoring vers le serveur : POST de création d'objet
 *  (visite/observation/site…) avec normalisation du payload (types, padding Marshmallow,
 *  id_digitiser, id_dataset), vérification anti-doublon par uuid client, et résolution du
 *  dataset rattaché au protocole. Extrait de [MonitoringApi] (découpage du god-object) ;
 *  appelé principalement par [OutboxEnvoi]. */
object MonitoringEnvoi {

    /** Envoie une nouvelle visite (ou tout objet monitoring "saisissable") sur le serveur.
     *  Endpoint : `POST /api/monitorings/object/<moduleCode>/<objectType>`.
     *  Le body est un GeoJSON-like `{geometry, properties}` ; pour les visites, la
     *  géométrie est généralement héritée du parent côté serveur — on n'envoie donc pas
     *  de `geometry` par défaut.
     *
     *  [parentIdField] / [parentId] : nom du champ FK vers le parent (ex `id_base_site`)
     *  et son id. Posés directement dans `properties` (le serveur les attend là, pas
     *  dans l'URL).
     *
     *  [valeurs] : map code_propriété → valeur typée renvoyée par
     *  [fr.ariegenature.geomys.monitoring.form.FormulaireRenderer.lireValeurs]. Les types
     *  attendus côté serveur sont déduits ici par best-effort :
     *  - Int / Long / Number → number JSON
     *  - Boolean → boolean JSON
     *  - List<*> → JSONArray (cas SELECT_MULTIPLE, observers, etc.)
     *  - String → tentative parse Int sinon String JSON (les ids nomenclature/dataset
     *    voyagent en String depuis le renderer alors que le serveur les veut en number).
     *
     *  Retourne le `id` du nouvel objet créé via Result.success, ou une exception via
     *  Result.failure (auth, HTTP, parse). */
    /** Cherche parmi les enfants directs de {parentObjectType}/{parentId} un objet portant
     *  [uuid] dans son champ [uuidFieldName]. Anti-doublon : quand la RÉPONSE d'un POST s'est
     *  perdue (coupure/timeout après que le serveur a traité), la saisie est marquée en échec
     *  alors que l'objet existe — re-POSTer aveuglément le dupliquerait. Lecture LIVE
     *  exclusivement (jamais le cache : un cache antérieur au POST dirait « absent » à tort).
     *  Retourne l'id serveur si trouvé (0 si trouvé mais id illisible), null si absent.
     *  LANCE en cas d'erreur réseau/auth : l'appelant ne doit alors PAS re-POSTer. */
    suspend fun chercherEnfantParUuid(
        config: GeoNatureConfig,
        moduleCode: String,
        parentObjectType: String,
        parentId: Int,
        childObjectType: String,
        uuidFieldName: String,
        uuid: String,
    ): Int? = withContext(Dispatchers.IO) {
        val base = config.urlServeur.trim().trimEnd('/')
        val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
            ?: throw GNErreur.AuthEchouee(401)
        val (token, _, cookies) = auth
        val url = URL("$base/api/monitorings/object/$moduleCode/$parentObjectType/$parentId?depth=1")
        val conn = HttpClient.get(url, token, cookies, 15000)
        try {
            val code = HttpClient.lireCode(conn)
            if (code != 200) {
                throw GNErreur.EnvoiEchoue(code, "vérification anti-doublon ($parentObjectType #$parentId)")
            }
            val obj = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = obj.optJSONObject("children")?.optJSONArray(childObjectType)
                ?: return@withContext null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val props = MonitoringObjets.aplatirProprietes(item.optJSONObject("properties"))
                val uuidServeur = props[uuidFieldName].orEmpty()
                if (uuidServeur.equals(uuid, ignoreCase = true)) {
                    // Même heuristique d'id que chargerObjet, avec repli sur les id_* des props.
                    val id = item.optInt("id", item.optInt("${childObjectType}_id", -1))
                        .takeIf { it > 0 }
                        ?: props.entries.firstOrNull { (k, v) ->
                            k.startsWith("id_") && (v.toIntOrNull() ?: 0) > 0
                        }?.value?.toIntOrNull()
                    return@withContext id ?: 0
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    suspend fun envoyerVisite(
        config: GeoNatureConfig,
        moduleCode: String,
        objectType: String,
        parentIdField: String?,
        parentId: Int?,
        valeurs: Map<String, Any?>,
        nomsChampsSchema: Collection<String> = emptyList(),
        /** Codes des champs texte-libre dont la valeur String ne doit PAS être coercée en
         *  Int même si elle est numérique (cf. audit B6). Vide → coercition historique. */
        champsTexteLibre: Collection<String> = emptyList(),
        /** uuid pré-généré côté client à injecter dans le payload sous [uuidFieldName].
         *  Sert à connaître à l'avance l'uuid_attached_row pour l'upload média ultérieur. */
        uuidClient: String? = null,
        uuidFieldName: String? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val base = config.urlServeur.trim().trimEnd('/')
            val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)
            val (token, idRoleAuth, cookies) = auth

            val properties = JSONObject()
            // Lien au parent : injecté dans properties (le sélecteur de parent est masqué
            // côté UI car déjà choisi par le drill-down qui amène ici).
            if (!parentIdField.isNullOrEmpty() && parentId != null && parentId > 0) {
                properties.put(parentIdField, parentId)
            }
            for ((code, brut) in valeurs) {
                // Un champ texte-libre garde sa valeur String telle quelle : "42" dans un
                // commentaire ne doit pas partir en number (audit B6).
                properties.put(code, normaliserPourJson(brut, preserverString = code in champsTexteLibre))
            }
            // uuid pré-généré côté client (cas où on a un média à rattacher après création).
            // Injecté seulement si le caller a fourni le nom du champ — sinon on ne sait pas
            // où le mettre dans le payload et le serveur générera son propre uuid.
            if (!uuidClient.isNullOrEmpty() && !uuidFieldName.isNullOrEmpty() &&
                !properties.has(uuidFieldName)) {
                properties.put(uuidFieldName, uuidClient)
            }
            // `id_digitiser` : le serveur attend l'id_role de l'utilisateur qui enregistre.
            // Contrainte NOT NULL côté DB monitoring → un 500 silencieux sans ce champ.
            // Dérivé EN DIRECT du login courant (comme Occtax/OccHab) plutôt que de la copie
            // persistée `config.idRoleUtilisateur` : évite d'estampiller l'ancien auteur après un
            // changement de compte. Repli sur la valeur persistée si l'auth ne l'a pas renvoyé.
            val idRole = idRoleAuth ?: config.idRoleUtilisateur.takeIf { it > 0 }
            if (idRole != null && !properties.has("id_digitiser")) {
                properties.put("id_digitiser", idRole)
            }
            // `id_dataset` : NOT NULL côté table monitoring (FK gn_meta.t_datasets).
            // ⚠ Le dataset OCCTAX configuré dans l'app n'est PAS forcément valide ici —
            // chaque protocole monitoring a son propre dataset rattaché. Si le schéma ne
            // l'expose pas, on cherche dans le cache local le premier dataset rattaché
            // au module courant (champ `moduleCodes` du cache datasets, peuplé au sync
            // via /api/meta/datasets?fields=modules). Fallback sur le dataset OCCTAX global
            // seulement si rien trouvé (instances anciennes sans la relation).
            if (!properties.has("id_dataset")) {
                val idDatasetModule = trouverDatasetPourModule(config, moduleCode)
                // Fallback OCCTAX seulement s'il est réellement présent sur le serveur courant
                // (sinon FK invalide → 500 opaque). On trace l'usage du fallback pour diagnostic :
                // le dataset OCCTAX n'est pas forcément rattaché au protocole (risque de 403 CRUVED).
                val fallbackOcctax = config.idDataset.trim().toIntOrNull()
                    ?.takeIf { it > 0 && config.datasetAcceptablePourEnvoi(it) }
                val idDataset = idDatasetModule ?: fallbackOcctax
                if (idDatasetModule == null && fallbackOcctax != null) {
                    android.util.Log.w("MonitoringApi",
                        "id_dataset du module '$moduleCode' introuvable → fallback dataset OCCTAX $fallbackOcctax (peut être hors-protocole)")
                }
                if (idDataset != null) properties.put("id_dataset", idDataset)
            }
            // `visit_date_max` est en général NOT NULL en DB. Si le formulaire ne l'a pas
            // collecté (cas fréquent : une seule date dans l'UI), copie depuis visit_date_min.
            if (properties.has("visit_date_min") && !properties.has("visit_date_max")) {
                properties.put("visit_date_max", properties.opt("visit_date_min"))
            }
            // Nettoie les valeurs vides résiduelles côté String : un champ DATE non rempli
            // arrive à "" et casse le parse côté serveur ; pareil pour les heures, etc.
            // On retire les clés à "" pour laisser le serveur appliquer son défaut.
            val cleanedKeys = mutableListOf<String>()
            properties.keys().forEach { k -> cleanedKeys.add(k) }
            cleanedKeys.forEach { k ->
                val v = properties.opt(k)
                if (v is String && v.isBlank()) properties.remove(k)
            }
            // Padding : le serveur Marshmallow valide que TOUTES les propriétés du schéma
            // sont présentes dans le payload, y compris les champs techniques cachés à
            // l'UI (id_base_visit, id_module, medias, nb_observations, observers_txt…).
            // Les champs absents reçoivent null — ce que fait aussi le formulaire web,
            // sauf pour quelques champs typés array qui prennent `[]` par défaut.
            val champsArrayParDefaut = setOf("medias")
            nomsChampsSchema.forEach { k ->
                if (!properties.has(k)) {
                    if (k in champsArrayParDefaut) properties.put(k, JSONArray())
                    else properties.put(k, JSONObject.NULL)
                }
            }

            // Format simple `{properties: …}` — c'est exactement ce que le formulaire web
            // GeoNature envoie sur la même instance (vérifié via DevTools). Le wrapper
            // GeoJSON Feature complet déclenche un 500 silencieux côté serveur.
            val body = JSONObject().put("properties", properties)

            val urlStr = "$base/api/monitorings/object/$moduleCode/$objectType"
            val bodyStr = body.toString()
            // Payload loggé seulement en debug : il contient des données métier (observateurs,
            // commentaires, géoloc) qu'on ne veut pas exposer en clair dans logcat en production.
            if (fr.ariegenature.geomys.BuildConfig.DEBUG) {
                android.util.Log.i("MonitoringApi", "POST $urlStr\n  body=$bodyStr")
            }
            val conn = HttpClient.postJson(URL(urlStr), token, cookies, 20000)
            try {
                conn.outputStream.use { it.write(bodyStr.toByteArray(Charsets.UTF_8)) }

                val code = HttpClient.lireCode(conn)
                if (code !in 200..299) {
                    val erreur = try {
                        conn.errorStream?.bufferedReader()?.readText()
                    } catch (_: Exception) { null }
                    android.util.Log.w("MonitoringApi", "POST $urlStr → HTTP $code\n  réponse=$erreur")
                    throw GNErreur.EnvoiEchoue(code, erreur?.take(500) ?: "pas de message")
                }
                val text = conn.inputStream.bufferedReader().readText()
                val obj = JSONObject(text)
                // L'API retourne le nouvel objet créé sous forme GeoJSON Feature. L'id se trouve
                // soit à la racine, soit dans `properties`, soit dans `id_<type>` du payload.
                obj.optInt("id", -1)
                    .takeIf { it > 0 }
                    ?: obj.optJSONObject("properties")?.optInt("id", -1)?.takeIf { it > 0 }
                    ?: obj.optJSONObject("properties")?.optInt("id_$objectType", -1)?.takeIf { it > 0 }
                    ?: 0
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Convertit une valeur typée (issue du form renderer) en valeur acceptable par
     *  JSONObject.put — préserve les types Number/Boolean, tente de parser les Strings
     *  numériques en Int (pour matcher ce qu'attend le serveur sur id_nomenclature etc.),
     *  sérialise List<*> en JSONArray. */
    /** Cherche le premier dataset actif rattaché au [moduleCode] côté serveur. D'abord
     *  dans le cache local OCCTAX (si par chance le dataset cumule plusieurs modules),
     *  puis via un appel live `/api/meta/datasets?module_code=<m>&active=true` qui
     *  retourne directement les datasets du module monitoring (filtré CRUVED). */
    private fun trouverDatasetPourModule(config: GeoNatureConfig, moduleCode: String): Int? {
        // 1) Cache local
        val json = config.datasetsCacheJson.takeIf { it.isNotEmpty() }
        if (json != null) {
            try {
                val t = object : com.google.gson.reflect.TypeToken<List<GeoNatureDataset>>() {}.type
                val datasets: List<GeoNatureDataset>? = com.google.gson.Gson().fromJson(json, t)
                datasets?.firstOrNull { moduleCode in it.moduleCodes }?.let { return it.id }
            } catch (_: Exception) { /* fallback ci-dessous */ }
        }
        // 2) Appel live filtré par module (les datasets monitoring ne sont pas dans le
        // cache OCCTAX par défaut — sync app filtre module_code=OCCTAX).
        val arr = chargerDatasetsDuModuleLive(config, moduleCode) ?: return null
        return arr.optJSONObject(0)?.optInt("id_dataset", -1)?.takeIf { it > 0 }
    }

    /** Datasets ACTIFS rattachés à [moduleCode], en appel LIVE (le cache OCCTAX ne les
     *  contient pas — sync filtré `module_code=OCCTAX`). Essaie les deux casses du code
     *  module (routes Flask sensibles à la casse). Retourne le tableau brut (id_dataset,
     *  id_taxa_list, …) non vide, ou null si injoignable/vide. BLOQUANT : à appeler depuis
     *  Dispatchers.IO. Partagé entre [trouverDatasetPourModule] (id du dataset pour le
     *  payload de visite) et NouvelleVisiteFragment (id_taxa_list pour les champs TAXON) —
     *  c'était deux copies du même HTTP brut qui pouvaient diverger. */
    internal fun chargerDatasetsDuModuleLive(config: GeoNatureConfig, moduleCode: String): JSONArray? {
        return try {
            val base = config.urlServeur.trim().trimEnd('/')
            val auth = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse) ?: return null
            val (token, _, cookies) = auth
            for (variant in listOf(moduleCode, moduleCode.lowercase()).distinct()) {
                val url = URL("$base/api/meta/datasets?module_code=$variant&active=true&fields=modules")
                val conn = HttpClient.get(url, token, cookies, 10000)
                if (HttpClient.lireCode(conn) != 200) continue
                val txt = conn.inputStream.bufferedReader().readText()
                val arr = txt.parserTableauJson("data") ?: continue
                if (arr.length() > 0) return arr
            }
            null
        } catch (_: Exception) { null }
    }

    /** [preserverString] : quand true, une valeur String numérique reste String (champ
     *  texte-libre). N'affecte que le niveau racine — les items d'array (observers, etc.)
     *  restent coercés car le serveur les veut en Int. */
    private fun normaliserPourJson(v: Any?, preserverString: Boolean = false): Any {
        if (v === JSONObject.NULL) return JSONObject.NULL
        return when (v) {
            null -> JSONObject.NULL
            is Boolean -> v
            is Number -> v
            // JSONArray arrive quand on reparse un payload depuis l'outbox. On reconstruit
            // un nouveau JSONArray en normalisant chaque item — sinon les "121" stockés
            // en String dans observers restent en String alors que le serveur attend des Int.
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until v.length()) out.put(normaliserPourJson(v.opt(i)))
                out
            }
            is JSONObject -> v
            is List<*> -> JSONArray().apply { v.forEach { put(normaliserPourJson(it)) } }
            is String -> {
                val t = v.trim()
                if (preserverString) t else t.toIntOrNull() ?: t
            }
            else -> v.toString()
        }
    }
}
