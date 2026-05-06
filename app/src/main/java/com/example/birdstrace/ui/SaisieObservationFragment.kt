package com.example.birdstrace.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.birdstrace.R
import com.example.birdstrace.TaxRefLocal
import com.example.birdstrace.databinding.FragmentSaisieObservationBinding
import com.example.birdstrace.model.Observation
import com.example.birdstrace.model.Taxon
import com.example.birdstrace.network.TaxRefService
import com.example.birdstrace.network.TaxRefStatut
import com.example.birdstrace.store.GeoNatureConfig
import com.example.birdstrace.store.TaxRefCache
import kotlinx.coroutines.*

class SaisieObservationFragment : Fragment() {
    private var _binding: FragmentSaisieObservationBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()
    private lateinit var gnConfig: GeoNatureConfig

    private var latitude = 0.0
    private var longitude = 0.0
    private var taxon: Taxon = Taxon.OISEAU
    private var taxRefStatut: TaxRefStatut? = null
    private var taxRefJob: Job? = null
    private var nombre = 1
    private var sexe = ""
    private var stadeVie = ""
    private var techniqueObs = ""
    private var statutBio = ""
    private var etaBio = ""
    private var preuveExist = ""
    private var objDenbr = ""
    private var typDenbr = ""
    private var comportement = ""
    private var methDetermin = ""
    private var determinateur = ""
    private var notes = ""
    private var cdNomManuel = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSaisieObservationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gnConfig = GeoNatureConfig(requireContext())
        latitude = arguments?.getDouble("latitude") ?: 0.0
        longitude = arguments?.getDouble("longitude") ?: 0.0

        binding.tvCoordonnees.text = "%.5f, %.5f".format(latitude, longitude)
        binding.tvNombre.text = "1 individu"

        setupTaxonSelector()
        setupAutocomplete()
        setupNombreControls()
        setupDetails()

        binding.btnAnnuler.setOnClickListener { findNavController().navigateUp() }
        binding.btnEnregistrer.setOnClickListener { enregistrer() }
    }

    private fun setupTaxonSelector() {
        updateTaxonUI()
        binding.btnTaxonOiseau.setOnClickListener {
            if (taxon != Taxon.OISEAU) { taxon = Taxon.OISEAU; onTaxonChanged() }
        }
        binding.btnTaxonMammifere.setOnClickListener {
            if (taxon != Taxon.MAMMIFERE) { taxon = Taxon.MAMMIFERE; onTaxonChanged() }
        }
        binding.btnTaxonReptile.setOnClickListener {
            if (taxon != Taxon.REPTILE) { taxon = Taxon.REPTILE; onTaxonChanged() }
        }
    }

    private fun onTaxonChanged() {
        binding.etEspece.setText("")
        taxRefStatut = null
        updateTaxRefUI()
        updateTaxonUI()
        refreshAutocompleteAdapter()
        updateEspeceHint()
    }

    private fun updateTaxonUI() {
        val transparent = ColorStateList.valueOf(Color.TRANSPARENT)
        val white = ColorStateList.valueOf(Color.WHITE)
        val gray = ContextCompat.getColorStateList(requireContext(), android.R.color.darker_gray)!!
        val oiseauColor = ContextCompat.getColor(requireContext(), R.color.orange)
        val mammiColor = ContextCompat.getColor(requireContext(), R.color.brown)
        val reptileColor = ContextCompat.getColor(requireContext(), R.color.colorSecondary)

        listOf(binding.btnTaxonOiseau, binding.btnTaxonMammifere, binding.btnTaxonReptile).forEach { btn ->
            btn.backgroundTintList = transparent
            btn.setTextColor(gray)
            btn.iconTint = gray
        }
        when (taxon) {
            Taxon.OISEAU -> {
                binding.btnTaxonOiseau.backgroundTintList = ColorStateList.valueOf(oiseauColor)
                binding.btnTaxonOiseau.setTextColor(Color.WHITE)
                binding.btnTaxonOiseau.iconTint = white
            }
            Taxon.MAMMIFERE -> {
                binding.btnTaxonMammifere.backgroundTintList = ColorStateList.valueOf(mammiColor)
                binding.btnTaxonMammifere.setTextColor(Color.WHITE)
                binding.btnTaxonMammifere.iconTint = white
            }
            Taxon.REPTILE -> {
                binding.btnTaxonReptile.backgroundTintList = ColorStateList.valueOf(reptileColor)
                binding.btnTaxonReptile.setTextColor(Color.WHITE)
                binding.btnTaxonReptile.iconTint = white
            }
        }
    }

    private fun refreshAutocompleteAdapter() {
        binding.etEspece.setAdapter(accentInsensitiveAdapter(TaxRefLocal.getSuggestions(taxon)))
    }

    private fun updateEspeceHint() {
        binding.tilEspece.hint = "" // On vide le hint pour qu'il ne flotte pas
        binding.tilEspece.placeholderText = when (taxon) {
            Taxon.MAMMIFERE -> "Espèce observée (ex: Renard roux, Blaireau…)"
            Taxon.REPTILE   -> "Espèce observée (ex: Lézard des murailles, Vipère aspic…)"
            else            -> "Espèce observée (ex: Merle noir, Rouge-gorge…)"
        }
    }

    private fun accentInsensitiveAdapter(suggestions: List<String>): ArrayAdapter<String> {
        val all = suggestions.toList()
        return object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_dropdown_item_1line, all.toMutableList()) {
            override fun getFilter() = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    val filtered = if (constraint.isNullOrEmpty()) all
                    else {
                        val query = TaxRefCache.normaliser(constraint.toString())
                        all.filter { TaxRefCache.normaliser(it).contains(query) }
                    }
                    results.values = filtered
                    results.count = filtered.size
                    return results
                }
                @Suppress("UNCHECKED_CAST")
                override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                    clear()
                    if (results.count > 0) addAll(results.values as List<String>)
                    notifyDataSetChanged()
                }
            }
        }
    }

    private fun setupAutocomplete() {
        binding.etEspece.setAdapter(accentInsensitiveAdapter(TaxRefLocal.getSuggestions(taxon)))
        binding.etEspece.threshold = 1

        binding.btnEnregistrer.isEnabled = false
        binding.etEspece.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.btnEnregistrer.isEnabled = s?.toString()?.trim()?.isNotEmpty() == true
                lancerRechercheTaxRef(s?.toString() ?: "")
            }
        })
    }

    private fun lancerRechercheTaxRef(nom: String) {
        taxRefJob?.cancel()
        if (nom.length < 2) {
            taxRefStatut = null
            updateTaxRefUI()
            return
        }
        taxRefJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            if (!isActive) return@launch
            binding.taxrefProgress.visibility = View.VISIBLE
            binding.tvTaxrefStatut.visibility = View.GONE
            val (statut, _) = TaxRefService.rechercher(nom, gnConfig)
            taxRefStatut = statut
            binding.taxrefProgress.visibility = View.GONE
            updateTaxRefUI()
        }
    }

    private fun updateTaxRefUI() {
        when (val s = taxRefStatut) {
            is TaxRefStatut.Trouve -> {
                binding.tvTaxrefStatut.visibility = View.VISIBLE
                binding.tvTaxrefStatut.text = "✓ ${s.nomScientifique}  •  cd_nom ${s.cdNom}"
                binding.tvTaxrefStatut.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
            }
            TaxRefStatut.NonTrouve -> {
                binding.tvTaxrefStatut.visibility = View.VISIBLE
                binding.tvTaxrefStatut.text = getString(R.string.taxref_non_trouve)
                binding.tvTaxrefStatut.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
            }
            TaxRefStatut.Indisponible -> {
                binding.tvTaxrefStatut.visibility = View.VISIBLE
                binding.tvTaxrefStatut.text = getString(R.string.taxref_indisponible)
                binding.tvTaxrefStatut.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
            }
            null -> {
                binding.tvTaxrefStatut.visibility = View.GONE
                binding.taxrefProgress.visibility = View.GONE
            }
        }
    }

    private fun setupNombreControls() {
        binding.btnMoins.setOnClickListener {
            if (nombre > 1) {
                nombre--
                binding.tvNombre.text = if (nombre == 1) "1 individu" else "$nombre individus"
            }
        }
        binding.btnPlus.setOnClickListener {
            if (nombre < 999) {
                nombre++
                binding.tvNombre.text = "$nombre individus"
            }
        }
    }

    private fun setupDetails() {
        binding.btnDetails.setOnClickListener {
            val bundle = Bundle().apply {
                putString("notes",         notes)
                putString("sexe",          sexe)
                putString("stadeVie",      stadeVie)
                putString("techniqueObs",  techniqueObs)
                putString("statutBio",     statutBio)
                putString("etaBio",        etaBio)
                putString("preuveExist",   preuveExist)
                putString("objDenbr",      objDenbr)
                putString("typDenbr",      typDenbr)
                putString("comportement",  comportement)
                putString("methDetermin",  methDetermin)
                putString("determinateur", determinateur)
                putString("cdNomManuel",   cdNomManuel)
                putBoolean("taxrefNonTrouve", taxRefStatut == TaxRefStatut.NonTrouve || taxRefStatut == TaxRefStatut.Indisponible)
                putDouble("latitude", latitude)
                putDouble("longitude", longitude)
            }
            findNavController().navigate(R.id.action_saisie_to_details, bundle)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Récupérer les détails retournés
        findNavController().currentBackStackEntry?.savedStateHandle?.apply {
            getLiveData<String>("notes").observe(viewLifecycleOwner)         { notes = it; updateDetailsIndicator() }
            getLiveData<String>("sexe").observe(viewLifecycleOwner)          { sexe = it; updateDetailsIndicator() }
            getLiveData<String>("stadeVie").observe(viewLifecycleOwner)      { stadeVie = it; updateDetailsIndicator() }
            getLiveData<String>("techniqueObs").observe(viewLifecycleOwner)  { techniqueObs = it; updateDetailsIndicator() }
            getLiveData<String>("statutBio").observe(viewLifecycleOwner)     { statutBio = it; updateDetailsIndicator() }
            getLiveData<String>("etaBio").observe(viewLifecycleOwner)        { etaBio = it; updateDetailsIndicator() }
            getLiveData<String>("preuveExist").observe(viewLifecycleOwner)   { preuveExist = it; updateDetailsIndicator() }
            getLiveData<String>("objDenbr").observe(viewLifecycleOwner)      { objDenbr = it; updateDetailsIndicator() }
            getLiveData<String>("typDenbr").observe(viewLifecycleOwner)      { typDenbr = it; updateDetailsIndicator() }
            getLiveData<String>("comportement").observe(viewLifecycleOwner)  { comportement = it; updateDetailsIndicator() }
            getLiveData<String>("methDetermin").observe(viewLifecycleOwner)  { methDetermin = it; updateDetailsIndicator() }
            getLiveData<String>("determinateur").observe(viewLifecycleOwner) { determinateur = it }
            getLiveData<String>("cdNomManuel").observe(viewLifecycleOwner)   { cdNomManuel = it }
        }
    }

    private fun updateDetailsIndicator() {
        val hasDetails = notes.isNotEmpty() || sexe.isNotEmpty() || stadeVie.isNotEmpty() ||
            techniqueObs.isNotEmpty() || statutBio.isNotEmpty() || etaBio.isNotEmpty() ||
            preuveExist.isNotEmpty() || objDenbr.isNotEmpty() || typDenbr.isNotEmpty() ||
            comportement.isNotEmpty() || methDetermin.isNotEmpty()
        binding.ivDetailsIndicator.visibility = if (hasDetails) View.VISIBLE else View.GONE
    }

    private fun enregistrer() {
        val especeText = binding.etEspece.text.toString().trim()
        val (cdNomFinal, nomAffiche) = when (val s = taxRefStatut) {
            is TaxRefStatut.Trouve -> Pair(s.cdNom, s.nomFrancais ?: s.nomScientifique)
            else -> Pair(cdNomManuel.trim().toIntOrNull(), especeText)
        }
        val obs = Observation(
            espece = if (nomAffiche.isEmpty()) "Espèce inconnue" else nomAffiche,
            taxon = taxon,
            cdNom = cdNomFinal,
            latitude = latitude,
            longitude = longitude,
            notes = notes,
            nombre = nombre,
            sexe          = sexe.ifEmpty { null },
            stadeVie      = stadeVie.ifEmpty { null },
            techniqueObs  = techniqueObs.ifEmpty { null },
            statutBio     = statutBio.ifEmpty { null },
            etaBio        = etaBio.ifEmpty { null },
            preuveExist   = preuveExist.ifEmpty { null },
            objDenbr      = objDenbr.ifEmpty { null },
            typDenbr      = typDenbr.ifEmpty { null },
            comportement  = comportement.ifEmpty { null },
            methDetermin  = methDetermin.ifEmpty { null },
            determinateur = determinateur.ifEmpty { null }
        )
        traceViewModel.ajouterObservation(obs)
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        taxRefJob?.cancel()
        _binding = null
    }

}