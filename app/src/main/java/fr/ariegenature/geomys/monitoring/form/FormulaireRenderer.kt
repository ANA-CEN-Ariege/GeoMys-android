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

package fr.ariegenature.geomys.monitoring.form

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Renderer de formulaire dynamique pour gn_module_monitoring. Inspiré de l'EditableFieldAdapter
 *  d'occtax-mobile, simplifié au style GeoMys-Android (LinearLayout programmatique, pas de
 *  RecyclerView/Hilt). Tient sur ~250 lignes vs ~1050 chez eux car (a) pas de Parcelable,
 *  (b) pas de mediator de coords min/max pour l'instant, (c) widgets POC limités à 5.
 *
 *  Cycle de vie typique :
 *    val r = FormulaireRenderer(ctx, parent, viewLifecycleOwner.lifecycleScope)
 *    r.rendre(fields)         // ajoute les vues
 *    val valeurs = r.lireValeurs()  // au moment du submit */
class FormulaireRenderer(
    private val ctx: Context,
    private val parent: ViewGroup,
    /** Scope lifecycle-aware (typiquement `viewLifecycleOwner.lifecycleScope` du Fragment).
     *  Sert au peuplement asynchrone de l'autocomplete TaxRef : sans rattachement au cycle
     *  de vie de la View, un détachement (rotation, back) laisserait tourner un Thread brut
     *  qui survit au formulaire et tente d'attacher un adapter à une vue détruite. */
    private val scope: CoroutineScope,
) {
    private val density = ctx.resources.displayMetrics.density
    /** Couleur de texte d'une valeur renseignée, résolue depuis le thème (colorOnSurface) —
     *  remplace un noir codé en dur invisible en mode sombre. */
    private val couleurValeur: Int = com.google.android.material.color.MaterialColors.getColor(
        parent, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt(),
    )
    /** Couleur d'erreur du thème (colorError) — utilisée pour les messages de validation
     *  sous les champs hors bornes. Reste lisible en mode sombre. */
    private val couleurErreur: Int = com.google.android.material.color.MaterialColors.getColor(
        parent, com.google.android.material.R.attr.colorError, 0xFFB00020.toInt(),
    )
    /** Couleur de texte secondaire (label, placeholder "Choisir…", aide…). Avant on avait
     *  des gris en dur (#888888, #666666) qui se brûlaient en mode sombre / sur le fond
     *  accueil. Le ?attr/colorOnSurfaceVariant Material reste lisible sur les deux. */
    private val couleurSecondaire: Int = com.google.android.material.color.MaterialColors.getColor(
        parent, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt(),
    )
    /** code → vue éditable racine (l'EditText / Spinner / TextView selon le widget). */
    private val vuesParCode = linkedMapOf<String, View>()
    /** code → champ d'origine (pour relire son viewType au moment de lire les valeurs). */
    private val fieldsParCode = linkedMapOf<String, EditableField>()
    /** code → conteneur LinearLayout englobant le champ — pour piloter sa visibilité
     *  via les expressions `hidden` du schéma serveur. */
    private val wrappersParCode = linkedMapOf<String, View>()
    /** code → TextView qui affiche le message d'erreur de validation sous le champ (créé
     *  vide pour chaque NUMBER ayant min/max, GONE tant qu'il n'y a pas de violation). */
    private val erreursParCode = linkedMapOf<String, TextView>()
    /** Callback notifié à chaque modification d'un champ (saisie, sélection, picker…).
     *  Utilisé par l'écran appelant pour piloter l'état du bouton de submit selon que
     *  les champs obligatoires sont remplis ou non. */
    private var onChangement: (() -> Unit)? = null
    /** Callback invoqué quand l'utilisateur appuie sur le bouton d'un champ MEDIA. Reçoit
     *  le code du champ + une lambda à invoquer avec l'URI String du fichier choisi (déjà
     *  importé dans le stockage interne) ou null en cas d'annulation. Externe car le picker
     *  Android (ActivityResultLauncher.GetContent) doit être enregistré dans le Fragment. */
    private var onChoixMedia: ((codeChamp: String, callback: (urisLocales: List<String>) -> Unit) -> Unit)? = null

    /** Enregistre le callback de choix média (cf. [onChoixMedia]). Le callback fourni au Fragment
     *  est rappelé avec la LISTE des URIs locales importées (multi-sélection ; liste vide si
     *  annulation). */
    fun setOnChoixMedia(callback: (String, (List<String>) -> Unit) -> Unit) {
        onChoixMedia = callback
    }
    /** Règles `change` du schéma (auto-remplissage de champs dépendants). Vide par défaut. */
    private var reglesChange: List<ChangeRules.Regle> = emptyList()
    /** Garde anti-récursion : true pendant qu'on applique un patch des règles change
     *  (sinon les setText/setSelection re-déclenchent les listeners → boucle infinie). */
    private var appliquantChange = false

    /** Approximation du `dirty` Angular (champ modifié par l'UTILISATEUR) sans instrumenter
     *  chaque widget : un champ est dirty si sa valeur courante diffère de la dernière valeur
     *  posée programmatiquement (règles change) ou, à défaut, de sa valeur au rendu. Sert aux
     *  gardes `!objForm.controls.X.dirty` des scripts change (ex. nom de site auto-généré
     *  tant que l'utilisateur ne l'a pas édité à la main). */
    private var valeursAuRendu: Map<String, String> = emptyMap()
    private val dernieresValeursAuto = mutableMapOf<String, String>()

    fun rendre(fields: List<EditableField>) {
        parent.removeAllViews()
        vuesParCode.clear()
        fieldsParCode.clear()
        wrappersParCode.clear()
        erreursParCode.clear()
        dernieresValeursAuto.clear()
        fields.forEach { field ->
            fieldsParCode[field.code] = field
            val (rowView, editable) = creerLigne(field)
            vuesParCode[field.code] = editable
            wrappersParCode[field.code] = rowView
            // Champ auto-sélectionné à valeur unique (cf. EditableField.masque) : masqué de l'UI
            // mais sa valeur reste dans le payload (lireValeurs itère toutes les vues).
            if (field.masque) rowView.visibility = View.GONE
            parent.addView(rowView)
        }
        // Référence « non touché par l'utilisateur » pour le drapeau dirty — AVANT la première
        // passe des règles change.
        valeursAuRendu = lireValeurs().mapValues { it.value?.toString() ?: "" }
        // Évaluation initiale + listeners sur tous les éditables pour ré-évaluer en live.
        attacherListenersDynamiques()
        appliquerVisibiliteConditionnelle()
        appliquerChangeRules()
        appliquerValidations()
        // Notifie aussi un état initial pour que le bouton démarre dans le bon mode
        // dès le rendu (typiquement : disabled tant que les obligatoires sont vides).
        onChangement?.invoke()
    }

    /** Déclare les règles `change` du schéma (cf. [ChangeRules]) et les applique une fois.
     *  À appeler après [rendre]. */
    fun setReglesChange(lignes: List<String>) {
        reglesChange = ChangeRules.parser(lignes)
        appliquerChangeRules()
    }

    /** Évalue les règles `change` contre les valeurs courantes (enrichies des drapeaux
     *  `__cd`/`__dirty`) et applique les champs à mettre à jour. La garde [appliquantChange]
     *  empêche la récursion via les listeners. */
    private fun appliquerChangeRules() {
        if (reglesChange.isEmpty() || appliquantChange) return
        val maj = ChangeRules.evaluer(reglesChange, valeursPourExpressions())
        if (maj.isEmpty()) return
        appliquantChange = true
        try {
            maj.forEach { (code, valeur) ->
                appliquerValeur(code, valeur)
                // Mémorise la valeur posée par le moteur (relue après pose, donc normalisée
                // par le widget) : référence du drapeau dirty — tant que l'utilisateur ne la
                // change pas, le champ reste « non touché » et re-calculable.
                dernieresValeursAuto[code] = lireValeurs()[code]?.toString() ?: ""
            }
        } finally {
            appliquantChange = false
        }
    }

    /** Pose programmatiquement la valeur [valeur] dans le widget du champ [code] (utilisé par
     *  les règles change). Ne fait rien si le champ n'existe pas / type non supporté. */
    private fun appliquerValeur(code: String, valeur: Any?) {
        val vue = vuesParCode[code] ?: return
        val field = fieldsParCode[code] ?: return
        when (field.viewType) {
            ViewType.TEXT, ViewType.TEXTAREA, ViewType.NUMBER ->
                (vue as EditText).setText(valeur?.toString() ?: "")
            ViewType.CHECKBOX -> (vue as android.widget.CheckBox).isChecked = when (valeur) {
                is Boolean -> valeur
                is Number -> valeur.toInt() != 0
                is String -> valeur.equals("true", true) || valeur == "1"
                else -> false
            }
            ViewType.SELECT -> {
                val sp = vue as Spinner
                val cible = valeur?.toString()
                val idx = field.values.indexOfFirst { it.value == cible }
                sp.setSelection(if (idx >= 0) idx + 1 else 0)
            }
            ViewType.RADIO -> {
                val rg = vue as android.widget.RadioGroup
                val cible = valeur?.toString()
                val bouton = (0 until rg.childCount)
                    .mapNotNull { rg.getChildAt(it) as? android.widget.RadioButton }
                    .firstOrNull { it.tag == cible }
                if (bouton != null) rg.check(bouton.id) else rg.clearCheck()
            }
            ViewType.DATE, ViewType.TIME, ViewType.DATETIME -> (vue as TextView).apply {
                val s = valeur?.toString().orEmpty()
                tag = s
                if (s.isNotEmpty()) { text = s; setTextColor(couleurValeur) }
            }
            // Les champs MEDIA ne sont jamais pilotés par les règles `change` du schéma (ce sont
            // des fichiers choisis par l'utilisateur) : no-op pour ne pas écraser la liste d'URIs.
            ViewType.MEDIA -> { /* rien */ }
            ViewType.SELECT_MULTIPLE -> (vue as TextView).apply {
                @Suppress("UNCHECKED_CAST")
                val liste = when (valeur) {
                    is List<*> -> valeur.map { it.toString() }
                    null -> emptyList()
                    else -> listOf(valeur.toString())
                }
                tag = liste
                val labels = field.values.filter { it.value in liste }.map { it.label }
                text = if (labels.isEmpty()) "Choisir…" else labels.joinToString(", ")
            }
            // TAXON : cible improbable d'une règle change, non géré.
            ViewType.TAXON -> {}
        }
    }

    /** Enregistre un callback notifié à chaque modification de champ ET immédiatement
     *  pour positionner l'état initial. Remplace tout callback précédent. */
    fun setOnChangement(callback: () -> Unit) {
        onChangement = callback
        callback()
    }

    /** Helper interne : recalcule la visibilité conditionnelle ET prévient l'écran appelant.
     *  À appeler depuis tous les listeners/dialogs/pickers où une valeur de champ change. */
    private fun notifierChangement() {
        appliquerVisibiliteConditionnelle()
        // Auto-remplissage des champs dépendants. La garde interne évite la récursion quand
        // c'est nous-mêmes qui modifions un champ via un patch.
        if (!appliquantChange) appliquerChangeRules()
        appliquerValidations()
        onChangement?.invoke()
    }

    /** Retourne les codes des champs marqués `obligatoire=true` dont la valeur est vide
     *  ET qui sont actuellement visibles à l'écran (un champ masqué par une expression
     *  `hidden` ne doit pas bloquer le submit). Utilisé par l'écran appelant pour
     *  désactiver le bouton de submit tant qu'il reste des champs requis non remplis. */
    fun champsObligatoiresManquants(): List<String> {
        val valeurs = valeursPourExpressions()
        return fieldsParCode.filter { (code, field) ->
            // Obligatoire statique (required: true) OU dynamique (required: ({value}) => …,
            // ex. champs végétation requis seulement au passage 2 — Point écoute avifaune).
            val requis = field.obligatoire ||
                (field.obligatoireExpr != null && HiddenExpr.evaluerBooleen(field.obligatoireExpr, valeurs))
            if (!requis) return@filter false
            if (wrappersParCode[code]?.visibility == View.GONE) return@filter false
            when (val v = valeurs[code]) {
                null -> true
                is String -> v.isBlank()
                is List<*> -> v.isEmpty()
                else -> false
            }
        }.keys.toList()
    }

    /** Codes des champs actuellement en violation de min/max. Un champ vide ne compte pas
     *  (c'est `obligatoire` qui s'en charge) et un champ masqué non plus. À combiner avec
     *  [champsObligatoiresManquants] côté caller pour piloter l'état du bouton de submit. */
    fun champsInvalides(): List<String> {
        val valeurs = lireValeurs()
        return fieldsParCode.mapNotNull { (code, field) ->
            if (field.viewType != ViewType.NUMBER) return@mapNotNull null
            if (wrappersParCode[code]?.visibility == View.GONE) return@mapNotNull null
            val v = ValidationExpr.violation(valeurs[code], field.minValue, field.maxValue, valeurs)
            if (v != null) code else null
        }
    }

    /** Met à jour chaque TextView d'erreur sous les champs NUMBER ayant des bornes. Appelée
     *  après chaque modification de champ — une borne `(value) => value.autre_champ` doit
     *  être ré-évaluée quand l'autre champ change, pas seulement le champ courant. */
    private fun appliquerValidations() {
        if (erreursParCode.isEmpty()) return
        val valeurs = lireValeurs()
        erreursParCode.forEach { (code, tvErreur) ->
            val field = fieldsParCode[code] ?: return@forEach
            val violation = ValidationExpr.violation(
                valeurs[code], field.minValue, field.maxValue, valeurs,
            )
            if (violation == null) {
                tvErreur.visibility = View.GONE
            } else {
                tvErreur.text = when (violation.type) {
                    ValidationExpr.Violation.Type.TROP_PETIT ->
                        "Valeur ≥ ${formaterBorne(violation.borne)} attendue"
                    ValidationExpr.Violation.Type.TROP_GRAND ->
                        "Valeur ≤ ${formaterBorne(violation.borne)} attendue"
                }
                tvErreur.visibility = View.VISIBLE
            }
        }
    }

    /** Formate une borne pour l'affichage : entier sans décimale (`10`, pas `10.0`) si la
     *  valeur est entière, sinon avec un seul chiffre significatif après la virgule. */
    private fun formaterBorne(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** Attache un listener "valeur changée" sur chaque éditeur. Chaque modification
     *  re-évalue les expressions `hidden` de tous les champs et met à jour leur visibilité. */
    private fun attacherListenersDynamiques() {
        vuesParCode.forEach { (_, v) ->
            when (v) {
                is android.widget.CheckBox -> v.setOnCheckedChangeListener { _, _ ->
                    notifierChangement()
                }
                is Spinner -> v.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        notifierChangement()
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
                is EditText -> v.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        notifierChangement()
                    }
                })
                // DATE / TIME / DATETIME / SELECT_MULTIPLE : TextView cliquables dont la valeur
                // change via leur propre dialog/picker, qui appelle déjà notifierChangement().
                // Aucun listener à attacher ici (l'ancien hook TextView était inopérant).
            }
        }
    }

    /** Évalue chaque expression `hidden` et met à jour la visibilité du wrapper. */
    private fun appliquerVisibiliteConditionnelle() {
        val valeurs = valeursPourExpressions()
        fieldsParCode.forEach { (code, field) ->
            val expr = field.hiddenExpr ?: return@forEach
            val masquer = HiddenExpr.masquer(expr, valeurs)
            wrappersParCode[code]?.visibility = if (masquer) View.GONE else View.VISIBLE
        }
    }

    /** Valeurs courantes ENRICHIES pour le moteur d'expressions : pour chaque champ dont les
     *  options portent un cd_nomenclature (datalists nomenclature), expose le cd de l'option
     *  sélectionnée sous la clé `<code>__cd`. C'est la cible des réécritures
     *  `meta.nomenclatures[value.X].cd_nomenclature` → `${X__cd}` de [HiddenExpr.normaliser]
     *  (protocoles loutre/blaireau : sexe, stade de vie… selon la technique d'observation).
     *  Aucune sélection → clé absente → l'expression compare contre null (parité web). */
    private fun valeursPourExpressions(): Map<String, Any?> {
        val valeurs = lireValeurs().toMutableMap()
        fieldsParCode.forEach { (code, field) ->
            // Drapeau « modifié par l'utilisateur » (cf. valeursAuRendu/dernieresValeursAuto).
            val courantStr = valeurs[code]?.toString() ?: ""
            val reference = dernieresValeursAuto[code] ?: valeursAuRendu[code] ?: ""
            valeurs["${code}__dirty"] = courantStr != reference
            // cd_nomenclature de l'option sélectionnée (datalists nomenclature).
            val courant = courantStr.takeIf { it.isNotEmpty() } ?: return@forEach
            field.values.firstOrNull { it.value == courant }?.cdNomenclature
                ?.let { valeurs["${code}__cd"] = it }
        }
        return valeurs
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
            ViewType.TIME -> (v as TextView).tag as? String ?: ""
            ViewType.DATETIME -> (v as TextView).tag as? String ?: ""
            ViewType.TAXON -> {
                // `tag` porte le cd_nom Int résolu via TaxRefCache lors du choix d'une
                // suggestion. Null si l'utilisateur a tapé un texte qui n'a pas matché.
                (v as android.widget.AutoCompleteTextView).tag as? Int
            }
            ViewType.SELECT -> {
                val sp = v as Spinner
                val idx = sp.selectedItemPosition
                if (idx <= 0) "" else field.values.getOrNull(idx - 1)?.value.orEmpty()
            }
            ViewType.RADIO -> {
                val rg = v as android.widget.RadioGroup
                val coche = rg.checkedRadioButtonId
                if (coche == View.NO_ID) "" else rg.findViewById<View>(coche)?.tag as? String ?: ""
            }
            ViewType.SELECT_MULTIPLE -> {
                // Liste vide → null (sémantique "non renseigné" attendue par le serveur
                // monitoring, aligné sur ce qu'envoie le formulaire web).
                @Suppress("UNCHECKED_CAST")
                ((v as TextView).tag as? List<String>)?.takeIf { it.isNotEmpty() }
            }
            // CheckBox non cochée = null (= "non renseigné"), cochée = true. Le serveur
            // monitoring ne semble pas distinguer false explicite de "non renseigné".
            ViewType.CHECKBOX -> if ((v as android.widget.CheckBox).isChecked) true else null
            // MEDIA : URI String stockée dans le tag du LinearLayout, ou null. Le payload
            // POST de l'objet ne porte PAS cette valeur — l'envoi du fichier est différé
            // à OutboxEnvoi (cf. SaisieEnAttente.mediaPathLocal).
            ViewType.MEDIA -> {
                @Suppress("UNCHECKED_CAST")
                (v as LinearLayout).tag as? List<String> ?: emptyList<String>()
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
            // « * » pour un obligatoire statique OU conditionnel (required dynamique : dans
            // les schémas observés, la condition d'obligation suit celle de visibilité —
            // quand le champ est affiché, il est requis).
            text = if (field.obligatoire || field.obligatoireExpr != null) "${field.label} *" else field.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(couleurSecondaire)
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
            ViewType.TIME -> creerChampTime(field)
            ViewType.DATETIME -> creerChampDateTime(field)
            ViewType.TAXON -> creerChampTaxon(field)
            ViewType.SELECT -> creerSpinner(field)
            ViewType.RADIO -> creerChampRadio(field)
            ViewType.SELECT_MULTIPLE -> creerChampMultiSelect(field)
            ViewType.CHECKBOX -> creerCheckBox(field)
            ViewType.MEDIA -> creerChampMedia(field)
        }
        container.addView(editable)
        // Message d'erreur de validation min/max (NUMBER avec bornes uniquement). Créé GONE,
        // peuplé/affiché par [appliquerValidations] à chaque modification de champ.
        if (field.viewType == ViewType.NUMBER &&
            (field.minValue != null || field.maxValue != null)
        ) {
            val tvErreur = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(couleurErreur)
                setPadding(0, (2 * density).toInt(), 0, 0)
                visibility = View.GONE
            }
            container.addView(tvErreur)
            erreursParCode[field.code] = tvErreur
        }
        // Texte d'aide (definition du schéma serveur) sous le champ — utile pour expliquer
        // ce qu'on attend, particulièrement sur les protocoles techniques.
        field.aide?.takeIf { it.isNotEmpty() }?.let { aide ->
            val tvAide = TextView(ctx).apply {
                text = aide
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(couleurSecondaire)
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
            val defautInitial = field.value?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
            defautInitial?.let { setText(it) }
            // NUMBER pré-rempli avec un défaut serveur (typiquement "0") : on traite ce défaut
            // comme une valeur « fantôme ». Au focus, s'il est encore intact, on le vide pour
            // saisir une autre valeur directement ; si le champ est laissé vide, on le restaure
            // au blur — le défaut reste donc la valeur par défaut sans gêner la frappe.
            if (field.viewType == ViewType.NUMBER && defautInitial != null) {
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        if (text?.toString() == defautInitial) setText("")
                    } else if (text.isNullOrEmpty()) {
                        setText(defautInitial)
                    }
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

    /** Champ DATE : un TextView cliquable qui ouvre un DatePickerDialog. La date choisie est
     *  stockée dans `tag` au format "YYYY-M-D" (sans zero-padding, comme le formulaire web
     *  GeoNature) et affichée au format FR pour l'utilisateur. */
    private fun creerChampDate(field: EditableField): TextView {
        val fmtAffichage = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        // Format aligné sur le formulaire web GeoNature : `yyyy-M-d` sans zero-padding
        // sur le mois et le jour (ex: "2026-5-21"). Le SimpleDateFormat de Java avec un
        // seul `M`/`d` produit exactement ce format.
        val fmtIso = SimpleDateFormat("yyyy-M-d", Locale.US)
        val tv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding((8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt())
            setBackgroundResource(fr.ariegenature.geomys.R.drawable.bg_carte_contour)
            text = "Choisir une date…"
            setTextColor(couleurSecondaire)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Pré-remplir si fourni dans le field. Le format de stockage est "yyyy-M-d" (sans
        // zero-padding) donc la longueur varie entre 8 et 10 — on ne peut pas s'appuyer
        // sur substring(0, 10). On strippe une éventuelle partie heure ISO, puis on parse
        // en mode lenient (par défaut) qui accepte aussi "yyyy-MM-dd".
        (field.value as? String)?.takeIf { it.isNotBlank() }?.let { brut ->
            val sansHeure = brut.substringBefore('T').substringBefore(' ')
            val date = runCatching { fmtIso.parse(sansHeure) }.getOrNull()
            if (date != null) {
                tv.tag = fmtIso.format(date)
                tv.text = fmtAffichage.format(date)
                tv.setTextColor(couleurValeur)
            }
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
                    tv.setTextColor(couleurValeur)
                    notifierChangement()
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
            ).show()
        }
        return tv
    }

    /** Champ TIME : TextView cliquable qui ouvre un TimePickerDialog 24h. L'heure choisie
     *  est stockée dans `tag` au format "HH:MM" (ce que le serveur GeoNature attend) et
     *  affichée à l'utilisateur tel quel. */
    private fun creerChampTime(field: EditableField): TextView {
        val tv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding((8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt())
            setBackgroundResource(fr.ariegenature.geomys.R.drawable.bg_carte_contour)
            text = "Choisir une heure…"
            setTextColor(couleurSecondaire)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Pré-remplit avec field.value si fourni (accepte "HH:MM", "HH:MM:SS", "HHhMM").
        (field.value as? String)?.let { brut ->
            parseHeure(brut)?.let { (h, m) ->
                val iso = "%02d:%02d".format(h, m)
                tv.tag = iso
                tv.text = iso
                tv.setTextColor(couleurValeur)
            }
        }
        tv.setOnClickListener {
            val (hInit, mInit) = (tv.tag as? String)?.let { parseHeure(it) }
                ?: Pair(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                        java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE))
            android.app.TimePickerDialog(ctx, { _, h, m ->
                val iso = "%02d:%02d".format(h, m)
                tv.tag = iso
                tv.text = iso
                tv.setTextColor(couleurValeur)
                notifierChangement()
            }, hInit, mInit, true).show()
        }
        return tv
    }

    /** Champ DATETIME : TextView cliquable qui enchaîne un DatePickerDialog puis un
     *  TimePickerDialog. La valeur est stockée dans `tag` au format "yyyy-MM-dd HH:mm:ss"
     *  (accepté par le backend GeoNature, cf. formatDateTime de gn_mobile_monitoring) et
     *  affichée au format FR "dd/MM/yyyy HH:mm". */
    private fun creerChampDateTime(field: EditableField): TextView {
        val fmtAffichage = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        val fmtStockage = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val tv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding((8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt())
            setBackgroundResource(fr.ariegenature.geomys.R.drawable.bg_carte_contour)
            text = "Choisir date et heure…"
            setTextColor(couleurSecondaire)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Pré-remplir : on accepte le séparateur ISO 'T' ou l'espace, avec ou sans secondes.
        (field.value as? String)?.takeIf { it.isNotBlank() }?.let { brut ->
            val normalise = brut.trim().replace('T', ' ')
            val date = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm").firstNotNullOfOrNull { pat ->
                runCatching { SimpleDateFormat(pat, Locale.US).parse(normalise) }.getOrNull()
            }
            if (date != null) {
                tv.tag = fmtStockage.format(date)
                tv.text = fmtAffichage.format(date)
                tv.setTextColor(couleurValeur)
            }
        }
        tv.setOnClickListener {
            val cal = Calendar.getInstance()
            (tv.tag as? String)?.let { iso ->
                runCatching { cal.time = fmtStockage.parse(iso)!! }
            }
            DatePickerDialog(
                ctx,
                { _, y, mo, d ->
                    cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, mo); cal.set(Calendar.DAY_OF_MONTH, d)
                    android.app.TimePickerDialog(ctx, { _, h, mi ->
                        cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, mi); cal.set(Calendar.SECOND, 0)
                        tv.tag = fmtStockage.format(cal.time)
                        tv.text = fmtAffichage.format(cal.time)
                        tv.setTextColor(couleurValeur)
                        notifierChangement()
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
            ).show()
        }
        return tv
    }

    /** Parse tolérant les variantes courantes (HH:MM, HH:MM:SS, HHhMM, "10h30") en (heure, minute). */
    private fun parseHeure(raw: String): Pair<Int, Int>? {
        val s = raw.trim().lowercase().replace("h", ":")
        val parts = s.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        return h to m
    }

    /** Champ TAXON : AutoCompleteTextView branché sur le cache TaxRef local. À la sélection
     *  d'une suggestion, on résout le `cd_nom` via [fr.ariegenature.geomys.store.TaxRefCache.get]
     *  et on le stocke dans le `tag` du champ pour la lecture côté envoi. */
    private fun creerChampTaxon(field: EditableField): android.widget.AutoCompleteTextView {
        val ac = android.widget.AutoCompleteTextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            threshold = 2
            setHint("Tapez le nom du taxon…")
            // Pré-remplissage si field.value est un cd_nom déjà connu (édition future).
            (field.value as? Int)?.takeIf { it > 0 }?.let { cd ->
                val entry = fr.ariegenature.geomys.store.TaxRefCache.entreesParCdNom()[cd]
                if (entry != null) {
                    setText(entry.nomFrOriginal ?: entry.sciNom, false)
                    tag = cd
                }
            }
            (field.value as? String)?.takeIf { it.isNotEmpty() }?.let { setText(it, false) }
        }
        // Adapter peuplé en arrière-plan via une coroutine rattachée au [scope] du Fragment.
        // La liste des taxons est grosse (15k+) → on évite de bloquer le rendu du formulaire.
        // Si le schéma déclare une id_list_taxonomy, on restreint l'autocomplete aux
        // cd_nom qui appartiennent à cette liste (= taxons "autorisés" pour ce protocole).
        // Le rattachement au lifecycle annule automatiquement le scan si la View est détruite
        // pendant le calcul (rotation, back) — plus de race contre une vue déjà détachée.
        val idListeRestreinte = field.idListeTaxonomieRestreinte
        val hintInitial = ac.hint
        scope.launch {
            val (noms, listeVide, diagDetails) = withContext(Dispatchers.Default) {
                // Index memoizé côté TaxRefCache : pas de re-matérialisation des 15-50k
                // entrées à chaque rendu d'un champ TAXON (cf. audit B5).
                val restreint = fr.ariegenature.geomys.store.TaxRefCache.nomsSuggestion(idListeRestreinte)
                // Liste taxonomique imposée par le protocole mais AUCUN taxon n'en est en cache
                // (liste non synchronisée — la résolution de l'id de liste passe parfois par le
                // réseau, le cache local peut ne pas la couvrir). Dégradation gracieuse : on
                // propose TOUTES les espèces du cache plutôt qu'un champ inutilisable — un champ
                // vide empêchait carrément de saisir l'obs sur le terrain (« l'autocomplétion ne
                // marche plus »), pire qu'une suggestion hors liste que le serveur validera.
                val listeVide = idListeRestreinte != null && restreint.isEmpty()
                val effectifs = if (listeVide)
                    fr.ariegenature.geomys.store.TaxRefCache.nomsSuggestion(null) else restreint
                val diag = when {
                    listeVide -> "liste=$idListeRestreinte VIDE en cache (non synchronisée) — " +
                        "repli sur toutes les espèces (${effectifs.size})"
                    idListeRestreinte != null -> "liste=$idListeRestreinte, ${effectifs.size} suggestions"
                    else -> "liste=null (toutes), ${effectifs.size} suggestions"
                }
                Triple(effectifs, listeVide, diag)
            }
            android.util.Log.i("FormulaireRenderer",
                "Champ TAXON '${field.code}' → $diagDetails")
            // Adapter posé inconditionnellement : le scope (lifecycle du Fragment) annule déjà
            // la coroutine si la vue est détruite. L'ancien garde `isAttachedToWindow` sautait
            // silencieusement la pose quand le rendu aboutissait AVANT l'attachement de la vue
            // (cache rapide) → champ définitivement sans suggestions, de façon intermittente.
            val adapter = fr.ariegenature.geomys.ui.saisie.createSpeciesAutocompleteAdapter(ctx, noms)
            ac.setAdapter(adapter)
            ac.hint = if (listeVide)
                "Liste du protocole non synchronisée — toutes les espèces proposées" else hintInitial
        }
        // Sélection d'une suggestion : on résout le cd_nom via TaxRefCache et on le stocke.
        ac.setOnItemClickListener { _, _, _, _ ->
            val saisi = ac.text?.toString().orEmpty()
            ac.tag = fr.ariegenature.geomys.store.TaxRefCache.get(saisi)?.cdNom
            notifierChangement()
        }
        // Si l'utilisateur édite manuellement, on invalide le cd_nom (oblige re-sélection).
        ac.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // Re-tente la résolution sur saisie complète (cas où l'utilisateur a tapé
                // un nom exact sans cliquer une suggestion).
                val saisi = s?.toString().orEmpty()
                val resolu = fr.ariegenature.geomys.store.TaxRefCache.get(saisi)?.cdNom
                if (resolu != ac.tag) ac.tag = resolu
            }
        })
        return ac
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
            setBackgroundResource(fr.ariegenature.geomys.R.drawable.bg_carte_contour)
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
            tv.setTextColor(couleurValeur)
        } else {
            tv.text = "Choisir…"
            tv.setTextColor(couleurSecondaire)
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
                        tv.setTextColor(couleurSecondaire)
                    } else {
                        tv.text = liste.joinToString(", ") { it.label }
                        tv.setTextColor(couleurValeur)
                    }
                    notifierChangement()
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

    /** Champ RADIO : groupe de boutons radio (widget serveur `radio`). Disposition horizontale
     *  pour les petits jeux d'options courtes (ex. Oui/Non), verticale sinon — heuristique
     *  alignée sur gn_mobile_monitoring. La `value` de l'option cochée est portée par le `tag`
     *  de chaque bouton ; on pré-coche field.value si fournie (défaut serveur ou édition). */
    private fun creerChampRadio(field: EditableField): android.widget.RadioGroup {
        val horizontal = field.values.size <= 4 &&
            field.values.all { it.label.length <= 15 } &&
            field.values.sumOf { it.label.length } <= 40
        val rg = android.widget.RadioGroup(ctx).apply {
            orientation = if (horizontal) android.widget.RadioGroup.HORIZONTAL
                else android.widget.RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val defaut = field.value?.toString()
        field.values.forEach { opt ->
            val rb = com.google.android.material.radiobutton.MaterialRadioButton(ctx).apply {
                id = View.generateViewId()
                text = opt.label
                tag = opt.value
                setTextColor(couleurValeur)
                // Marge à droite pour aérer les boutons quand ils sont côte à côte.
                if (horizontal) setPadding(0, 0, (16 * density).toInt(), 0)
            }
            rg.addView(rb)
            if (opt.value == defaut) rg.check(rb.id)
        }
        // Listener posé APRÈS le pré-cochage du défaut pour ne pas notifier pendant le rendu.
        rg.setOnCheckedChangeListener { _, _ -> notifierChangement() }
        return rg
    }

    /** Champ MEDIA : conteneur horizontal [bouton "Ajouter une photo" | nom-de-fichier | ✕].
     *  La sélection elle-même est déléguée au Fragment via [onChoixMedia] (qui doit avoir
     *  registré un ActivityResultLauncher.GetContent). Le `tag` du LinearLayout porte l'URI
     *  String du fichier importé localement, ou null tant que rien n'est sélectionné. La
     *  contrainte de cardinalité est "un seul fichier" pour cette version. */
    private fun creerChampMedia(field: EditableField): LinearLayout {
        // Conteneur VERTICAL : une liste de fichiers (nom + ✕) puis un bouton d'ajout. Le `tag`
        // du conteneur porte la LISTE mutable des URIs locales (lue par lireValeurs ; mutée en
        // place pour que la référence reste valide). Multi-fichiers.
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val uris = mutableListOf<String>()
        container.tag = uris
        val liste = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val tvVide = TextView(ctx).apply {
            text = "Aucune photo"
            setTextColor(couleurSecondaire)
        }
        fun rebuild() {
            liste.removeAllViews()
            if (uris.isEmpty()) {
                liste.addView(tvVide)
            } else {
                uris.toList().forEach { uri ->
                    val ligne = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    }
                    val tv = TextView(ctx).apply {
                        text = java.io.File(android.net.Uri.parse(uri).path ?: "").name
                        setTextColor(couleurValeur)
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                        ).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                    }
                    val del = com.google.android.material.button.MaterialButton(
                        ctx, null, com.google.android.material.R.attr.materialIconButtonStyle,
                    ).apply {
                        text = "✕"
                        setOnClickListener {
                            uris.remove(uri)
                            rebuild()
                            notifierChangement()
                        }
                    }
                    ligne.addView(tv)
                    ligne.addView(del)
                    liste.addView(ligne)
                }
            }
        }
        val btn = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "Ajouter des photos"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener {
                val cb = onChoixMedia ?: run {
                    android.widget.Toast.makeText(ctx,
                        "Picker média non configuré (callback manquant)",
                        android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                cb(field.code) { nouvelles ->
                    val ajout = nouvelles.filter { it.isNotEmpty() }
                    if (ajout.isEmpty()) return@cb
                    uris.addAll(ajout)
                    rebuild()
                    notifierChangement()
                }
            }
        }
        // Pré-remplissage (édition) : la valeur peut être une List<String> (multi) ou, par
        // compatibilité, une String unique (ancien format).
        when (val v = field.value) {
            is List<*> -> uris.addAll(v.filterIsInstance<String>().filter { it.isNotEmpty() })
            is String -> if (v.isNotEmpty()) uris.add(v)
        }
        rebuild()
        container.addView(liste)
        container.addView(btn)
        return container
    }

    /** Booléen rendu comme CheckBox. Pré-cochée si field.value est `true` (Boolean), ou la
     *  chaîne "true"/"1" (cas où le serveur envoie une string même pour un bool). */
    private fun creerCheckBox(field: EditableField): android.widget.CheckBox {
        val coche = when (val v = field.value) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true) || v == "1"
            is Number -> v.toInt() != 0
            else -> false
        }
        return android.widget.CheckBox(ctx).apply {
            // On laisse la label principale au TextView au-dessus pour rester cohérent avec
            // les autres champs (libellé + ' *' si obligatoire). La CheckBox elle-même n'a
            // donc pas de texte à droite.
            text = ""
            isChecked = coche
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }
}