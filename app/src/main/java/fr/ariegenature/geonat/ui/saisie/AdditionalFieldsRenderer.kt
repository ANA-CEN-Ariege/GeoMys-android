package fr.ariegenature.geonat.ui.saisie

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.setMargins
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geonat.network.AdditionalFieldDef
import fr.ariegenature.geonat.network.WidgetType

/** Rend dynamiquement des champs additionnels GeoNature (gn_commons.t_additional_fields)
 *  dans un container vertical. Chaque widget est tagué pour qu'on puisse relire les valeurs
 *  saisies. Réutilisé par CaracterisationFragment (releve/occurrence) et DenombrementFragment. */
object AdditionalFieldsRenderer {

    /** Désérialise le cache JSON additional_fields. */
    fun fromJson(json: String): List<AdditionalFieldDef> {
        if (json.isEmpty()) return emptyList()
        return try {
            val t = object : TypeToken<List<AdditionalFieldDef>>() {}.type
            Gson().fromJson<List<AdditionalFieldDef>>(json, t) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /** Vide le container et rend tous les champs définis dans `defs`.
     *  Les valeurs courantes sont lues depuis `valeurs` (Map<field_name, valeur stringifiée>).
     *  Tolère un `valeurs == null` (cas Gson : champ absent du JSON désérialisé). */
    fun rendre(
        container: LinearLayout,
        defs: List<AdditionalFieldDef>,
        valeurs: Map<String, String>?,
    ) {
        container.removeAllViews()
        if (defs.isEmpty()) return
        val ctx = container.context
        defs.forEach { def ->
            // Filet : si la valeur saisie ou le défaut serveur est la chaîne "null", on traite
            // comme absent (sinon le champ s'affiche pré-rempli avec "null", déroutant).
            val brut = valeurs?.get(def.fieldName) ?: def.defaultValue ?: ""
            val valeur = if (brut == "null") "" else brut
            container.addView(buildWidget(ctx, def, valeur))
        }
    }

    /** Lit les valeurs courantes dans les widgets du container, indexées par field_name. */
    fun collecter(container: LinearLayout): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (i in 0 until container.childCount) {
            val view = container.getChildAt(i)
            val tag = view.tag as? FieldTag ?: continue
            val value: String = when (tag.widget) {
                WidgetType.TEXT, WidgetType.TEXTAREA, WidgetType.NUMBER, WidgetType.INCONNU ->
                    (view.findViewWithTag<EditText>("input"))?.text?.toString() ?: ""
                WidgetType.SELECT, WidgetType.NOMENCLATURE -> {
                    val spinner = view.findViewWithTag<Spinner>("input") ?: continue
                    val codes = spinner.tag as? List<*>
                    codes?.getOrNull(spinner.selectedItemPosition) as? String ?: ""
                }
                WidgetType.CHECKBOX ->
                    if (view.findViewWithTag<CheckBox>("input")?.isChecked == true) "true" else "false"
            }
            result[tag.fieldName] = value
        }
        return result
    }

    private data class FieldTag(val fieldName: String, val widget: WidgetType)

    private fun buildWidget(ctx: Context, def: AdditionalFieldDef, current: String): LinearLayout {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(ctx, 8) }
            tag = FieldTag(def.fieldName, def.widget)
        }
        // Label
        wrapper.addView(TextView(ctx).apply {
            text = def.fieldLabel + if (def.required) " *" else ""
            textSize = 13f
            setTextColor(ctx.getColor(android.R.color.darker_gray))
        })

        // Widget
        when (def.widget) {
            WidgetType.SELECT -> {
                val spinner = Spinner(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    tag = "input"
                }
                val labels = listOf("(non renseigné)") + def.fieldValues
                val codes = listOf("") + def.fieldValues
                val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
                spinner.tag = codes
                val idx = codes.indexOf(current).coerceAtLeast(0)
                spinner.setSelection(idx)
                wrapper.addView(spinner)
            }
            WidgetType.NOMENCLATURE -> {
                val spinner = Spinner(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    tag = "input"
                }
                val labels = listOf("(non renseigné)") + def.nomenclatureOptions.map { it.second }
                val codes = listOf("") + def.nomenclatureOptions.map { it.first }
                val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
                spinner.tag = codes
                spinner.setSelection(codes.indexOf(current).coerceAtLeast(0))
                wrapper.addView(spinner)
            }
            WidgetType.CHECKBOX -> {
                wrapper.addView(CheckBox(ctx).apply {
                    isChecked = current.equals("true", ignoreCase = true)
                    tag = "input"
                })
            }
            else -> {
                // TEXT / TEXTAREA / NUMBER / INCONNU → champ texte avec hints adaptés.
                val til = TextInputLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    if (def.widget == WidgetType.INCONNU && def.widgetServeur.isNotEmpty())
                        hint = "Type ${def.widgetServeur} (saisi en texte)"
                }
                val et = TextInputEditText(ctx).apply {
                    // TextInputLayout hérite de LinearLayout → ses enfants DOIVENT avoir
                    // des LinearLayout.LayoutParams, sinon ClassCastException au layout pass.
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    tag = "input"
                    inputType = when (def.widget) {
                        WidgetType.NUMBER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        WidgetType.TEXTAREA -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                    if (def.widget == WidgetType.TEXTAREA) minLines = 2
                    setText(current)
                }
                til.addView(et)
                wrapper.addView(til)
            }
        }

        // Description en sous-titre si présente.
        def.description?.takeIf { it.isNotEmpty() }?.let {
            wrapper.addView(TextView(ctx).apply {
                text = it
                textSize = 11f
                setTextColor(ctx.getColor(android.R.color.darker_gray))
            })
        }
        return wrapper
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
