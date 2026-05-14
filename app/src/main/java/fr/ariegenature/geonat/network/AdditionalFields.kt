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
    /** Nom du widget tel que renvoyé par le serveur (utile pour debug si type non supporté). */
    val widgetServeur: String = "",
) {
    fun appliqueA(codeObjet: String): Boolean = objectsCode.contains(codeObjet)
}

object AdditionalFieldsApi {

    /** GET /api/gn_commons/additional_fields?module_code=OCCTAX
     *  Retourne la liste de tous les champs additionnels Occtax (tous niveaux confondus). */
    suspend fun charger(config: GeoNatureConfig, moduleCode: String = "OCCTAX"): List<AdditionalFieldDef> =
        withContext(Dispatchers.IO) {
            try {
                val base = config.urlServeur.trim().trimEnd('/')
                val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                    ?: return@withContext emptyList()

                val url = URL("$base/api/gn_commons/additional_fields?module_code=$moduleCode")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                if (conn.responseCode != 200) return@withContext emptyList()

                val text = conn.inputStream.bufferedReader().readText()
                val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                    val obj = JSONObject(text)
                    obj.optJSONArray("data") ?: obj.optJSONArray("items") ?: return@withContext emptyList()
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
                        widgetServeur = widgetName,
                    ))
                }
                // Tri par field_order si disponible, sinon par label.
                result.sortedBy { it.fieldLabel.lowercase() }
            } catch (_: Exception) { emptyList() }
        }
}
