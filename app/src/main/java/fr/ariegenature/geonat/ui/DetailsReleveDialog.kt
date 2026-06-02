/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geonat.network.AdditionalFieldDef
import fr.ariegenature.geonat.network.GeoNatureDataset
import fr.ariegenature.geonat.network.GeoNatureObservateur
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.ui.saisie.AdditionalFieldsRenderer

private val gsonDetailsReleve = Gson()

/** Jeux de données proposables dans « Détails du relevé » : actifs + rattachés OCCTAX (ou sans
 *  module déclaré), lus depuis le cache local. Couples (id, "nom (id)") pour le dropdown. */
fun datasetsPourDetailsReleve(config: GeoNatureConfig): List<Pair<Int, String>> =
    try {
        val t = object : TypeToken<List<GeoNatureDataset>>() {}.type
        val l: List<GeoNatureDataset> = gsonDetailsReleve.fromJson(config.datasetsCacheJson, t) ?: emptyList()
        l.filter { it.actif && (it.moduleCodes.isEmpty() || "OCCTAX" in it.moduleCodes) }
            .map { it.id to "${it.nom} (${it.id})" }
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
    val idObservateur: Int?,
    val nomObservateur: String?,
    val additionnels: Map<String, String>,
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
    idObservateurInitial: Int?,
    nomObservateurInitial: String?,
    defs: List<AdditionalFieldDef>,
    valeursInitiales: Map<String, String>,
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
            setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, labels))
            threshold = 1
            isSingleLine = true
            // Clic = ré-ouvre la liste complète pour changer facilement.
            setOnClickListener { showDropDown() }
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

    val getDataset = selecteur("Jeu de données", datasets, idDatasetInitial, nomDatasetInitial)
    val getObservateur = selecteur("Observateur", observateurs, idObservateurInitial, nomObservateurInitial)

    // Infos lecture seule restantes (position, géométrie…).
    infos.forEach { (label, valeur) -> if (valeur.isNotBlank()) ligneLecture(label, valeur) }

    // Champs additionnels éditables — éventuels.
    val containerAdd = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    if (defs.isNotEmpty()) {
        racine.addView(titre("Champs additionnels"))
        AdditionalFieldsRenderer.rendre(containerAdd, defs, valeursInitiales)
        racine.addView(containerAdd)
    }

    val scroll = ScrollView(ctx).apply { addView(racine) }
    MaterialAlertDialogBuilder(ctx)
        .setTitle("Détails du relevé")
        .setView(scroll)
        .setPositiveButton("Valider") { _, _ ->
            val ds = getDataset()
            val obs = getObservateur()
            onValider(
                DetailsReleveResult(
                    idDataset = ds?.first,
                    idObservateur = obs?.first,
                    nomObservateur = obs?.second,
                    additionnels = if (defs.isNotEmpty()) AdditionalFieldsRenderer.collecter(containerAdd) else valeursInitiales,
                )
            )
        }
        .setNegativeButton("Annuler", null)
        .show()
}
