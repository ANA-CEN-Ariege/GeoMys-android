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

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentOcchabHabitatBinding
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.network.HabitatService
import fr.ariegenature.geomys.network.HabitatSuggestion
import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Écran de création / édition d'UN habitat OccHab (champs pilotés par `OCCHAB.formConfig`).
 * Affiché directement après la carte (et après la saisie du jeu de données à la 1ʳᵉ station).
 * « Valider » ajoute l'habitat à la station et renvoie sur la liste des habitats.
 */
class OccHabHabitatFragment : Fragment() {
    private var _binding: FragmentOcchabHabitatBinding? = null
    private val binding get() = _binding!!
    private val occhabViewModel: OccHabViewModel by activityViewModels()
    private lateinit var gnConfig: GeoNatureConfig

    private var existant: OccHabHabitat? = null
    private var cdHabChoisi: Int? = null
    private var champHab: MaterialAutoCompleteTextView? = null
    private var champDeterminateur: EditText? = null
    private var champPrecision: EditText? = null
    private var champRecouvr: EditText? = null
    private var lireInteret: () -> Int? = { null }
    private var lireTypeDeterm: () -> Int? = { null }
    private var lireTech: () -> Int? = { null }
    private var lireAbon: () -> Int? = { null }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOcchabHabitatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.racine.applySystemBarInsets()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "OccHab")
        gnConfig = GeoNatureConfig(requireContext())

        val habitatId = arguments?.getString("habitatId")
        existant = habitatId?.let { id -> occhabViewModel.station.habitats.firstOrNull { it.id == id } }

        construireFormulaire()
        binding.btnValider.setOnClickListener { valider() }

        // Démarrage de session (1ʳᵉ station) : dialogue « Jeu de données » SEUL (obligatoire).
        // Les autres détails partent des défauts serveur et s'éditent via « Détails » (écran liste).
        // « Retour » ramène à la carte.
        if (!occhabViewModel.jddDefini) binding.root.post {
            if (isAdded) ouvrirDialogDetailsOccHab(
                requireContext(), occhabViewModel, gnConfig,
                jddObligatoire = true, jddSeul = true,
                onAnnule = { findNavController().navigateUp() },
            ) {}
        }
    }

    private val density get() = resources.displayMetrics.density

    private fun label(t: String) = TextView(requireContext()).apply {
        text = t
        setPadding(0, (12 * density).toInt(), 0, (2 * density).toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun construireFormulaire() {
        val ctx = requireContext()
        val racine = binding.fieldsContainer
        val ex = existant

        // Autocomplétion HABREF (cache dédié OccHab, sensible aux accents comme le web).
        cdHabChoisi = ex?.cdHab?.takeIf { it > 0 }
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
        val champ = MaterialAutoCompleteTextView(ctx).apply {
            setAdapter(adapter); threshold = 0; isSingleLine = true
            hint = "Rechercher un habitat (HABREF)…"
            setText(ex?.habitatLabel.orEmpty(), false)
        }
        champHab = champ
        var majProg = false
        var job: Job? = null
        champ.addTextChangedListener(object : TextWatcher {
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
                    if (libelles.isNotEmpty() && champ.isFocused) champ.showDropDown()
                }
            }
        })
        champ.setOnItemClickListener { _, _, pos, _ ->
            val libelle = adapter.getItem(pos) ?: return@setOnItemClickListener
            cdHabChoisi = cdParLibelle[libelle]
            majProg = true
            champ.setText(libelle, false)
            majProg = false
            masquerClavier(champ)
        }
        // Re-clic sur un habitat déjà sélectionné : on vide le champ (le TextWatcher annule la
        // sélection) pour pouvoir chercher une autre valeur — même geste que le champ JDD.
        champ.setOnClickListener {
            if (cdHabChoisi != null) champ.setText("", false)
        }

        val estNouveau = ex == null
        fun idInit(valExistante: Int?, mnem: String): Int? =
            valExistante ?: if (estNouveau) gnConfig.occhabDefautNomenclature(mnem) else null
        fun visible(champC: String) = gnConfig.occhabChampVisible(champC)

        racine.addView(label("Habitat (HABREF) *")); racine.addView(champ)

        lireInteret = if (visible("community_interest")) {
            val (sp, lire) = construireSpinner("HAB_INTERET_COM", idInit(ex?.idNomInteretCommunautaire, "HAB_INTERET_COM"))
            racine.addView(label("Habitat d'intérêt communautaire")); racine.addView(sp); lire
        } else { { ex?.idNomInteretCommunautaire } }

        champDeterminateur = if (visible("determiner")) {
            val et = EditText(ctx).apply {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setText(ex?.determiner.orEmpty()); hint = "Déterminateur"
            }
            racine.addView(label("Déterminateur")); racine.addView(et); et
        } else null

        lireTypeDeterm = if (visible("determination_type")) {
            val (sp, lire) = construireSpinner("DETERMINATION_TYP_HAB", idInit(ex?.idNomTypeDetermination, "DETERMINATION_TYP_HAB"))
            racine.addView(label("Type de détermination")); racine.addView(sp); lire
        } else { { ex?.idNomTypeDetermination } }

        lireTech = if (visible("collection_technique")) {
            val (sp, lire) = construireSpinner("TECHNIQUE_COLLECT_HAB", idInit(ex?.idNomTechniqueCollecte, "TECHNIQUE_COLLECT_HAB"))
            racine.addView(label("Technique de collecte")); racine.addView(sp); lire
        } else { { ex?.idNomTechniqueCollecte } }

        champPrecision = if (visible("technical_precision")) {
            val et = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(ex?.precisionTechnique.orEmpty()); hint = "Précision technique"
            }
            racine.addView(label("Précision technique")); racine.addView(et); et
        } else null

        champRecouvr = if (visible("recovery_percentage")) {
            val et = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(ex?.recouvrement?.let {
                    if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
                }.orEmpty())
                hint = "Recouvrement en %"
            }
            racine.addView(label("Recouvrement (%)")); racine.addView(et); et
        } else null

        lireAbon = if (visible("abundance")) {
            val (sp, lire) = construireSpinner("ABONDANCE_HAB", idInit(ex?.idNomAbondance, "ABONDANCE_HAB"))
            racine.addView(label("Abondance")); racine.addView(sp); lire
        } else { { ex?.idNomAbondance } }
    }

    private fun valider() {
        val cd = cdHabChoisi
        if (cd == null || cd <= 0) {
            toast("Choisissez un habitat dans la liste HABREF")
            return
        }
        val libelle = champHab?.text?.toString()?.trim().orEmpty()
        // Champ VISIBLE → sa valeur fait foi (vide = effacé) ; champ MASQUÉ par formConfig →
        // on préserve la valeur existante. L'ancien repli `?: existant?.x` empêchait d'EFFACER
        // un champ visible (la valeur précédente réapparaissait).
        val habitat = (existant ?: OccHabHabitat()).copy(
            cdHab = cd,
            habitatLabel = libelle,
            nomCite = libelle,
            determiner = champDeterminateur?.let { it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?: if (champDeterminateur == null) existant?.determiner else null,
            precisionTechnique = champPrecision?.let { it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?: if (champPrecision == null) existant?.precisionTechnique else null,
            recouvrement = champRecouvr?.let { it.text?.toString()?.trim()?.toDoubleOrNull() }
                ?: if (champRecouvr == null) existant?.recouvrement else null,
            idNomTechniqueCollecte = lireTech(),
            idNomAbondance = lireAbon(),
            idNomTypeDetermination = lireTypeDeterm(),
            idNomInteretCommunautaire = lireInteret(),
        )
        occhabViewModel.ajouterOuMajHabitat(habitat)
        // Enregistrement AU FIL DE L'EAU (comme les saisies Occtax) : la station — détails de
        // session fusionnés — est (ré)écrite dans « Mes stations » dès qu'un habitat est validé.
        val station = occhabViewModel.stationAEnregistrer()
        fr.ariegenature.geomys.store.OccHabStore(requireContext()).remplacer(station.id, station)
        occhabViewModel.enregistrerDansSession(station.id)
        findNavController().naviguerSur(R.id.action_occhab_habitat_to_liste)
    }

    private fun construireSpinner(type: String, idCourant: Int?): Pair<Spinner, () -> Int?> =
        construireSpinnerNomenclature(requireContext(), type, idCourant)

    private suspend fun chercherHabitats(terme: String): List<HabitatSuggestion> {
        val base = gnConfig.urlServeur.trim().trimEnd('/')
        val idList = gnConfig.occhabIdListHabitat.takeIf { it > 0 }
        return HabitatService.rechercher(base, terme, 20, idList, occhab = true)
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
