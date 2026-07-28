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

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NomenclatureCache
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Dialogue « Détails de la station » OccHab, partagé par l'écran de création d'habitat
 * ([OccHabHabitatFragment]) et l'écran liste ([OccHabStationFragment]). Édite les détails
 * COMMUNS à la session ([OccHabViewModel.details]) : jeu de données, observateurs, dates,
 * altitudes, surface + méthode de calcul, nature de l'objet géographique, commentaire.
 * [onValide] est appelé après validation (rafraîchir résumé / état du bouton Enregistrer).
 */
fun ouvrirDialogDetailsOccHab(
    context: Context,
    vm: OccHabViewModel,
    config: GeoNatureConfig,
    /** true à la 1ʳᵉ station : le jeu de données est obligatoire (« Valider » bloqué sans lui,
     *  dialogue non annulable, bouton « Retour » → [onAnnule]). */
    jddObligatoire: Boolean = false,
    /** true = dialogue « Jeu de données » SEUL (démarrage de session) ; false = tous les champs. */
    jddSeul: Boolean = false,
    onAnnule: () -> Unit = {},
    onValide: () -> Unit,
) {
    val density = context.resources.displayMetrics.density
    val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
    val d = vm.details
    val racine = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * density).toInt(); setPadding(p, p, p, 0)
    }

    fun label(t: String) = TextView(context).apply {
        text = t
        setPadding(0, (12 * density).toInt(), 0, (2 * density).toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun champClic(labelTxt: String, valeurInit: String, onClic: (TextView) -> Unit): TextView {
        racine.addView(label(labelTxt))
        val tv = TextView(context).apply {
            text = valeurInit
            textSize = 15f
            setPadding((4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt())
            val bg = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
            setBackgroundResource(bg.resourceId)
            isClickable = true
        }
        tv.setOnClickListener { onClic(tv) }
        racine.addView(tv)
        return tv
    }

    fun champNombre(labelTxt: String, valeurInit: String?): EditText {
        racine.addView(label(labelTxt))
        val et = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(valeurInit.orEmpty())
        }
        racine.addView(et)
        return et
    }

    fun spinner(labelTxt: String, type: String, idCourant: Int?): () -> Int? {
        racine.addView(label(labelTxt))
        val (sp, lecteur) = construireSpinnerNomenclature(context, type, idCourant)
        racine.addView(sp)
        return lecteur
    }

    fun choisirDateHeure(cal: Calendar, onSet: () -> Unit) {
        DatePickerDialog(context, { _, y, m, day ->
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(context, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
                onSet()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ── État LOCAL des sélections : rien n'est écrit dans le ViewModel avant « Valider »
    //    (sinon « Annuler » laissait passer les modifications de JDD / observateurs / dates). ──
    var pendingIdDataset: Int? = d.idDataset
    var pendingNomDataset: String? = d.nomDataset
    var pendingDateMin: Long? = d.dateMin
    var pendingDateMax: Long? = d.dateMax
    // Getter des observateurs choisis (widget partagé, cf. plus bas). Par défaut : sélection
    // actuelle inchangée (mode « JDD seul » ne montre pas le champ).
    var lireObservateurs: () -> List<Pair<Int, String>> = {
        d.observateursIds.mapIndexed { i, id -> id to (d.observateursNoms.getOrNull(i) ?: id.toString()) }
    }

    // Jeu de données (autocomplétion).
    racine.addView(label("Jeu de données *"))
    val datasets = datasetsPourOccHab(config)
    if (datasets.isEmpty()) {
        racine.addView(TextView(context).apply {
            text = "Aucun jeu de données OccHab en cache — lancez « Recharger les données »."
            setTextColor(couleurErreur(context)); textSize = 13f
            setPadding(0, (6 * density).toInt(), 0, 0)
        })
    } else {
        val til = LayoutInflater.from(context)
            .inflate(R.layout.champ_dropdown_releve, racine, false) as TextInputLayout
        til.hint = "Rechercher un jeu de données"
        val champ = til.findViewById<MaterialAutoCompleteTextView>(R.id.ac_champ_releve)
        val labels = datasets.map { it.second }
        champ.setAdapter(AdaptateurAutocomplete(context, labels))
        champ.threshold = 1
        // Hauteur bornée (5 items, défilable) pour que la liste reste entièrement visible
        // au-dessus du clavier.
        val hauteurDropdown = (minOf(datasets.size, 5) * 48 * density).toInt()
        champ.dropDownHeight = hauteurDropdown
        if (jddSeul) {
            // Dialogue JDD seul (petit, centré) : le clavier masque le bas → dropdown AU-DESSUS.
            champ.post { champ.dropDownVerticalOffset = -(champ.height + hauteurDropdown) }
        }
        // Détails (champ en haut du formulaire) : ancrage par défaut = liste juste sous le bas
        // du champ (un offset supplémentaire la faisait descendre trop bas).
        var nomChoisi: String? = pendingNomDataset ?: datasets.firstOrNull { it.first == pendingIdDataset }?.second
        nomChoisi?.let { champ.setText(it, false) }
        champ.setOnClickListener { champ.setText("", false); champ.showDropDown() }
        champ.setOnItemClickListener { _, _, pos, _ ->
            val txt = (champ.adapter.getItem(pos) as? String).orEmpty()
            datasets.firstOrNull { it.second == txt }?.let { sel ->
                pendingIdDataset = sel.first
                pendingNomDataset = sel.second
                nomChoisi = sel.second
            }
            champ.setText(nomChoisi.orEmpty(), false)
            masquerClavier(champ)
        }
        champ.setOnDismissListener { champ.setText(nomChoisi.orEmpty(), false) }
        racine.addView(til)
    }

    // Autres champs (masqués en mode « JDD seul » du démarrage de session).
    var etAltMin: EditText? = null
    var etAltMax: EditText? = null
    var etSurface: EditText? = null
    var etComment: EditText? = null
    var lireMethode: () -> Int? = { null }
    var lireNature: () -> Int? = { null }
    if (!jddSeul) {
        // Observateurs : MÊME widget multi-sélection qu'Occtax (« Détails du relevé ») —
        // recherche + entrées retirables « ✕ ». Appliqué au ViewModel seulement à « Valider ».
        racine.addView(label("Observateurs"))
        lireObservateurs = construireSelecteurMultiObservateurs(
            context, racine, observateursPourDetailsReleve(config),
            d.observateursIds, d.observateursNoms,
        )

        // Dates début / fin.
        val calDebut = Calendar.getInstance().apply { timeInMillis = pendingDateMin ?: System.currentTimeMillis() }
        val calFin = Calendar.getInstance().apply { timeInMillis = pendingDateMax ?: calDebut.timeInMillis }
        champClic("Date de début", dateFmt.format(calDebut.time)) { tv ->
            choisirDateHeure(calDebut) {
                pendingDateMin = calDebut.timeInMillis
                tv.text = dateFmt.format(calDebut.time)
            }
        }
        champClic("Date de fin", pendingDateMax?.let { dateFmt.format(Date(it)) } ?: "—") { tv ->
            choisirDateHeure(calFin) {
                pendingDateMax = calFin.timeInMillis
                tv.text = dateFmt.format(calFin.time)
            }
        }

        // Altitudes / surface / nomenclatures / commentaire (lus à la validation).
        etAltMin = champNombre("Altitude min (m)", d.altitudeMin?.toString())
        etAltMax = champNombre("Altitude max (m)", d.altitudeMax?.toString())
        etSurface = champNombre("Surface (m²)", d.surface?.toString())
        lireMethode = spinner("Méthode de calcul de la surface", "METHOD_CALCUL_SURFACE", d.idNomCalculSurface)
        lireNature = spinner("Nature de l'objet géographique", "NAT_OBJ_GEO", d.idNomObjetGeographique)
        racine.addView(label("Commentaire"))
        etComment = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            setText(d.comment.orEmpty())
        }
        racine.addView(etComment)
    }

    val dlg = AlertDialog.Builder(context)
        .setTitle(if (jddSeul) "Jeu de données" else "Détails de la station")
        .setView(ScrollView(context).apply { addView(racine) })
        .setCancelable(!jddObligatoire)
        .setPositiveButton("Valider", null)
        .setNegativeButton(if (jddObligatoire) "Retour" else "Annuler") { _, _ ->
            if (jddObligatoire) onAnnule()
        }
        .create()
    dlg.setOnShowListener {
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (jddObligatoire && pendingIdDataset == null) {
                Toast.makeText(context, "Choisissez un jeu de données", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Application ATOMIQUE des sélections : rien avant, tout ici (Annuler = sans effet).
            vm.majDetails {
                it.idDataset = pendingIdDataset
                it.nomDataset = pendingNomDataset
            }
            if (!jddSeul) {
                // Garde serveur : date_max ≥ date_min (sinon payload invalide côté GeoNature).
                val fin = pendingDateMax?.let { f -> maxOf(f, pendingDateMin ?: f) }
                val obs = lireObservateurs()
                vm.majDetails {
                    it.observateursIds = obs.map { o -> o.first }
                    it.observateursNoms = obs.map { o -> o.second }
                    it.dateMin = pendingDateMin
                    it.dateMax = fin
                    it.altitudeMin = etAltMin?.text?.toString()?.trim()?.toIntOrNull()
                    it.altitudeMax = etAltMax?.text?.toString()?.trim()?.toIntOrNull()
                    it.surface = etSurface?.text?.toString()?.trim()?.toLongOrNull()
                    it.idNomCalculSurface = lireMethode()
                    it.idNomObjetGeographique = lireNature()
                    it.comment = etComment?.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() }
                }
            }
            onValide()
            dlg.dismiss()
        }
    }
    dlg.show()
}

/** Spinner de nomenclature « — Non renseigné — » + valeurs du cache [NomenclatureCache].
 *  Renvoie le spinner et un lecteur de l'id_nomenclature choisi (null si non renseigné).
 *  Partagé par le dialogue Détails et l'écran de création d'habitat.
 *  Rendu ALIGNÉ sur les spinners de nomenclature Occtax (OcctaxFieldsRenderer) : item fermé
 *  `simple_spinner_item` + liste `simple_spinner_dropdown_item`, valeurs triées alphabétiquement
 *  (français, insensible casse/accents), placeholder en tête. */
internal fun construireSpinnerNomenclature(
    context: Context, type: String, idCourant: Int?,
): Pair<Spinner, () -> Int?> {
    val collator = java.text.Collator.getInstance(Locale.FRENCH).apply {
        strength = java.text.Collator.PRIMARY
    }
    val valeurs = NomenclatureCache.get(type).sortedWith(compareBy(collator) { it.label })
    val labels = mutableListOf("— Non renseigné —").apply { valeurs.forEach { add(it.label) } }
    val spinner = Spinner(context)
    val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
    val idx = valeurs.indexOfFirst { it.id == idCourant }
    if (idx >= 0) spinner.setSelection(idx + 1)
    return spinner to {
        val pos = spinner.selectedItemPosition
        if (pos <= 0) null else valeurs.getOrNull(pos - 1)?.id
    }
}
