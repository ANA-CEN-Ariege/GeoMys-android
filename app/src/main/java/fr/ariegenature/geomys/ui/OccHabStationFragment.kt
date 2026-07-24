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
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentOcchabStationBinding
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.network.HabitatService
import fr.ariegenature.geomys.network.HabitatSuggestion
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.OccHabStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Formulaire d'une station OccHab : jeu de données, observateurs, dates début/fin, altitudes,
 * surface + méthode de calcul, nature de l'objet géographique, commentaire, et liste d'habitats.
 * Enregistre en local ([OccHabStore]) puis renvoie vers « Mes stations » d'où se fait l'envoi.
 */
class OccHabStationFragment : Fragment() {
    private var _binding: FragmentOcchabStationBinding? = null
    private val binding get() = _binding!!
    private val occhabViewModel: OccHabViewModel by activityViewModels()
    private lateinit var occHabStore: OccHabStore
    private lateinit var gnConfig: GeoNatureConfig

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

    // Références aux champs lus à l'enregistrement (les autres écrivent directement le ViewModel).
    private var etAltMin: EditText? = null
    private var etAltMax: EditText? = null
    private var etSurface: EditText? = null
    private var lireMethodeSurface: (() -> Int?)? = null
    private var lireNatureObjet: (() -> Int?)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOcchabStationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.racine.applySystemBarInsets()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "OccHab")
        occHabStore = OccHabStore(requireContext())
        gnConfig = GeoNatureConfig(requireContext())

        appliquerDefauts()
        afficherGeometrie()
        construireChampsStation()
        binding.etCommentaire.setText(occhabViewModel.station.comment.orEmpty())
        binding.btnAjouterHabitat.setOnClickListener { ouvrirDialogHabitat(null) }
        binding.btnEnregistrer.setOnClickListener { enregistrer() }
        rafraichirHabitats()
    }

    /** Pré-remplit jeu de données et observateurs avec les défauts de config si non renseignés. */
    private fun appliquerDefauts() {
        val s = occhabViewModel.station
        if (s.idDataset == null) {
            // On ne propose PAS le JDD OCCTAX de la config s'il n'est pas un JDD OccHab. Défaut :
            // le JDD OCCTAX seulement s'il figure dans la liste OccHab, sinon l'unique JDD OccHab
            // s'il n'y en a qu'un, sinon rien (l'utilisateur choisit).
            val occhab = datasetsPourOccHab(gnConfig)
            val cfg = gnConfig.idDataset.trim().toIntOrNull()?.takeIf { it > 0 }
            val defaut = when {
                cfg != null && occhab.any { it.first == cfg } -> cfg
                occhab.size == 1 -> occhab.first().first
                else -> null
            }
            if (defaut != null) occhabViewModel.maj { it.copy(idDataset = defaut) }
        }
        if (occhabViewModel.station.observateursIds.isEmpty()) {
            val id = gnConfig.observateurDefautId.trim().toIntOrNull()?.takeIf { it > 0 }
                ?: gnConfig.idRoleUtilisateur.takeIf { it > 0 }
            val nom = gnConfig.observateurDefautNom.ifBlank { gnConfig.nomUtilisateur }
            if (id != null) occhabViewModel.maj {
                it.copy(observateursIds = listOf(id), observateursNoms = listOfNotNull(nom.ifBlank { null }))
            }
        }
    }

    private fun afficherGeometrie() {
        val s = occhabViewModel.station
        binding.tvGeometrie.text = when (s.geometryType) {
            "Polygon" -> {
                val n = try { org.json.JSONArray(s.geometryCoordsJson).length() } catch (_: Exception) { 0 }
                "Géométrie : polygone ($n sommets)"
            }
            else -> "Géométrie : point (%.5f, %.5f)".format(s.latitude, s.longitude)
        }
    }

    // ── Construction des champs de la station ──────────────────────────────────────────────
    private fun construireChampsStation() {
        val c = binding.stationFieldsContainer
        c.removeAllViews()
        val datasets = datasetsPourOccHab(gnConfig)
        val observateurs = observateursPourDetailsReleve(gnConfig)

        // Jeu de données (obligatoire) — champ à autocomplétion identique à Occtax.
        ajouterChampDataset(c, datasets)

        // Observateurs (multi-sélection).
        ajouterChampClic(c, "Observateurs", libelleObservateurs()) { valueTv ->
            if (observateurs.isEmpty()) { toast("Aucun observateur en cache — synchronisez"); return@ajouterChampClic }
            val ids = observateurs.map { it.first }
            val labels = observateurs.map { it.second }.toTypedArray()
            val coches = BooleanArray(ids.size) { occhabViewModel.station.observateursIds.contains(ids[it]) }
            AlertDialog.Builder(requireContext())
                .setTitle("Observateurs")
                .setMultiChoiceItems(labels, coches) { _, which, isChecked -> coches[which] = isChecked }
                .setPositiveButton("OK") { _, _ ->
                    val selIds = ids.filterIndexed { i, _ -> coches[i] }
                    val selNoms = observateurs.filterIndexed { i, _ -> coches[i] }.map { it.second }
                    occhabViewModel.maj { it.copy(observateursIds = selIds, observateursNoms = selNoms) }
                    valueTv.text = libelleObservateurs()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        // Dates début / fin.
        val calDebut = Calendar.getInstance().apply {
            timeInMillis = occhabViewModel.station.dateMin ?: occhabViewModel.station.date
        }
        val calFin = Calendar.getInstance().apply {
            timeInMillis = occhabViewModel.station.dateMax ?: calDebut.timeInMillis
        }
        ajouterChampClic(c, "Date de début", dateFmt.format(calDebut.time)) { valueTv ->
            choisirDateHeure(calDebut) {
                occhabViewModel.maj { it.copy(dateMin = calDebut.timeInMillis) }
                valueTv.text = dateFmt.format(calDebut.time)
            }
        }
        ajouterChampClic(c, "Date de fin", occhabViewModel.station.dateMax?.let { dateFmt.format(Date(it)) } ?: "—") { valueTv ->
            choisirDateHeure(calFin) {
                occhabViewModel.maj { it.copy(dateMax = calFin.timeInMillis) }
                valueTv.text = dateFmt.format(calFin.time)
            }
        }

        // Altitudes min/max + surface (numériques).
        etAltMin = ajouterChampNombre(c, "Altitude min (m)", occhabViewModel.station.altitudeMin?.toString(), decimal = false)
        etAltMax = ajouterChampNombre(c, "Altitude max (m)", occhabViewModel.station.altitudeMax?.toString(), decimal = false)
        etSurface = ajouterChampNombre(c, "Surface (m²)", occhabViewModel.station.surface?.toString(), decimal = false)

        // Nomenclatures station.
        lireMethodeSurface = ajouterSpinner(c, "Méthode de calcul de la surface",
            "METHOD_CALCUL_SURFACE", occhabViewModel.station.idNomCalculSurface)
        lireNatureObjet = ajouterSpinner(c, "Nature de l'objet géographique",
            "NAT_OBJ_GEO", occhabViewModel.station.idNomObjetGeographique)
    }

    /** Champ « jeu de données » à autocomplétion (widget ExposedDropdownMenu identique à Occtax :
     *  clic = déploie toute la liste, filtre insensible aux accents/casse par *contains*). */
    private fun ajouterChampDataset(container: LinearLayout, datasets: List<Pair<Int, String>>) {
        ajouterLabel(container, "Jeu de données *")
        if (datasets.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "Aucun jeu de données OccHab en cache — lancez « Recharger les données »."
                setTextColor(0xFFC62828.toInt()); textSize = 13f
                setPadding(0, (6 * density).toInt(), 0, 0)
            })
            return
        }
        val til = LayoutInflater.from(requireContext())
            .inflate(R.layout.champ_dropdown_releve, container, false) as TextInputLayout
        til.hint = "Rechercher un jeu de données"
        val champ = til.findViewById<MaterialAutoCompleteTextView>(R.id.ac_champ_releve)
        val labels = datasets.map { it.second }
        champ.setAdapter(AdaptateurAutocomplete(requireContext(), labels))
        champ.threshold = 1
        var nomChoisi: String? = datasets.firstOrNull { it.first == occhabViewModel.station.idDataset }?.second
        nomChoisi?.let { champ.setText(it, false) }
        // Clic = vide le champ puis déploie toute la liste (comme Occtax/Paramètres).
        champ.setOnClickListener { champ.setText("", false); champ.showDropDown() }
        champ.setOnItemClickListener { _, _, pos, _ ->
            val txt = (champ.adapter.getItem(pos) as? String).orEmpty()
            datasets.firstOrNull { it.second == txt }?.let { sel ->
                occhabViewModel.maj { st -> st.copy(idDataset = sel.first) }
                nomChoisi = sel.second
            }
            // onDismiss peut courir AVANT onItemClick → on réaffiche EXPLICITEMENT la sélection.
            champ.setText(nomChoisi.orEmpty(), false)
        }
        champ.setOnDismissListener { champ.setText(nomChoisi.orEmpty(), false) }
        container.addView(til)
    }

    private fun libelleObservateurs(): String =
        occhabViewModel.station.observateursNoms.joinToString(", ")
            .ifBlank { "(utilisateur connecté)" }

    private fun choisirDateHeure(cal: Calendar, onSet: () -> Unit) {
        DatePickerDialog(requireContext(), { _, y, m, d ->
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
            TimePickerDialog(requireContext(), { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
                onSet()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ── Helpers de construction de champs ──────────────────────────────────────────────────
    private val density get() = resources.displayMetrics.density

    private fun ajouterLabel(container: LinearLayout, t: String) {
        container.addView(TextView(requireContext()).apply {
            text = t
            setPadding(0, (12 * density).toInt(), 0, (2 * density).toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    /** Champ « cliquable » (valeur affichée + clic ouvrant un sélecteur). Renvoie le TextView
     *  de valeur pour que l'appelant le mette à jour après sélection. */
    private fun ajouterChampClic(
        container: LinearLayout, label: String, valeurInit: String, onClic: (TextView) -> Unit,
    ): TextView {
        ajouterLabel(container, label)
        val tv = TextView(requireContext()).apply {
            text = valeurInit
            textSize = 15f
            setPadding((4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt())
            val bg = android.util.TypedValue()
            requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
            setBackgroundResource(bg.resourceId)
            isClickable = true
        }
        tv.setOnClickListener { onClic(tv) }
        container.addView(tv)
        return tv
    }

    private fun ajouterChampNombre(
        container: LinearLayout, label: String, valeurInit: String?, decimal: Boolean,
    ): EditText {
        ajouterLabel(container, label)
        val et = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            setText(valeurInit.orEmpty())
        }
        container.addView(et)
        return et
    }

    private fun ajouterSpinner(
        container: LinearLayout, label: String, type: String, idCourant: Int?,
    ): () -> Int? {
        ajouterLabel(container, label)
        val (spinner, lecteur) = construireSpinner(type, idCourant)
        container.addView(spinner)
        return lecteur
    }

    private fun rafraichirHabitats() {
        val container = binding.habitatsContainer
        container.removeAllViews()
        val habitats = occhabViewModel.station.habitats
        binding.tvAucunHabitat.visibility = if (habitats.isEmpty()) View.VISIBLE else View.GONE
        habitats.forEach { h ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                val bg = android.util.TypedValue()
                requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
                setBackgroundResource(bg.resourceId)
            }
            val txt = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val recouvr = h.recouvrement?.let { " · ${it.toInt()} %" } ?: ""
                text = (h.habitatLabel.ifBlank { h.nomCite }.ifBlank { "Habitat ${h.cdHab}" }) + recouvr
                textSize = 14f
            }
            val suppr = ImageButton(requireContext()).apply {
                setImageResource(R.drawable.ic_delete)
                background = null
                contentDescription = "Supprimer l'habitat"
                setColorFilter(0xFFC62828.toInt())
                setOnClickListener {
                    occhabViewModel.supprimerHabitat(h.id)
                    rafraichirHabitats()
                }
            }
            row.setOnClickListener { ouvrirDialogHabitat(h) }
            row.addView(txt)
            row.addView(suppr)
            container.addView(row)
        }
    }

    // ── Dialogue d'ajout / édition d'un habitat ────────────────────────────────────────────
    private fun ouvrirDialogHabitat(existant: OccHabHabitat?) {
        val ctx = requireContext()
        val pad = (16 * density).toInt()
        val racine = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun label(t: String) = TextView(ctx).apply {
            text = t
            setPadding(0, (10 * density).toInt(), 0, 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        var cdHabChoisi: Int? = existant?.cdHab?.takeIf { it > 0 }
        val libelles = ArrayList<String>()
        val cdParLibelle = HashMap<String, Int>()
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_dropdown_item_1line, libelles) {
            private val neutre = object : Filter() {
                override fun performFiltering(c: CharSequence?) = FilterResults().apply {
                    values = libelles; count = libelles.size
                }
                override fun publishResults(c: CharSequence?, r: FilterResults?) = notifyDataSetChanged()
            }
            override fun getFilter(): Filter = neutre
        }
        val champHab = MaterialAutoCompleteTextView(ctx).apply {
            setAdapter(adapter); threshold = 0; isSingleLine = true
            hint = "Rechercher un habitat (HABREF)…"
            setText(existant?.habitatLabel.orEmpty(), false)
        }
        var majProg = false
        var job: Job? = null
        champHab.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (majProg) return
                cdHabChoisi = null
                val terme = s?.toString().orEmpty()
                job?.cancel()
                if (terme.trim().length < 2) return
                job = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    val res = chercherHabitats(terme)
                    libelles.clear(); cdParLibelle.clear()
                    res.forEach { libelles.add(it.libelle); cdParLibelle[it.libelle] = it.cdHab }
                    adapter.notifyDataSetChanged()
                    if (libelles.isNotEmpty() && champHab.isFocused) champHab.showDropDown()
                }
            }
        })
        champHab.setOnItemClickListener { _, _, pos, _ ->
            val libelle = adapter.getItem(pos) ?: return@setOnItemClickListener
            cdHabChoisi = cdParLibelle[libelle]
            majProg = true
            champHab.setText(libelle, false)
            majProg = false
        }

        val champNom = EditText(ctx).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(existant?.nomCite.orEmpty())
            hint = "Nom cité (défaut = libellé HABREF)"
        }
        val champRecouvr = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existant?.recouvrement?.let {
                if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
            }.orEmpty())
            hint = "Recouvrement en %"
        }
        val (spTech, lireTech) = construireSpinner("TECHNIQUE_COLLECT_HAB", existant?.idNomTechniqueCollecte)
        val (spAbon, lireAbon) = construireSpinner("ABONDANCE_HAB", existant?.idNomAbondance)

        racine.addView(label("Habitat (HABREF) *")); racine.addView(champHab)
        racine.addView(label("Nom cité")); racine.addView(champNom)
        racine.addView(label("Recouvrement (%)")); racine.addView(champRecouvr)
        racine.addView(label("Technique de collecte")); racine.addView(spTech)
        racine.addView(label("Abondance")); racine.addView(spAbon)

        val dlg = AlertDialog.Builder(ctx)
            .setTitle(if (existant == null) "Ajouter un habitat" else "Modifier l'habitat")
            .setView(ScrollView(ctx).apply { addView(racine) })
            .setPositiveButton("OK", null)
            .setNegativeButton("Annuler", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cd = cdHabChoisi
                if (cd == null || cd <= 0) {
                    toast("Choisissez un habitat dans la liste HABREF")
                    return@setOnClickListener
                }
                val habitat = (existant ?: OccHabHabitat()).copy(
                    cdHab = cd,
                    habitatLabel = champHab.text?.toString()?.trim().orEmpty(),
                    nomCite = champNom.text?.toString()?.trim().orEmpty(),
                    recouvrement = champRecouvr.text?.toString()?.trim()?.toDoubleOrNull(),
                    idNomTechniqueCollecte = lireTech(),
                    idNomAbondance = lireAbon(),
                )
                occhabViewModel.ajouterOuMajHabitat(habitat)
                rafraichirHabitats()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun construireSpinner(type: String, idCourant: Int?): Pair<Spinner, () -> Int?> {
        val valeurs = NomenclatureCache.get(type)
        val labels = mutableListOf("— Non renseigné —").apply { valeurs.forEach { add(it.label) } }
        val spinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        }
        val idx = valeurs.indexOfFirst { it.id == idCourant }
        if (idx >= 0) spinner.setSelection(idx + 1)
        val lecteur: () -> Int? = {
            val pos = spinner.selectedItemPosition
            if (pos <= 0) null else valeurs.getOrNull(pos - 1)?.id
        }
        return spinner to lecteur
    }

    /** Recherche HABREF restreinte à la liste du module OccHab (`OCCHAB.ID_LIST_HABITAT`), comme
     *  le formulaire web — donc les mêmes valeurs que le serveur, et pas tout le référentiel. */
    private suspend fun chercherHabitats(terme: String): List<HabitatSuggestion> {
        val base = gnConfig.urlServeur.trim().trimEnd('/')
        val idList = gnConfig.occhabIdListHabitat.takeIf { it > 0 }
        return HabitatService.rechercher(base, terme, 25, idList)
    }

    // ── Enregistrement ─────────────────────────────────────────────────────────────────────
    private fun enregistrer() {
        occhabViewModel.maj {
            it.copy(
                comment = binding.etCommentaire.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() },
                altitudeMin = etAltMin?.text?.toString()?.trim()?.toIntOrNull(),
                altitudeMax = etAltMax?.text?.toString()?.trim()?.toIntOrNull(),
                surface = etSurface?.text?.toString()?.trim()?.toLongOrNull(),
                idNomCalculSurface = lireMethodeSurface?.invoke(),
                idNomObjetGeographique = lireNatureObjet?.invoke(),
            )
        }
        val station = occhabViewModel.station
        if (station.idDataset == null) {
            toast("Choisissez un jeu de données")
            return
        }
        occHabStore.remplacer(station.id, station)
        toast("Station enregistrée")
        findNavController().naviguerSur(R.id.action_occhab_station_to_stations)
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
