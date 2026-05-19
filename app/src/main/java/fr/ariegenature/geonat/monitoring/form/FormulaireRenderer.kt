package fr.ariegenature.geonat.monitoring.form

import android.app.DatePickerDialog
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Renderer de formulaire dynamique pour gn_module_monitoring. Inspiré de l'EditableFieldAdapter
 *  d'occtax-mobile, simplifié au style GeoNat-Android (LinearLayout programmatique, pas de
 *  RecyclerView/Hilt). Tient sur ~250 lignes vs ~1050 chez eux car (a) pas de Parcelable,
 *  (b) pas de mediator de coords min/max pour l'instant, (c) widgets POC limités à 5.
 *
 *  Cycle de vie typique :
 *    val r = FormulaireRenderer(ctx, parent)
 *    r.rendre(fields)         // ajoute les vues
 *    val valeurs = r.lireValeurs()  // au moment du submit */
class FormulaireRenderer(
    private val ctx: Context,
    private val parent: ViewGroup,
) {
    private val density = ctx.resources.displayMetrics.density
    /** code → vue éditable racine (l'EditText / Spinner / TextView selon le widget). */
    private val vuesParCode = linkedMapOf<String, View>()
    /** code → champ d'origine (pour relire son viewType au moment de lire les valeurs). */
    private val fieldsParCode = linkedMapOf<String, EditableField>()

    fun rendre(fields: List<EditableField>) {
        parent.removeAllViews()
        vuesParCode.clear()
        fieldsParCode.clear()
        fields.forEach { field ->
            fieldsParCode[field.code] = field
            val (rowView, editable) = creerLigne(field)
            vuesParCode[field.code] = editable
            parent.addView(rowView)
        }
    }

    /** Lit la valeur courante de chaque champ. Renvoie une Map code → valeur typée :
     *  - TEXT / TEXTAREA → String (vide si non rempli)
     *  - NUMBER → Int? (null si non parsable)
     *  - DATE → String "YYYY-MM-DD" ou ""
     *  - SELECT → String (valeur technique de l'option, "" si rien) */
    fun lireValeurs(): Map<String, Any?> = vuesParCode.mapValues { (code, v) ->
        val field = fieldsParCode[code] ?: return@mapValues null
        when (field.viewType) {
            ViewType.TEXT, ViewType.TEXTAREA -> (v as EditText).text.toString()
            ViewType.NUMBER -> (v as EditText).text.toString().toIntOrNull()
            ViewType.DATE -> (v as TextView).tag as? String ?: ""
            ViewType.SELECT -> {
                val sp = v as Spinner
                val idx = sp.selectedItemPosition
                if (idx <= 0) "" else field.values.getOrNull(idx - 1)?.value.orEmpty()
            }
            ViewType.SELECT_MULTIPLE -> {
                @Suppress("UNCHECKED_CAST")
                ((v as TextView).tag as? List<String>).orEmpty()
            }
        }
    }

    private fun creerLigne(field: EditableField): Pair<View, View> {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val labelTv = TextView(ctx).apply {
            text = if (field.obligatoire) "${field.label} *" else field.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        container.addView(labelTv)
        val editable: View = when (field.viewType) {
            ViewType.TEXT -> creerEditText(field, InputType.TYPE_CLASS_TEXT)
            ViewType.TEXTAREA -> creerEditText(
                field,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                minLines = 3,
            )
            ViewType.NUMBER -> creerEditText(
                field,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
            )
            ViewType.DATE -> creerChampDate(field)
            ViewType.SELECT -> creerSpinner(field)
            ViewType.SELECT_MULTIPLE -> creerChampMultiSelect(field)
        }
        container.addView(editable)
        // Texte d'aide (definition du schéma serveur) sous le champ — utile pour expliquer
        // ce qu'on attend, particulièrement sur les protocoles techniques.
        field.aide?.takeIf { it.isNotEmpty() }?.let { aide ->
            val tvAide = TextView(ctx).apply {
                text = aide
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF888888.toInt())
                setPadding(0, (2 * density).toInt(), 0, 0)
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.ITALIC)
            }
            container.addView(tvAide)
        }
        return container to editable
    }

    private fun creerEditText(field: EditableField, type: Int, minLines: Int = 1): EditText =
        EditText(ctx).apply {
            inputType = type
            if (minLines > 1) {
                this.minLines = minLines
                setLines(minLines)
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            field.value?.toString()?.takeIf { it.isNotEmpty() && it != "null" }?.let { setText(it) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

    /** Champ DATE : un TextView cliquable qui ouvre un DatePickerDialog. La date choisie est
     *  stockée dans `tag` au format ISO "YYYY-MM-DD" et affichée au format FR pour l'utilisateur. */
    private fun creerChampDate(field: EditableField): TextView {
        val fmtAffichage = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val fmtIso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val tv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding((8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt())
            setBackgroundResource(android.R.drawable.editbox_background_normal)
            text = "Choisir une date…"
            setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Pré-remplir si fourni dans le field.
        (field.value as? String)?.takeIf { it.length >= 10 }?.let { iso ->
            tv.tag = iso.substring(0, 10)
            runCatching { tv.text = fmtAffichage.format(fmtIso.parse(iso.substring(0, 10))!!) }
            tv.setTextColor(0xFF000000.toInt())
        }
        tv.setOnClickListener {
            val cal = Calendar.getInstance()
            (tv.tag as? String)?.let { iso ->
                runCatching { cal.time = fmtIso.parse(iso)!! }
            }
            DatePickerDialog(
                ctx,
                { _, y, m, d ->
                    cal.set(y, m, d)
                    tv.tag = fmtIso.format(cal.time)
                    tv.text = fmtAffichage.format(cal.time)
                    tv.setTextColor(0xFF000000.toInt())
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
            ).show()
        }
        return tv
    }

    /** Champ multi-sélection : TextView cliquable qui ouvre un dialog cases à cocher.
     *  Stocke la List<String> des `value` sélectionnées dans `tag`. Affiche les `label`s joints
     *  par virgule, ou "Choisir…" si rien sélectionné. Pré-coche les valeurs de [field.value]
     *  si fournies (List<String> ou String unique). */
    private fun creerChampMultiSelect(field: EditableField): TextView {
        @Suppress("UNCHECKED_CAST")
        val defaut: List<String> = (field.value as? List<String>)
            ?: (field.value as? String)?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
            ?: emptyList()
        val tv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding((8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt())
            setBackgroundResource(android.R.drawable.editbox_background_normal)
            tag = defaut
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        if (field.values.isEmpty()) {
            tv.text = "(aucune option disponible)"
            tv.isEnabled = false
            return tv
        }
        // Affichage initial des labels des valeurs pré-sélectionnées.
        val initLabels = field.values.filter { it.value in defaut }.map { it.label }
        if (initLabels.isNotEmpty()) {
            tv.text = initLabels.joinToString(", ")
            tv.setTextColor(0xFF000000.toInt())
        } else {
            tv.text = "Choisir…"
            tv.setTextColor(0xFF888888.toInt())
        }
        tv.setOnClickListener {
            @Suppress("UNCHECKED_CAST")
            val selectionActuelle = (tv.tag as? List<String>).orEmpty().toMutableSet()
            val labels = field.values.map { it.label }.toTypedArray()
            val cochees = BooleanArray(field.values.size) { i -> field.values[i].value in selectionActuelle }
            AlertDialog.Builder(ctx)
                .setTitle(field.label)
                .setMultiChoiceItems(labels, cochees) { _, which, isChecked ->
                    val value = field.values[which].value
                    if (isChecked) selectionActuelle.add(value) else selectionActuelle.remove(value)
                }
                .setPositiveButton("Valider") { _, _ ->
                    val liste = field.values.filter { it.value in selectionActuelle }
                    tv.tag = liste.map { it.value }
                    if (liste.isEmpty()) {
                        tv.text = "Choisir…"
                        tv.setTextColor(0xFF888888.toInt())
                    } else {
                        tv.text = liste.joinToString(", ") { it.label }
                        tv.setTextColor(0xFF000000.toInt())
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
        return tv
    }

    private fun creerSpinner(field: EditableField): Spinner {
        val placeholder = "— choisir —"
        val labels = listOf(placeholder) + field.values.map { it.label }
        val sp = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Sélectionne l'item par défaut si fourni.
        (field.value as? String)?.let { defaultVal ->
            val idx = field.values.indexOfFirst { it.value == defaultVal }
            if (idx >= 0) sp.setSelection(idx + 1)
        }
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {}
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return sp
    }
}