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

package fr.ariegenature.geomys.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geomys.network.AdditionalFieldDef
import fr.ariegenature.geomys.network.GeoNatureDataset
import fr.ariegenature.geomys.network.GeoNatureObservateur
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.network.HabitatSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.champFormVisible
import fr.ariegenature.geomys.store.OcctaxFieldsConfig
import fr.ariegenature.geomys.ui.saisie.AdditionalFieldsRenderer
import fr.ariegenature.geomys.ui.saisie.OcctaxFieldsRenderer

private val gsonDetailsReleve = Gson()

/** Minuscule + diacritiques retirés, pour comparer « emile » et « Émile » indifféremment. */
private fun normaliserAccents(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .lowercase()

/** Adaptateur d'autocomplétion sur une liste finie (jeu de données, observateurs) :
 *  - filtrage **insensible aux accents et à la casse**, par *contains* (pas seulement le préfixe) ;
 *  - contrainte vide ⇒ **toute la liste** (permet de déployer le menu au clic, façon spinner).
 *  Le filtrage par défaut d'`AutoCompleteTextView` (préfixe, sensible aux accents) est ainsi remplacé. */
private class AdaptateurAutocomplete(context: Context, private val tous: List<String>) :
    ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, ArrayList(tous)) {
    private val filtre = object : android.widget.Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val c = constraint?.toString()?.let { normaliserAccents(it) }.orEmpty()
            val res = if (c.isEmpty()) tous else tous.filter { normaliserAccents(it).contains(c) }
            return FilterResults().apply { values = res; count = res.size }
        }
        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            setNotifyOnChange(false)
            clear()
            (results?.values as? List<String>)?.let { addAll(it) }
            notifyDataSetChanged()
        }
        override fun convertResultToString(resultValue: Any?): CharSequence =
            resultValue as? CharSequence ?: ""
    }
    override fun getFilter(): android.widget.Filter = filtre
}

/** Jeux de données proposables dans « Détails du relevé » : actifs + rattachés OCCTAX (ou sans
 *  module déclaré), lus depuis le cache local. Couples (id, "nom (id)") pour le dropdown. */
fun datasetsPourDetailsReleve(config: GeoNatureConfig): List<Pair<Int, String>> =
    try {
        val t = object : TypeToken<List<GeoNatureDataset>>() {}.type
        val l: List<GeoNatureDataset> = gsonDetailsReleve.fromJson(config.datasetsCacheJson, t) ?: emptyList()
        val creables = config.datasetsCreablesOcctax
        l.filter {
            it.actif && (it.moduleCodes.isEmpty() || "OCCTAX" in it.moduleCodes) &&
                (creables.isEmpty() || it.id in creables)
        }.map { it.id to "${it.nom} (${it.id})" }
    } catch (_: Exception) { emptyList() }

/** Observateurs proposables dans « Détails du relevé », depuis le cache local. */
fun observateursPourDetailsReleve(config: GeoNatureConfig): List<Pair<Int, String>> =
    try {
        val t = object : TypeToken<List<GeoNatureObservateur>>() {}.type
        val l: List<GeoNatureObservateur> = gsonDetailsReleve.fromJson(config.observateursCacheJson, t) ?: emptyList()
        l.map { it.idRole to it.nomComplet }
    } catch (_: Exception) { emptyList() }

/** Résultat du dialog « Détails du relevé » : les sélections éditables (jeu de données,
 *  observateur) + les champs additionnels OCCTAX_RELEVE collectés. */
data class DetailsReleveResult(
    val idDataset: Int?,
    /** Observateurs sélectionnés (ids), dans l'ordre d'ajout. Vide = aucun (→ utilisateur connecté). */
    val idsObservateurs: List<Int>,
    /** Libellés des observateurs sélectionnés, alignés sur [idsObservateurs]. */
    val nomsObservateurs: List<String>,
    val additionnels: Map<String, String>,
    /** Commentaire libre du relevé, "" si vide. */
    val comment: String = "",
    /** Code HABREF de l'habitat sélectionné, null si aucun. */
    val cdHab: Int? = null,
    /** Libellé de l'habitat sélectionné, null si aucun. */
    val habitatLabel: String? = null,
    /** Code nomenclature TYP_GRP (type de regroupement du relevé), "" si non renseigné/masqué. */
    val typGrp: String = "",
    /** Date+heure de début du relevé (epoch millis), null si non géré. */
    val dateDebut: Long? = null,
    /** Date+heure de fin du relevé (epoch millis), null si non géré. */
    val dateFin: Long? = null,
)

/** Dialog "Détails du relevé" partagé par la saisie multi-taxons ([SaisieObservationFragment])
 *  et mono-taxons ([SaisieRapideFragment]).
 *
 *  Le **jeu de données** et l'**observateur** sont **éditables** (listes déroulantes alimentées
 *  par les caches du serveur), pré-sélectionnés sur les valeurs courantes. Suivent d'éventuelles
 *  lignes d'info en lecture seule ([infos] : position, géométrie…) puis les champs additionnels
 *  niveau OCCTAX_RELEVE ([defs]) éditables. Sur "Valider", [onValider] reçoit le [DetailsReleveResult].
 *
 *  [datasets] / [observateurs] : couples (id, libellé) proposés dans les dropdowns. Si une liste
 *  est vide (cache non chargé), le champ retombe en ligne d'info lecture seule. */
fun ouvrirDialogDetailsReleve(
    ctx: Context,
    infos: List<Pair<String, String>>,
    datasets: List<Pair<Int, String>>,
    idDatasetInitial: Int?,
    nomDatasetInitial: String?,
    observateurs: List<Pair<Int, String>>,
    idsObservateursInitial: List<Int>,
    nomsObservateursInitial: List<String>,
    defs: List<AdditionalFieldDef>,
    valeursInitiales: Map<String, String>,
    settingsJson: String,
    formFieldsJson: String,
    typGrpInitial: String,
    commentInitial: String,
    dateDebutInitial: Long?,
    dateFinInitial: Long?,
    dateAvecHeures: Boolean,
    dateAvecFin: Boolean,
    cdHabInitial: Int?,
    habitatLabelInitial: String?,
    scope: CoroutineScope,
    chercherHabitats: suspend (String) -> List<HabitatSuggestion>,
    onValider: (DetailsReleveResult) -> Unit,
) {
    val density = ctx.resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val racine = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, pad / 2)
        // Capte le focus initial pour qu'aucun champ ne s'ouvre tout seul à l'affichage du dialogue.
        isFocusableInTouchMode = true
    }

    fun titre(t: String) = TextView(ctx).apply {
        text = t
        textSize = 12f
        setTextColor(couleurSecondaire(ctx))
        setPadding(0, (8 * density).toInt(), 0, 0)
    }

    fun ligneLecture(label: String, valeur: String) {
        racine.addView(TextView(ctx).apply {
            text = label; textSize = 12f; setTextColor(couleurSecondaire(ctx))
        })
        racine.addView(TextView(ctx).apply {
            text = valeur; textSize = 15f
            setPadding(0, 0, 0, (8 * density).toInt())
        })
    }

    // Champ « menu déroulant » Material identique à Paramètres (cf. champ_dropdown_releve.xml) :
    // le wrapper TextInputLayout ExposedDropdownMenu est ce qui ouvre la liste au 1er tap.
    fun champDropdown(hint: String): com.google.android.material.textfield.TextInputLayout =
        (android.view.LayoutInflater.from(ctx)
            .inflate(R.layout.champ_dropdown_releve, racine, false)
                as com.google.android.material.textfield.TextInputLayout)
            .apply { this.hint = hint }

    // Sélecteur déroulant (jeu de données / observateur) : renvoie un getter de l'id choisi.
    // Si [options] est vide (cache non chargé), on retombe en ligne lecture seule. Le getter ne
    // renvoie JAMAIS null ni l'id-en-texte quand une sélection initiale existe : il conserve la
    // valeur courante (id + vrai libellé) pour ne pas l'effacer à la validation.
    fun selecteur(
        label: String,
        options: List<Pair<Int, String>>,
        idInitial: Int?,
        nomInitial: String?,
    ): () -> Pair<Int, String>? {
        // Libellé de l'initial : depuis les options si présent, sinon le nom fourni, sinon l'id.
        val labelInitial = options.firstOrNull { it.first == idInitial }?.second
            ?: nomInitial?.takeIf { it.isNotBlank() }
            ?: idInitial?.toString()
        // Repli sur la sélection initiale (id + meilleur libellé connu), jamais null/id-texte.
        fun initialOuNull(): Pair<Int, String>? = idInitial?.let { id -> id to (labelInitial ?: id.toString()) }

        if (options.isEmpty()) {
            ligneLecture(label, labelInitial ?: "—")
            return ::initialOuNull
        }
        racine.addView(titre(label))
        val labels = options.map { it.second }
        // Sélection mémorisée hors du champ texte (comme Paramètres) : le texte n'est plus la
        // source de vérité, on peut donc le vider au clic pour déployer toute la liste sans
        // perdre la valeur si l'utilisateur referme le menu sans re-choisir.
        var idChoisi: Int? = idInitial
        var nomChoisi: String? = labelInitial
        // Widget IDENTIQUE à Paramètres : TextInputLayout ExposedDropdownMenu (inflé) → ouverture
        // de toute la liste dès le 1er tap.
        val til = champDropdown("Rechercher un jeu de données")
        val champ = til.findViewById<MaterialAutoCompleteTextView>(R.id.ac_champ_releve).apply {
            setAdapter(AdaptateurAutocomplete(ctx, labels))
            threshold = 1
            val idx = options.indexOfFirst { it.first == idInitial }
            if (idx >= 0) setText(labels[idx], false)
            else labelInitial?.let { setText(it, false) }
            // Clic = vide le champ puis déploie toute la liste (comme Paramètres).
            setOnClickListener { setText("", false); showDropDown() }
            setOnItemClickListener { _, _, pos, _ ->
                masquerClavier(this)
                val txt = (adapter.getItem(pos) as? String).orEmpty()
                options.firstOrNull { it.second == txt }?.let { idChoisi = it.first; nomChoisi = it.second }
            }
            // Fermeture sans (re)choix : on réaffiche le libellé de la sélection courante.
            setOnDismissListener { setText(nomChoisi.orEmpty(), false) }
        }
        racine.addView(til)
        return { idChoisi?.let { id -> id to (nomChoisi ?: id.toString()) } }
    }

    // Sélecteur MULTIPLE (observateurs) : un champ de recherche qui AJOUTE à une liste affichée
    // dessous (chaque entrée retirable via « ✕ »). Renvoie un getter des couples (id, nom) choisis,
    // dans l'ordre d'ajout. Si [options] est vide (cache non chargé), retombe en lecture seule.
    fun selecteurMulti(
        label: String,
        options: List<Pair<Int, String>>,
        idsInitial: List<Int>,
        nomsInitial: List<String>,
    ): () -> List<Pair<Int, String>> {
        racine.addView(titre(label))
        // LinkedHashMap : conserve l'ordre d'ajout, dédoublonne par id.
        val choisis = LinkedHashMap<Int, String>()
        idsInitial.forEachIndexed { i, id ->
            choisis[id] = options.firstOrNull { it.first == id }?.second
                ?: nomsInitial.getOrNull(i)?.takeIf { it.isNotBlank() }
                ?: id.toString()
        }
        val liste = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun rafraichir() {
            liste.removeAllViews()
            choisis.forEach { (id, nom) ->
                liste.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply {
                        text = nom; textSize = 15f
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(ctx).apply {
                        text = "✕"; textSize = 16f
                        setTextColor(0xFFC62828.toInt())
                        setPadding((12 * density).toInt(), (4 * density).toInt(),
                            (4 * density).toInt(), (4 * density).toInt())
                        isClickable = true
                        setOnClickListener { choisis.remove(id); rafraichir() }
                    })
                })
            }
        }
        if (options.isEmpty()) {
            // Pas de cache : on affiche seulement les éventuels libellés initiaux, non éditables.
            if (choisis.isEmpty()) ligneLecture(label, "—")
            racine.addView(liste); rafraichir()
            return { choisis.map { (id, nom) -> id to nom } }
        }
        // Même widget que Paramètres (ExposedDropdownMenu) : la liste des observateurs se déploie
        // au 1er tap ; on tape pour filtrer (insensible aux accents), on choisit pour ajouter.
        val til = champDropdown("Ajouter un observateur")
        til.findViewById<MaterialAutoCompleteTextView>(R.id.ac_champ_releve).apply {
            setAdapter(AdaptateurAutocomplete(ctx, options.map { it.second }))
            threshold = 1
            setOnClickListener { setText("", false); showDropDown() }
            setOnItemClickListener { _, _, pos, _ ->
                masquerClavier(this)
                val txt = (adapter.getItem(pos) as? String).orEmpty()
                options.firstOrNull { it.second == txt }?.let { choisis[it.first] = it.second }
                setText("", false)
                rafraichir()
            }
        }
        racine.addView(til)
        racine.addView(liste)
        rafraichir()
        return { choisis.map { (id, nom) -> id to nom } }
    }

    val getDataset = selecteur("Jeu de données", datasets, idDatasetInitial, nomDatasetInitial)
    val getObservateurs = selecteurMulti("Observateurs", observateurs, idsObservateursInitial, nomsObservateursInitial)

    // Infos lecture seule restantes (position, géométrie…).
    infos.forEach { (label, valeur) -> if (valeur.isNotBlank()) ligneLecture(label, valeur) }

    // ── Date du relevé (saisie a posteriori possible) ── pilotée serveur (input.date) :
    //    date de début toujours ; heure et/ou date de fin selon ce que le serveur active.
    val calDebut = java.util.Calendar.getInstance().apply {
        timeInMillis = dateDebutInitial ?: System.currentTimeMillis()
    }
    val calFin = java.util.Calendar.getInstance().apply {
        timeInMillis = dateFinInitial ?: calDebut.timeInMillis
    }
    val fmtDate = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE)
    val fmtHeure = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)
    fun champDateHeure(): TextView = TextView(ctx).apply {
        textSize = 15f
        setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
        isClickable = true
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 6 * density
            setStroke((1 * density).toInt(), couleurSecondaire(ctx))
        }
    }
    fun ligneDate(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, (8 * density).toInt())
    }
    fun choisirDate(cal: java.util.Calendar, onSet: () -> Unit) {
        android.app.DatePickerDialog(ctx, { _, y, m, d ->
            cal.set(java.util.Calendar.YEAR, y); cal.set(java.util.Calendar.MONTH, m)
            cal.set(java.util.Calendar.DAY_OF_MONTH, d); onSet()
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH))
            .apply { datePicker.maxDate = System.currentTimeMillis() } // pas de date future
            .show()
    }
    fun choisirHeure(cal: java.util.Calendar, onSet: () -> Unit) {
        android.app.TimePickerDialog(ctx, { _, h, min ->
            cal.set(java.util.Calendar.HOUR_OF_DAY, h); cal.set(java.util.Calendar.MINUTE, min); onSet()
        }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
    }
    fun ecart() = android.view.View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), 1)
    }
    // Date de fin repliée par défaut (comme le web : un « + » la révèle) ; dépliée d'emblée si on
    // édite un relevé qui porte déjà une fin distincte du début.
    var finActive = dateAvecFin && dateFinInitial != null && dateFinInitial != dateDebutInitial

    racine.addView(titre("Date du relevé"))
    val ligneDebut = ligneDate()
    ligneDebut.addView(champDateHeure().apply {
        text = fmtDate.format(calDebut.time)
        setOnClickListener { choisirDate(calDebut) { text = fmtDate.format(calDebut.time) } }
    })
    if (dateAvecHeures) {
        ligneDebut.addView(ecart())
        ligneDebut.addView(champDateHeure().apply {
            text = fmtHeure.format(calDebut.time)
            setOnClickListener { choisirHeure(calDebut) { text = fmtHeure.format(calDebut.time) } }
        })
    }

    // Bloc « date de fin » (titre + ligne), masqué tant qu'on ne l'a pas ajouté via « ＋ ».
    val titreFin = titre("Date de fin")
    val ligneFin = ligneDate()
    val champDateFin = champDateHeure().apply {
        text = fmtDate.format(calFin.time)
        setOnClickListener { choisirDate(calFin) { text = fmtDate.format(calFin.time) } }
    }
    val champHeureFin = if (dateAvecHeures) champDateHeure().apply {
        text = fmtHeure.format(calFin.time)
        setOnClickListener { choisirHeure(calFin) { text = fmtHeure.format(calFin.time) } }
    } else null
    ligneFin.addView(champDateFin)
    champHeureFin?.let { ligneFin.addView(ecart()); ligneFin.addView(it) }

    val btnAjouterFin = champDateHeure().apply { text = "＋" }
    val btnRetirerFin = champDateHeure().apply {
        text = "✕"; setTextColor(0xFFC62828.toInt()); background = null
    }
    fun majAffichageFin() {
        titreFin.visibility = if (finActive) android.view.View.VISIBLE else android.view.View.GONE
        ligneFin.visibility = if (finActive) android.view.View.VISIBLE else android.view.View.GONE
        btnAjouterFin.visibility =
            if (dateAvecFin && !finActive) android.view.View.VISIBLE else android.view.View.GONE
    }
    btnAjouterFin.setOnClickListener {
        calFin.timeInMillis = calDebut.timeInMillis // la fin démarre sur le début
        champDateFin.text = fmtDate.format(calFin.time)
        champHeureFin?.text = fmtHeure.format(calFin.time)
        finActive = true; majAffichageFin()
    }
    btnRetirerFin.setOnClickListener { finActive = false; majAffichageFin() }
    if (dateAvecFin) {
        ligneDebut.addView(ecart()); ligneDebut.addView(btnAjouterFin)
        ligneFin.addView(ecart()); ligneFin.addView(btnRetirerFin)
    }
    racine.addView(ligneDebut)
    racine.addView(titreFin)
    racine.addView(ligneFin)
    majAffichageFin()

    // Renvoie (début, fin) en millis. Sans heures : journée entière (00:00 → 23:59). Date de fin non
    // ajoutée ⇒ fin = début. La fin est bornée à ≥ début.
    fun getDates(): Pair<Long, Long> {
        if (!dateAvecHeures) {
            calDebut.set(java.util.Calendar.HOUR_OF_DAY, 0); calDebut.set(java.util.Calendar.MINUTE, 0)
            calDebut.set(java.util.Calendar.SECOND, 0)
            calFin.set(java.util.Calendar.HOUR_OF_DAY, 23); calFin.set(java.util.Calendar.MINUTE, 59)
            calFin.set(java.util.Calendar.SECOND, 0)
        }
        val debut = calDebut.timeInMillis
        return debut to (if (dateAvecFin && finActive) maxOf(calFin.timeInMillis, debut) else debut)
    }

    // Type de regroupement (TYP_GRP) — affiché seulement si le serveur ne le masque pas :
    //  - non masqué par les settings mobiles (champsAffichage), ET
    //  - non masqué par la config web du serveur (form_fields.group_type), pour suivre le web
    //    instance par instance (sur l'ANA, group_type=false ⇒ champ caché).
    val containerReleve = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val champsReleve = OcctaxFieldsConfig.champsAffichage(settingsJson, OcctaxFieldsConfig.Niveau.RELEVE)
    val typGrpVisible = champsReleve.isNotEmpty() && champFormVisible(formFieldsJson, "group_type")
    if (typGrpVisible) {
        OcctaxFieldsRenderer.rendre(containerReleve, champsReleve, mapOf("TYP_GRP" to typGrpInitial), emptySet(), "")
        racine.addView(containerReleve)
    }

    // Champs additionnels éditables — éventuels.
    val containerAdd = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    if (defs.isNotEmpty()) {
        racine.addView(titre("Champs additionnels"))
        AdditionalFieldsRenderer.rendre(containerAdd, defs, valeursInitiales)
        racine.addView(containerAdd)
    }

    // Visibilité des champs Détails pilotée par le serveur (form_fields), par instance. Le champ
    // est TOUJOURS construit (pour préserver sa valeur initiale dans le résultat) mais n'est ajouté
    // à l'écran que s'il est visible. true par défaut si le serveur ne publie pas la clé.
    val habitatVisible = champFormVisible(formFieldsJson, "habitat")
    val commentVisible = champFormVisible(formFieldsJson, "comment_releve")

    // Habitat (cd_hab) — recherche live sur le référentiel HABREF du serveur. Le serveur filtre
    // déjà : on neutralise le filtrage client de l'AutoCompleteTextView (sinon il re-masque les
    // suggestions dont le libellé ne « commence » pas par le terme tapé). Une frappe invalide la
    // sélection précédente ; seul un clic sur une suggestion (re)fixe le cd_hab.
    if (habitatVisible) racine.addView(titre("Habitat"))
    var cdHabChoisi: Int? = cdHabInitial
    val libellesHabitat = mutableListOf<String>()
    val cdParLibelle = HashMap<String, Int>()
    val adapterHabitat = object :
        ArrayAdapter<String>(ctx, android.R.layout.simple_dropdown_item_1line, libellesHabitat) {
        private val filtreNeutre = object : Filter() {
            override fun performFiltering(c: CharSequence?) = FilterResults().apply {
                values = libellesHabitat; count = libellesHabitat.size
            }
            override fun publishResults(c: CharSequence?, r: FilterResults?) = notifyDataSetChanged()
        }
        override fun getFilter(): Filter = filtreNeutre
    }
    val champHabitat = MaterialAutoCompleteTextView(ctx).apply {
        setAdapter(adapterHabitat)
        threshold = 0
        isSingleLine = true
        hint = "Rechercher un habitat…"
        setText(habitatLabelInitial.orEmpty(), false)
        setPadding(0, 0, 0, (8 * density).toInt())
    }
    var majHabitatProgrammatique = false
    var jobHabitat: Job? = null
    champHabitat.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (majHabitatProgrammatique) return
            cdHabChoisi = null // toute édition annule la sélection jusqu'à un nouveau choix
            val terme = s?.toString().orEmpty()
            jobHabitat?.cancel()
            if (terme.trim().length < 2) return
            jobHabitat = scope.launch {
                delay(300) // anti-rebond : on ne requête pas à chaque frappe
                val res = chercherHabitats(terme)
                libellesHabitat.clear(); cdParLibelle.clear()
                res.forEach { libellesHabitat.add(it.libelle); cdParLibelle[it.libelle] = it.cdHab }
                adapterHabitat.notifyDataSetChanged()
                if (libellesHabitat.isNotEmpty() && champHabitat.isFocused) champHabitat.showDropDown()
            }
        }
    })
    champHabitat.setOnItemClickListener { _, _, pos, _ ->
        val libelle = adapterHabitat.getItem(pos) ?: return@setOnItemClickListener
        masquerClavier(champHabitat)
        cdHabChoisi = cdParLibelle[libelle]
        majHabitatProgrammatique = true
        champHabitat.setText(libelle, false)
        majHabitatProgrammatique = false
    }
    // Clic = (re)déploie les suggestions. Le référentiel HABREF étant trop volumineux pour être
    // listé d'un bloc, on déploie les résultats déjà chargés, ou on relance la recherche sur le
    // texte courant (ex. habitat déjà sélectionné) — sans effacer la sélection.
    champHabitat.setOnClickListener {
        if (libellesHabitat.isNotEmpty()) { champHabitat.showDropDown(); return@setOnClickListener }
        val terme = champHabitat.text?.toString()?.trim().orEmpty()
        if (terme.length < 2) return@setOnClickListener
        jobHabitat?.cancel()
        jobHabitat = scope.launch {
            val res = chercherHabitats(terme)
            libellesHabitat.clear(); cdParLibelle.clear()
            res.forEach { libellesHabitat.add(it.libelle); cdParLibelle[it.libelle] = it.cdHab }
            adapterHabitat.notifyDataSetChanged()
            if (libellesHabitat.isNotEmpty()) champHabitat.showDropDown()
        }
    }
    if (habitatVisible) racine.addView(champHabitat)

    // Commentaire libre du relevé (→ properties.comment).
    val champCommentaire = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setText(commentInitial)
        isSingleLine = false
        minLines = 2
        setPadding(0, 0, 0, (8 * density).toInt())
    }
    if (commentVisible) {
        racine.addView(titre("Commentaire"))
        racine.addView(champCommentaire)
    }

    val scroll = ScrollView(ctx).apply { addView(racine) }
    // Bouton « Valider » posé après show() pour pouvoir BLOQUER la fermeture si un champ
    // additionnel obligatoire (required) visible est vide (sinon setPositiveButton ferme le dialog).
    val dialog = MaterialAlertDialogBuilder(ctx)
        .setTitle("Détails du relevé")
        .setView(scroll)
        .setPositiveButton("Valider", null)
        .setNegativeButton("Annuler", null)
        .create()
    dialog.setOnShowListener {
        racine.requestFocus() // évite l'ouverture auto d'un dropdown si un champ happait le focus
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val manquants = if (defs.isNotEmpty()) AdditionalFieldsRenderer.champsObligatoiresVides(containerAdd) else emptyList()
            if (manquants.isNotEmpty()) {
                android.widget.Toast.makeText(ctx,
                    "Champs obligatoires à renseigner : ${manquants.joinToString(", ")}",
                    android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val ds = getDataset()
            val obs = getObservateurs()
            val (dDebut, dFin) = getDates()
            onValider(
                DetailsReleveResult(
                    idDataset = ds?.first,
                    idsObservateurs = obs.map { it.first },
                    nomsObservateurs = obs.map { it.second },
                    additionnels = if (defs.isNotEmpty()) AdditionalFieldsRenderer.collecter(containerAdd) else valeursInitiales,
                    comment = champCommentaire.text?.toString()?.trim().orEmpty(),
                    cdHab = cdHabChoisi,
                    habitatLabel = cdHabChoisi?.let { champHabitat.text?.toString()?.trim() },
                    typGrp = if (typGrpVisible) (OcctaxFieldsRenderer.collecter(containerReleve)["TYP_GRP"] ?: "") else typGrpInitial,
                    dateDebut = dDebut,
                    dateFin = dFin,
                )
            )
            dialog.dismiss()
        }
    }
    dialog.show()
}
