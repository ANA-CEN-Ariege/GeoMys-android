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

package fr.ariegenature.geomys.ui.saisie

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.setMargins
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.network.AdditionalFieldDef
import fr.ariegenature.geomys.network.WidgetType

/** Rend dynamiquement des champs additionnels GeoNature (gn_commons.t_additional_fields)
 *  dans un container vertical. Chaque widget est tagué pour qu'on puisse relire les valeurs
 *  saisies. Réutilisé par CaracterisationFragment (releve/occurrence) et DenombrementFragment. */
object AdditionalFieldsRenderer {

    // Gson partagé : sa construction (réflexion + registres d'adapters) est coûteuse et était
    // refaite à chaque ouverture d'écran de saisie.
    private val gson = Gson()

    /** Désérialise le cache JSON additional_fields. */
    fun fromJson(json: String): List<AdditionalFieldDef> {
        if (json.isEmpty()) return emptyList()
        return try {
            val t = object : TypeToken<List<AdditionalFieldDef>>() {}.type
            gson.fromJson<List<AdditionalFieldDef>>(json, t) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /** Vrai si le serveur déclare au moins un champ additionnel niveau OCCTAX_RELEVE visible
     *  pour le dataset courant. Sert à décider d'intercaler l'écran "Détails du relevé" avant
     *  la saisie (multi-taxons comme mono-taxons). */
    fun aDesChampsReleve(additionalFieldsOcctaxJson: String, idDataset: Int?): Boolean =
        fromJson(additionalFieldsOcctaxJson)
            .filter { it.appliqueA(fr.ariegenature.geomys.network.AdditionalFieldsObject.RELEVE) }
            .any { it.visiblePour(idDataset, emptyList()) }

    /** Vrai s'il existe au moins un champ additionnel relevé visible **obligatoire ET SANS valeur
     *  par défaut** — seul cas qui justifie d'intercaler l'écran « Détails du relevé » avant la
     *  saisie. Un required AVEC défaut est déjà satisfait, et tous ces champs restent de toute
     *  façon éditables via le bouton « Détails » de l'écran de saisie. */
    fun aDesChampsReleveRequisSansDefaut(additionalFieldsOcctaxJson: String, idDataset: Int?): Boolean =
        fromJson(additionalFieldsOcctaxJson)
            .filter { it.appliqueA(fr.ariegenature.geomys.network.AdditionalFieldsObject.RELEVE) }
            .filter { it.visiblePour(idDataset, emptyList()) }
            .any { it.required && it.defaultValue.isNullOrBlank() }

    /** Valeurs par défaut des champs additionnels relevé visibles (`fieldName` → defaultValue non
     *  vide). Sert à pré-remplir la session quand on SAUTE l'écran « Détails du relevé » : un champ
     *  avec défaut (notamment required) porte ainsi sa valeur jusqu'à l'envoi sans qu'on ait à
     *  ouvrir « Détails ». */
    fun defautsChampsReleve(additionalFieldsOcctaxJson: String, idDataset: Int?): Map<String, String> =
        fromJson(additionalFieldsOcctaxJson)
            .filter { it.appliqueA(fr.ariegenature.geomys.network.AdditionalFieldsObject.RELEVE) }
            .filter { it.visiblePour(idDataset, emptyList()) }
            // Pas de pré-remplissage des CHECKBOX depuis un default_value scalaire : le web ne le fait
            // pas pour les champs additionnels (sinon une case se retrouverait cochée/envoyée à tort).
            .filter { it.widget != WidgetType.CHECKBOX }
            .mapNotNull { def -> def.defaultValue?.takeIf { it.isNotBlank() }?.let { def.fieldName to it } }
            .toMap()

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
            // Sans valeur saisie ni défaut serveur, les champs date/heure prennent par défaut
            // la date du jour / l'heure actuelle (le serveur reste prioritaire via defaultValue).
            val brut = valeurs?.get(def.fieldName)
                ?: def.defaultValue
                ?: defautDateHeurePour(def.widgetServeur)
                ?: ""
            val valeur = if (brut == "null") "" else brut
            container.addView(buildWidget(ctx, def, valeur))
        }
    }

    /** Défaut date/heure pour les widgets serveur `date`/`time`/`datetime` des champs
     *  additionnels (rendus en champ texte) : date du jour, heure actuelle, ou les deux.
     *  Renvoie null pour les autres widgets (pas de pré-remplissage). */
    private fun defautDateHeurePour(widgetServeur: String): String? = when (widgetServeur.lowercase()) {
        "date" -> fr.ariegenature.geomys.util.DateHeureDefaut.dateDuJour()
        "time" -> fr.ariegenature.geomys.util.DateHeureDefaut.heureActuelle()
        "datetime", "date-time", "timestamp" -> fr.ariegenature.geomys.util.DateHeureDefaut.dateHeureActuelle()
        else -> null
    }

    /** Lit les valeurs courantes dans les widgets du container, indexées par field_name. */
    fun collecter(container: LinearLayout): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (i in 0 until container.childCount) {
            val view = container.getChildAt(i)
            val tag = view.tag as? FieldTag ?: continue
            result[tag.fieldName] = valeurChamp(view, tag.widget)
        }
        return result
    }

    /** Libellés des champs additionnels OBLIGATOIRES (required) visibles laissés vides. Sert à
     *  bloquer la validation côté Occtax (parité avec la garde du moteur monitoring). Les CHECKBOX
     *  sont exclues (toujours true/false, jamais « vides »). */
    fun champsObligatoiresVides(container: LinearLayout): List<String> {
        val manquants = mutableListOf<String>()
        for (i in 0 until container.childCount) {
            val view = container.getChildAt(i)
            val tag = view.tag as? FieldTag ?: continue
            if (!tag.required || tag.widget == WidgetType.CHECKBOX) continue
            if (valeurChamp(view, tag.widget).isBlank()) manquants += tag.label
        }
        return manquants
    }

    private fun valeurChamp(view: android.view.View, widget: WidgetType): String = when (widget) {
        WidgetType.TEXT, WidgetType.TEXTAREA, WidgetType.NUMBER, WidgetType.INCONNU ->
            (view.findViewWithTag<EditText>("input"))?.text?.toString() ?: ""
        WidgetType.SELECT, WidgetType.NOMENCLATURE -> {
            val spinner = view.findViewWithTag<Spinner>("input")
            val codes = spinner?.getTag(R.id.tag_field_codes) as? List<*>
            codes?.getOrNull(spinner.selectedItemPosition) as? String ?: ""
        }
        WidgetType.RADIO -> {
            val rbs = mutableListOf<RadioButton>()
            fun scan(v: android.view.View) {
                if (v is RadioButton) rbs.add(v)
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) scan(v.getChildAt(i))
            }
            scan(view)
            rbs.firstOrNull { it.isChecked }?.getTag(R.id.tag_field_codes)?.toString() ?: ""
        }
        WidgetType.CHECKBOX -> {
            // Multi-valeurs : plusieurs cases taguées avec leur code → tableau JSON des cochées.
            // Sinon (case unique tag "input") → booléen "true"/"false" (compat existante).
            val cases = mutableListOf<CheckBox>()
            fun scan(v: android.view.View) {
                if (v is CheckBox && v.getTag(R.id.tag_field_codes) != null) cases.add(v)
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) scan(v.getChildAt(i))
            }
            scan(view)
            if (cases.isNotEmpty()) {
                org.json.JSONArray(cases.filter { it.isChecked }.map { it.getTag(R.id.tag_field_codes).toString() }).toString()
            } else {
                if (view.findViewWithTag<CheckBox>("input")?.isChecked == true) "true" else "false"
            }
        }
    }

    /** Codes pré-cochés d'un checkbox multi : UNIQUEMENT depuis une vraie valeur enregistrée
     *  (tableau JSON `["1","3"]`). Une valeur scalaire = le `default_value` du serveur, que le web
     *  N'APPLIQUE PAS comme pré-cochage pour un champ additionnel → on ne coche rien (parité web). */
    private fun parseCodesCoches(current: String): Set<String> {
        if (current.isBlank()) return emptySet()
        return try {
            val arr = org.json.JSONArray(current)
            (0 until arr.length()).map { arr.get(it).toString() }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private data class FieldTag(val fieldName: String, val widget: WidgetType, val required: Boolean, val label: String)

    private fun buildWidget(ctx: Context, def: AdditionalFieldDef, current: String): LinearLayout {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(ctx, 8) }
            tag = FieldTag(def.fieldName, def.widget, def.required, def.fieldLabel)
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
                // Codes = vraies valeurs serveur (field_values[i].value) si disponibles, sinon repli
                // sur les libellés (anciens caches / field_values en chaînes nues).
                val valeurs = if (def.fieldValueCodes.size == def.fieldValues.size) def.fieldValueCodes else def.fieldValues
                val codes = listOf("") + valeurs
                val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
                // setTag(id, value) ne remplace PAS le tag principal ("input") utilisé pour
                // la recherche par findViewWithTag dans collecter — bug historique fixé ici.
                spinner.setTag(R.id.tag_field_codes, codes)
                val idx = codes.indexOf(current).coerceAtLeast(0)
                spinner.setSelection(idx)
                wrapper.addView(spinner)
            }
            WidgetType.RADIO -> {
                // Boutons radio (comme le web), choix unique MAIS dé-sélectionnable : re-cliquer la
                // valeur active la décoche (sémantique « coché / pas coché », cf. toggleValueRadio du
                // web). Pas d'option « (non renseigné) » : un radio seul = une case qu'on coche ou pas.
                val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; tag = "input" }
                val valeurs = if (def.fieldValueCodes.size == def.fieldValues.size) def.fieldValueCodes else def.fieldValues
                val boutons = mutableListOf<RadioButton>()
                var selection: String? = current.takeIf { it.isNotEmpty() }
                def.fieldValues.forEachIndexed { i, label ->
                    val code = valeurs.getOrElse(i) { label }
                    val rb = RadioButton(ctx).apply {
                        text = label
                        setTag(R.id.tag_field_codes, code)
                        isChecked = code == selection
                        setOnClickListener {
                            selection = if (selection == code) null else code
                            boutons.forEach { b -> b.isChecked = b.getTag(R.id.tag_field_codes) == selection }
                        }
                    }
                    boutons.add(rb)
                    container.addView(rb)
                }
                wrapper.addView(container)
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
                spinner.setTag(R.id.tag_field_codes, codes)
                spinner.setSelection(codes.indexOf(current).coerceAtLeast(0))
                wrapper.addView(spinner)
            }
            WidgetType.CHECKBOX -> {
                if (def.fieldValues.size > 1) {
                    // Checkbox MULTI-valeurs (comme le web) : un groupe de cases, une par valeur.
                    // Résultat = tableau JSON des CODES cochés. Pré-cochage depuis la valeur courante
                    // (tableau JSON) ou la valeur par défaut (code simple, ex. "2").
                    val coches = parseCodesCoches(current)
                    def.fieldValues.forEachIndexed { i, label ->
                        val code = def.fieldValueCodes.getOrElse(i) { label }
                        wrapper.addView(CheckBox(ctx).apply {
                            text = label
                            isChecked = code in coches
                            setTag(R.id.tag_field_codes, code)
                        })
                    }
                } else {
                    wrapper.addView(CheckBox(ctx).apply {
                        isChecked = current.equals("true", ignoreCase = true)
                        tag = "input"
                    })
                }
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
