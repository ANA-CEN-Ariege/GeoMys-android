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
    /** Couleur de texte d'une valeur renseignée, résolue depuis le thème (colorOnSurface) —
     *  remplace un noir codé en dur invisible en mode sombre. */
    private val couleurValeur: Int = com.google.android.material.color.MaterialColors.getColor(
        parent, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt(),
    )
    /** code → vue éditable racine (l'EditText / Spinner / TextView selon le widget). */
    private val vuesParCode = linkedMapOf<String, View>()
    /** code → champ d'origine (pour relire son viewType au moment de lire les valeurs). */
    private val fieldsParCode = linkedMapOf<String, EditableField>()
    /** code → conteneur LinearLayout englobant le champ — pour piloter sa visibilité
     *  via les expressions `hidden` du schéma serveur. */
    private val wrappersParCode = linkedMapOf<String, View>()
    /** Callback notifié à chaque modification d'un champ (saisie, sélection, picker…).
     *  Utilisé par l'écran appelant pour piloter l'état du bouton de submit selon que
     *  les champs obligatoires sont remplis ou non. */
    private var onChangement: (() -> Unit)? = null
    /** Règles `change` du schéma (auto-remplissage de champs dépendants). Vide par défaut. */
    private var reglesChange: List<ChangeRules.Regle> = emptyList()
    /** Garde anti-récursion : true pendant qu'on applique un patch des règles change
     *  (sinon les setText/setSelection re-déclenchent les listeners → boucle infinie). */
    private var appliquantChange = false

    fun rendre(fields: List<EditableField>) {
        parent.removeAllViews()
        vuesParCode.clear()
        fieldsParCode.clear()
        wrappersParCode.clear()
        fields.forEach { field ->
            fieldsParCode[field.code] = field
            val (rowView, editable) = creerLigne(field)
            vuesParCode[field.code] = editable
            wrappersParCode[field.code] = rowView
            parent.addView(rowView)
        }
        // Évaluation initiale + listeners sur tous les éditables pour ré-évaluer en live.
        attacherListenersDynamiques()
        appliquerVisibiliteConditionnelle()
        appliquerChangeRules()
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

    /** Évalue les règles `change` contre les valeurs courantes et applique les champs à
     *  mettre à jour. La garde [appliquantChange] empêche la récursion via les listeners. */
    private fun appliquerChangeRules() {
        if (reglesChange.isEmpty() || appliquantChange) return
        val maj = ChangeRules.evaluer(reglesChange, lireValeurs())
        if (maj.isEmpty()) return
        appliquantChange = true
        try {
            maj.forEach { (code, valeur) -> appliquerValeur(code, valeur) }
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
            ViewType.DATE, ViewType.TIME -> (vue as TextView).apply {
                val s = valeur?.toString().orEmpty()
                tag = s
                if (s.isNotEmpty()) { text = s; setTextColor(couleurValeur) }
            }
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
        onChangement?.invoke()
    }

    /** Retourne les codes des champs marqués `obligatoire=true` dont la valeur est vide
     *  ET qui sont actuellement visibles à l'écran (un champ masqué par une expression
     *  `hidden` ne doit pas bloquer le submit). Utilisé par l'écran appelant pour
     *  désactiver le bouton de submit tant qu'il reste des champs requis non remplis. */
    fun champsObligatoiresManquants(): List<String> {
        val valeurs = lireValeurs()
        return fieldsParCode.filter { (code, field) ->
            if (!field.obligatoire) return@filter false
            if (wrappersParCode[code]?.visibility == View.GONE) return@filter false
            when (val v = valeurs[code]) {
                null -> true
                is String -> v.isBlank()
                is List<*> -> v.isEmpty()
                else -> false
            }
        }.keys.toList()
    }

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
                // DATE et SELECT_MULTIPLE reposent sur des TextView dont la valeur change via
                // un dialog/picker — on hooke leur invalidation via le ChangeListener du tag.
                is TextView -> v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }
        }
    }

    /** Évalue chaque expression `hidden` et met à jour la visibilité du wrapper. */
    private fun appliquerVisibiliteConditionnelle() {
        val valeurs = lireValeurs()
        fieldsParCode.forEach { (code, field) ->
            val expr = field.hiddenExpr ?: return@forEach
            val masquer = HiddenExpr.masquer(expr, valeurs)
            wrappersParCode[code]?.visibility = if (masquer) View.GONE else View.VISIBLE
        }
    }

    /** Notification publique : à appeler après une modification "externe" d'un champ
     *  (par ex. depuis le code appelant qui pré-remplit des valeurs avant rendu). */
    fun reevaluerVisibilites() = appliquerVisibiliteConditionnelle()

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
            ViewType.SELECT_MULTIPLE -> {
                // Liste vide → null (sémantique "non renseigné" attendue par le serveur
                // monitoring, aligné sur ce qu'envoie le formulaire web).
                @Suppress("UNCHECKED_CAST")
                ((v as TextView).tag as? List<String>)?.takeIf { it.isNotEmpty() }
            }
            // CheckBox non cochée = null (= "non renseigné"), cochée = true. Le serveur
            // monitoring ne semble pas distinguer false explicite de "non renseigné".
            ViewType.CHECKBOX -> if ((v as android.widget.CheckBox).isChecked) true else null
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
            ViewType.TIME -> creerChampTime(field)
            ViewType.TAXON -> creerChampTaxon(field)
            ViewType.SELECT -> creerSpinner(field)
            ViewType.SELECT_MULTIPLE -> creerChampMultiSelect(field)
            ViewType.CHECKBOX -> creerCheckBox(field)
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
            setBackgroundResource(fr.ariegenature.geonat.R.drawable.bg_carte_contour)
            text = "Choisir une date…"
            setTextColor(0xFF888888.toInt())
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
            setBackgroundResource(fr.ariegenature.geonat.R.drawable.bg_carte_contour)
            text = "Choisir une heure…"
            setTextColor(0xFF888888.toInt())
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

    /** Parse tolérant les variantes courantes (HH:MM, HH:MM:SS, HHhMM, "10h30") en (heure, minute). */
    private fun parseHeure(raw: String): Pair<Int, Int>? {
        val s = raw.trim().lowercase().replace("h", ":")
        val parts = s.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        return h to m
    }

    /** Champ TAXON : AutoCompleteTextView branché sur le cache TaxRef local. À la sélection
     *  d'une suggestion, on résout le `cd_nom` via [fr.ariegenature.geonat.store.TaxRefCache.get]
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
                val entry = fr.ariegenature.geonat.store.TaxRefCache.entreesParCdNom()[cd]
                if (entry != null) {
                    setText(entry.nomFrOriginal ?: entry.sciNom, false)
                    tag = cd
                }
            }
            (field.value as? String)?.takeIf { it.isNotEmpty() }?.let { setText(it, false) }
        }
        // Adapter peuplé en arrière-plan via Thread brut (pas de scope dispo ici). La
        // liste des taxons est grosse → on évite de bloquer le rendu du formulaire.
        // Si le schéma déclare une id_list_taxonomy, on restreint l'autocomplete aux
        // cd_nom qui appartiennent à cette liste (= taxons "autorisés" pour ce protocole).
        val idListeRestreinte = field.idListeTaxonomieRestreinte
        Thread {
            val noms: List<String>
            val diagDetails: String
            if (idListeRestreinte != null) {
                val cdNomsAutorises = fr.ariegenature.geonat.store.TaxRefCache.cdNomsDansListe(idListeRestreinte)
                noms = fr.ariegenature.geonat.store.TaxRefCache.toutesLesEntrees()
                    .filter { (_, entry) -> entry.cdNom in cdNomsAutorises }
                    .keys
                    .toList()
                diagDetails = "liste=$idListeRestreinte, ${cdNomsAutorises.size} cd_nom autorisés, ${noms.size} suggestions"
            } else {
                noms = fr.ariegenature.geonat.store.TaxRefCache.toutesLesEntrees().keys.toList()
                diagDetails = "liste=null (toutes), ${noms.size} suggestions"
            }
            android.util.Log.i("FormulaireRenderer",
                "Champ TAXON '${field.code}' → $diagDetails")
            ac.post {
                if (ac.isAttachedToWindow) {
                    val adapter = fr.ariegenature.geonat.ui.saisie.createSpeciesAutocompleteAdapter(ctx, noms)
                    ac.setAdapter(adapter)
                }
            }
        }.start()
        // Sélection d'une suggestion : on résout le cd_nom via TaxRefCache et on le stocke.
        ac.setOnItemClickListener { _, _, _, _ ->
            val saisi = ac.text?.toString().orEmpty()
            ac.tag = fr.ariegenature.geonat.store.TaxRefCache.get(saisi)?.cdNom
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
                val resolu = fr.ariegenature.geonat.store.TaxRefCache.get(saisi)?.cdNom
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
            setBackgroundResource(fr.ariegenature.geonat.R.drawable.bg_carte_contour)
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