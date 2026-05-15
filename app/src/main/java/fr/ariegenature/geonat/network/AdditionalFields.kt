package fr.ariegenature.geonat.network

import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** Niveaux où un champ additionnel peut s'appliquer (table `objects.code_object` côté GN). */
object AdditionalFieldsObject {
    const val RELEVE = "OCCTAX_RELEVE"
    const val OCCURRENCE = "OCCTAX_OCCURRENCE"
    const val COUNTING = "OCCTAX_DENOMBREMENT"
}

/** Types de widgets supportés (couvrent ~95% des configs Occtax réelles).
 *  Les autres widgets serveur sont rendus en texte libre (best-effort) avec un hint. */
enum class WidgetType { TEXT, TEXTAREA, NUMBER, SELECT, CHECKBOX, INCONNU }

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
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
            val code = conn.responseCode
            if (code == 404) return@withContext emptyList() // endpoint absent — feature optionnelle
            if (code != 200) throw GNErreur.EnvoiEchoue(code, "additional_fields : HTTP $code")

            val text = conn.inputStream.bufferedReader().readText()
            val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                val obj = JSONObject(text)
                obj.optJSONArray("data") ?: obj.optJSONArray("items")
                    ?: throw GNErreur.EnvoiEchoue(code, "additional_fields : format JSON inattendu")
            }
                val result = mutableListOf<AdditionalFieldDef>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val idField = item.optInt("id_field", -1).takeIf { it > 0 } ?: continue
                    val fieldName = item.optString("field_name", "").ifEmpty { continue }
                    val fieldLabel = item.optString("field_label", fieldName)
                    val typeWidgetObj = item.optJSONObject("type_widget")
                    val widgetName = typeWidgetObj?.optString("widget_name", "") ?: ""
                    val widget = when (widgetName.lowercase()) {
                        "text" -> WidgetType.TEXT
                        "textarea" -> WidgetType.TEXTAREA
                        "number" -> WidgetType.NUMBER
                        "select" -> WidgetType.SELECT
                        "checkbox", "bool_checkbox" -> WidgetType.CHECKBOX
                        else -> WidgetType.INCONNU
                    }
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
                        description = item.optString("description", "").takeIf { it.isNotEmpty() },
                        defaultValue = item.optString("default_value", "").takeIf { it.isNotEmpty() },
                        objectsCode = objectsCode,
                        datasetsIds = datasetsIds,
                        idList = idList,
                        widgetServeur = widgetName,
                    ))
                }
            // Tri par field_order si disponible, sinon par label.
            result.sortedBy { it.fieldLabel.lowercase() }
        }
}
