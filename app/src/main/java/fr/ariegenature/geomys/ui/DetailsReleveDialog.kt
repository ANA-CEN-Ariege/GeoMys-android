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
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geomys.network.AdditionalFieldDef
import fr.ariegenature.geomys.network.GeoNatureDataset
import fr.ariegenature.geomys.network.GeoNatureObservateur
import fr.ariegenature.geomys.network.HabitatSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import fr.ariegenature.geomys.store.GeoNatureConfig
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
    typGrpInitial: String,
    commentInitial: String,
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
        val champ = AutoCompleteTextView(ctx).apply {
            val ad = AdaptateurAutocomplete(ctx, labels)
            setAdapter(ad)
            threshold = 1
            isSingleLine = true
            // Clic / prise de focus = déploie TOUTE la liste (contrainte vide), façon spinner —
            // sans quoi le filtre la réduirait à la valeur déjà affichée.
            setOnClickListener { ad.filter.filter(null) { showDropDown() } }
            setOnFocusChangeListener { _, aFocus -> if (aFocus) ad.filter.filter(null) { showDropDown() } }
            val idx = options.indexOfFirst { it.first == idInitial }
            // Affiche la sélection initiale même si elle n'est pas (ou plus) dans les options.
            if (idx >= 0) setText(labels[idx], false)
            else labelInitial?.let { setText(it, false) }
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        racine.addView(champ)
        return {
            val txt = champ.text?.toString().orEmpty()
            // Match exact d'un libellé d'option ; sinon on garde la sélection initiale.
            options.firstOrNull { it.second == txt } ?: initialOuNull()
        }
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
        val recherche = AutoCompleteTextView(ctx).apply {
            val ad = AdaptateurAutocomplete(ctx, options.map { it.second })
            setAdapter(ad)
            threshold = 1
            isSingleLine = true
            hint = "Ajouter un observateur…"
            // Clic / focus = déploie toute la liste des observateurs (insensible aux accents en frappe).
            setOnClickListener { ad.filter.filter(null) { showDropDown() } }
            setOnFocusChangeListener { _, aFocus -> if (aFocus) ad.filter.filter(null) { showDropDown() } }
            setOnItemClickListener { _, _, pos, _ ->
                val txt = (adapter.getItem(pos) as? String).orEmpty()
                options.firstOrNull { it.second == txt }?.let { choisis[it.first] = it.second }
                setText("", false)
                rafraichir()
            }
            // Même respiration que le champ Habitat (8dp), pour ne pas coller la liste en dessous.
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        racine.addView(recherche)
        racine.addView(liste)
        rafraichir()
        return { choisis.map { (id, nom) -> id to nom } }
    }

    val getDataset = selecteur("Jeu de données", datasets, idDatasetInitial, nomDatasetInitial)
    val getObservateurs = selecteurMulti("Observateurs", observateurs, idsObservateursInitial, nomsObservateursInitial)

    // Infos lecture seule restantes (position, géométrie…).
    infos.forEach { (label, valeur) -> if (valeur.isNotBlank()) ligneLecture(label, valeur) }

    // Nomenclature niveau relevé (TYP_GRP) — affichée si le serveur ne la masque pas.
    val containerReleve = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val champsReleve = OcctaxFieldsConfig.champsAffichage(settingsJson, OcctaxFieldsConfig.Niveau.RELEVE)
    if (champsReleve.isNotEmpty()) {
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

    // Habitat (cd_hab) — recherche live sur le référentiel HABREF du serveur. Le serveur filtre
    // déjà : on neutralise le filtrage client de l'AutoCompleteTextView (sinon il re-masque les
    // suggestions dont le libellé ne « commence » pas par le terme tapé). Une frappe invalide la
    // sélection précédente ; seul un clic sur une suggestion (re)fixe le cd_hab.
    racine.addView(titre("Habitat"))
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
    val champHabitat = AutoCompleteTextView(ctx).apply {
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
    racine.addView(champHabitat)

    // Commentaire libre du relevé (→ properties.comment).
    racine.addView(titre("Commentaire"))
    val champCommentaire = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setText(commentInitial)
        isSingleLine = false
        minLines = 2
        setPadding(0, 0, 0, (8 * density).toInt())
    }
    racine.addView(champCommentaire)

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
            onValider(
                DetailsReleveResult(
                    idDataset = ds?.first,
                    idsObservateurs = obs.map { it.first },
                    nomsObservateurs = obs.map { it.second },
                    additionnels = if (defs.isNotEmpty()) AdditionalFieldsRenderer.collecter(containerAdd) else valeursInitiales,
                    comment = champCommentaire.text?.toString()?.trim().orEmpty(),
                    cdHab = cdHabChoisi,
                    habitatLabel = cdHabChoisi?.let { champHabitat.text?.toString()?.trim() },
                    typGrp = if (champsReleve.isNotEmpty()) (OcctaxFieldsRenderer.collecter(containerReleve)["TYP_GRP"] ?: "") else typGrpInitial,
                )
            )
            dialog.dismiss()
        }
    }
    dialog.show()
}
