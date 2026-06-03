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
import fr.ariegenature.geomys.store.NomenclatureCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** Niveaux où un champ additionnel peut s'appliquer (table `objects.code_object` côté GN).
 *  ⚠ Le code OCCTAX_OCCURENCE est typo côté serveur (un seul R) — c'est la valeur réellement
 *  stockée dans gn_commons.bib_tables_location, confirmée par gn_mobile_occtax officiel. */
object AdditionalFieldsObject {
    const val RELEVE = "OCCTAX_RELEVE"
    const val OCCURRENCE = "OCCTAX_OCCURENCE"
    const val COUNTING = "OCCTAX_DENOMBREMENT"
}

/** Types de widgets supportés (couvrent ~95% des configs Occtax réelles).
 *  Les autres widgets serveur sont rendus en texte libre (best-effort) avec un hint. */
enum class WidgetType { TEXT, TEXTAREA, NUMBER, SELECT, CHECKBOX, NOMENCLATURE, INCONNU }

/** Définition d'un champ additionnel récupérée du serveur GeoNature.
 *  Sérialisable (Gson) pour cache en SharedPreferences. */
data class AdditionalFieldDef(
    val idField: Int,
    val fieldName: String,
    val fieldLabel: String,
    val widget: WidgetType,
    /** Valeurs proposées pour SELECT — tableau JSON string. */
    val fieldValues: List<String> = emptyList(),
    val required: Boolean = false,
    val description: String? = null,
    val defaultValue: String? = null,
    /** Codes des objets concernés (OCCTAX_RELEVE / OCCTAX_OCCURRENCE / OCCTAX_DENOMBREMENT). */
    val objectsCode: List<String> = emptyList(),
    /** Liste des id_dataset où ce champ s'applique. Vide = tous les datasets. */
    val datasetsIds: List<Int> = emptyList(),
    /** id_list UsersHub auquel ce champ se restreint (null = aucune restriction). */
    val idList: Int? = null,
    /** Nom du widget tel que renvoyé par le serveur (utile pour debug si type non supporté). */
    val widgetServeur: String = "",
    /** Pour widget=`nomenclature` : code mnémonique du type de nomenclature serveur
     *  (ex. `STATUT_OBS`, `METH_DETERMIN`). Sert à fetcher /api/nomenclatures/nomenclature/<code>. */
    val codeNomenclatureType: String? = null,
    /** Options résolues pour widget=`nomenclature` : (id_nomenclature, label_fr). */
    val nomenclatureOptions: List<Pair<String, String>> = emptyList(),
) {
    fun appliqueA(codeObjet: String): Boolean = objectsCode.contains(codeObjet)

    /** Vrai si ce champ doit s'afficher pour le dataset courant et le taxon observé.
     *  - `idDataset` : id du dataset courant (gnConfig.idDataset)
     *  - `listesDuTaxon` : id_liste UsersHub auxquelles appartient le cd_nom observé
     *    (récupéré via TaxRefCache.listesPourCdNom(cdNom))
     *
     *  Règles :
     *  - datasets vide → applies à tous les datasets
     *  - idList null  → applies à tous les taxons (pas de restriction par liste)
     *  - sinon : il faut que le taxon appartienne à cette liste */
    fun visiblePour(idDataset: Int?, listesDuTaxon: Collection<Int>): Boolean {
        if (datasetsIds.isNotEmpty() && idDataset != null && idDataset !in datasetsIds) return false
        if (datasetsIds.isNotEmpty() && idDataset == null) return false
        if (idList != null && idList !in listesDuTaxon) return false
        return true
    }
}

object AdditionalFieldsApi {

    /** GET /api/gn_commons/additional_fields?module_code=OCCTAX
     *  Retourne la liste de tous les champs additionnels Occtax (tous niveaux confondus).
     *  Renvoie [] silencieusement si HTTP 404 (endpoint absent — feature optionnelle).
     *  Sur toute autre erreur (timeout, 5xx, parse), propage l'exception au caller. */
    suspend fun charger(config: GeoNatureConfig, moduleCode: String = "OCCTAX"): List<AdditionalFieldDef> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: throw GNErreur.AuthEchouee(401)

            val url = URL("$base/api/gn_commons/additional_fields?module_code=$moduleCode")
            val conn = HttpClient.get(url, token, cookies, 10000)
            val code = conn.responseCode
            if (code == 404) return@withContext emptyList() // endpoint absent — feature optionnelle
            if (code != 200) throw GNErreur.EnvoiEchoue(code, "additional_fields : HTTP $code")

            val text = conn.inputStream.bufferedReader().readText()
            val array: JSONArray = text.parserTableauJson("data", "items")
                ?: throw GNErreur.EnvoiEchoue(code, "additional_fields : format JSON inattendu")
            val result = parserChamps(array)
            // Pour les widgets `nomenclature` : fetch parallèle des listes serveur
            // (/api/nomenclatures/nomenclature/<CODE>) pour résoudre id_nomenclature → label_fr.
            val codesNom = result.mapNotNull { it.codeNomenclatureType }.toSet()
            val nomenclaturesResolues: Map<String, List<Pair<String, String>>> =
                if (codesNom.isEmpty()) emptyMap() else coroutineScope {
                    codesNom.map { code ->
                        async { code to fetchValeursNomenclature(base, token, cookies, code) }
                    }.awaitAll().toMap()
                }
            // Defaults par mnémonique : lus depuis NomenclatureCache, peuplé par
            // GeoNatureSync.synchroniserNomenclatures (single source of truth — appelé une
            // fois par sync). Couvre TOUS les champs basés sur des nomenclatures, qu'ils
            // soient additional ou natifs (Caractérisation, Dénombrement).
            val defautsParMnemonique: Map<String, String> = NomenclatureCache.tousLesDefauts()
            val resultEnrichi = result.map { def ->
                def.codeNomenclatureType?.let { code ->
                    def.copy(
                        nomenclatureOptions = nomenclaturesResolues[code].orEmpty(),
                        // default_value côté additional_fields est presque toujours null
                        // côté serveur — on retombe sur defaultNomenclatures du module.
                        defaultValue = def.defaultValue ?: defautsParMnemonique[code],
                    )
                } ?: def
            }
            // Tri par field_order si disponible, sinon par label.
            resultEnrichi.sortedBy { it.fieldLabel.lowercase() }
        }

    /** Cherche le default sous plusieurs clés serveur connues (le format varie selon les
     *  versions GeoNature et la migration gn_commons). Essaie également dans
     *  `additional_attributes` qui est l'enveloppe utilisée par certains widgets. Enfin,
     *  pour les widgets SELECT/RADIO/DATALIST, prend le premier `field_values[i].default==true`. */
    /** Parse le tableau JSON brut renvoyé par /api/gn_commons/additional_fields en
     *  liste de [AdditionalFieldDef]. Gère les formes variables du serveur GeoNature :
     *  type_widget objet vs string, field_values string vs {value,label}, objects[].code_object,
     *  datasets en ids nus ou objets {id_dataset}. Pur (pas de réseau) → testable. */
    internal fun parserChamps(array: JSONArray): List<AdditionalFieldDef> {
        val result = mutableListOf<AdditionalFieldDef>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val idField = item.optInt("id_field", -1).takeIf { it > 0 } ?: continue
            val fieldName = item.optString("field_name", "").ifEmpty { continue }
            val fieldLabel = item.optString("field_label", fieldName)
            // type_widget peut être renvoyé sous plusieurs formes selon la version GN :
            //   - JSONObject {widget_name: "select", ...}
            //   - String "select"
            //   - Absent (rare, on a alors WidgetType.INCONNU)
            val typeWidgetBrut = item.opt("type_widget")
            val widgetName = when (typeWidgetBrut) {
                is JSONObject -> typeWidgetBrut.optString("widget_name", "")
                    .ifEmpty { typeWidgetBrut.optString("name", "") }
                    .ifEmpty { typeWidgetBrut.optString("code_widget", "") }
                is String -> typeWidgetBrut
                else -> ""
            }
            val widget = when (widgetName.lowercase()) {
                "text" -> WidgetType.TEXT
                "textarea" -> WidgetType.TEXTAREA
                "number", "integer", "float" -> WidgetType.NUMBER
                "select", "radio", "datalist" -> WidgetType.SELECT
                "checkbox", "bool_checkbox", "bool" -> WidgetType.CHECKBOX
                "nomenclature" -> WidgetType.NOMENCLATURE
                else -> WidgetType.INCONNU
            }
            // Pour widget=nomenclature : essaie d'extraire le code_nomenclature_type
            // depuis plusieurs emplacements possibles (varie selon les versions GN).
            val codeNomType: String? = if (widget == WidgetType.NOMENCLATURE) {
                item.optString("code_nomenclature_type", "")
                    .ifEmpty { item.optJSONObject("additional_attributes")?.optString("code_nomenclature_type", "") ?: "" }
                    .ifEmpty {
                        (typeWidgetBrut as? JSONObject)?.optString("code_nomenclature_type", "") ?: ""
                    }
                    .takeIf { it.isNotEmpty() }
            } else null
            val fieldValues = mutableListOf<String>()
            item.optJSONArray("field_values")?.let { arr ->
                for (j in 0 until arr.length()) {
                    // Soit string nu, soit objet {value, label}.
                    val v = arr.opt(j)
                    when (v) {
                        is String -> fieldValues.add(v)
                        is JSONObject -> fieldValues.add(v.optString("label", v.optString("value", "")))
                        else -> v?.toString()?.takeIf { it.isNotEmpty() }?.let { fieldValues.add(it) }
                    }
                }
            }
            val objectsCode = mutableListOf<String>()
            item.optJSONArray("objects")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.optString("code_object", "")
                        ?.takeIf { it.isNotEmpty() }?.let(objectsCode::add)
                }
            }
            // Restrictions par dataset : tableau d'objets {id_dataset, ...} ou d'ids nus.
            val datasetsIds = mutableListOf<Int>()
            item.optJSONArray("datasets")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val v = arr.opt(j)
                    when (v) {
                        is Int -> datasetsIds.add(v)
                        is Long -> datasetsIds.add(v.toInt())
                        is JSONObject -> v.optInt("id_dataset", -1).takeIf { it > 0 }?.let(datasetsIds::add)
                    }
                }
            }
            // Restriction par liste UsersHub de taxons.
            val idList = item.optInt("id_list", -1).takeIf { it > 0 }
            result.add(AdditionalFieldDef(
                idField = idField,
                fieldName = fieldName,
                fieldLabel = fieldLabel,
                widget = widget,
                fieldValues = fieldValues,
                required = item.optBoolean("required", false),
                description = item.optString("description", "")
                    .takeIf { it.isNotEmpty() && it != "null" },
                // Le serveur GeoNature renvoie `default_value` sous plusieurs formes
                // selon le widget : String pour TEXT/TEXTAREA, Number pour NUMBER,
                // String/Number pour NOMENCLATURE (id_nomenclature), JSONArray pour
                // SELECT_MULTIPLE ou un objet {value,label} pour certains widgets.
                // `optString` retourne "null" littéral si la clé porte une valeur
                // null → on filtre. Pour les types complexes on essaie d'extraire
                // la "value" la plus probable.
                defaultValue = extraireDefautToutesClés(item, fieldName, widget.name),
                objectsCode = objectsCode,
                datasetsIds = datasetsIds,
                idList = idList,
                widgetServeur = widgetName,
                codeNomenclatureType = codeNomType,
            ))
        }
        return result
    }

    internal fun extraireDefautToutesClés(item: JSONObject, fieldName: String, widgetName: String): String? {
        // 1) Clés directes à la racine de l'item (toutes les variantes vues sur le terrain).
        val clésDirectes = listOf(
            "default_value", "value_default", "defaultValue", "value", "default"
        )
        for (k in clésDirectes) {
            if (!item.has(k)) continue
            extraireDefaut(item.opt(k))?.let { return it }
        }
        // 2) Dans additional_attributes (enveloppe pour certains widgets).
        item.optJSONObject("additional_attributes")?.let { attrs ->
            for (k in clésDirectes) {
                if (!attrs.has(k)) continue
                extraireDefaut(attrs.opt(k))?.let { return it }
            }
        }
        // 3) Pour SELECT/RADIO/DATALIST : un item de field_values avec default==true.
        item.optJSONArray("field_values")?.let { arr ->
            for (j in 0 until arr.length()) {
                val obj = arr.optJSONObject(j) ?: continue
                if (obj.optBoolean("default", false) || obj.optBoolean("selected", false)) {
                    return obj.opt("value")?.toString() ?: obj.opt("label")?.toString()
                }
            }
        }
        // 4) Log diag : si rien n'a matché mais qu'une clé qui contient "default" ou
        // "value" existe dans le payload, on trace pour comprendre le format réel.
        val clésInteressantes = item.keys().asSequence().filter {
            it.lowercase().contains("default") || it == "value"
        }.toList()
        if (clésInteressantes.isNotEmpty()) {
            android.util.Log.w(
                "AdditionalFields",
                "default introuvable pour '$fieldName' ($widgetName) — clés du payload contenant 'default'/'value' : " +
                clésInteressantes.joinToString { "$it=${item.opt(it)}" }
            )
        }
        return null
    }

    /** Normalise une valeur `default_value` arbitraire (String, Number, JSONArray, JSONObject)
     *  en une String exploitable par les widgets. Conventions :
     *  - String / Number → String simple (filtre `"null"` et chaîne vide).
     *  - JSONArray → premier élément (cas SELECT/MULTIPLE — la majorité des widgets gardent
     *    un seul item par défaut).
     *  - JSONObject → champ `value` si présent, sinon `id_nomenclature`, sinon vide.
     *  - null / JSONObject.NULL → vide. */
    private fun extraireDefaut(brut: Any?): String? = when (brut) {
        null, JSONObject.NULL -> null
        is String -> brut.takeIf { it.isNotEmpty() && it != "null" }
        is Number -> brut.toString()
        is Boolean -> brut.toString()
        is JSONArray -> if (brut.length() > 0) extraireDefaut(brut.opt(0)) else null
        is JSONObject -> {
            val v = brut.opt("value")?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
                ?: brut.opt("id_nomenclature")?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
            v
        }
        else -> brut.toString().takeIf { it.isNotEmpty() && it != "null" }
    }

    /** Fetch des valeurs d'une nomenclature (`/api/nomenclatures/nomenclature/<code>`).
     *  Retourne la liste (id_nomenclature, label_fr) ou vide en cas d'erreur. */
    private fun fetchValeursNomenclature(
        base: String,
        token: String?,
        cookies: String,
        code: String,
    ): List<Pair<String, String>> {
        return try {
            val url = URL("$base/api/nomenclatures/nomenclature/$code")
            val conn = HttpClient.get(url, token, cookies, 10000)
            if (conn.responseCode != 200) return emptyList()
            val text = conn.inputStream.bufferedReader().readText()
            val arr = text.parserTableauJson("values", "data") ?: return emptyList()
            val out = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.opt("id_nomenclature")?.toString() ?: continue
                val lbl = item.optString("label_fr", "")
                    .ifEmpty { item.optString("label_default", "") }
                    .takeIf { it.isNotEmpty() } ?: continue
                out.add(id to lbl)
            }
            out.sortedBy { it.second.lowercase() }
        } catch (_: Exception) { emptyList() }
    }

}
