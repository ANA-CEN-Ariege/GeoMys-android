package com.example.birdstrace.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    private var speechRecognizer: SpeechRecognizer? = null

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) demarrerEcoute() else Toast.makeText(requireContext(), "Permission micro refusée", Toast.LENGTH_SHORT).show()
    }
    private val traceViewModel: TraceViewModel by activityViewModels()
    private lateinit var gnConfig: GeoNatureConfig

    private var obsId: String? = null
    private var latitude = 0.0
    private var longitude = 0.0
    private var taxon: Taxon = Taxon.OISEAU
    private var rechercheNomSci = false
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
        // ScrollView edge-to-edge : padding-top = status bar, padding-bottom = nav bar + IME.
        binding.root.applySystemBarInsets(includeIme = true)
        gnConfig = GeoNatureConfig(requireContext())
        obsId = arguments?.getString("obsId")

        val obsExistante = obsId?.let { id ->
            traceViewModel.observations.value?.find { it.id == id }
        }

        if (obsExistante != null) {
            latitude = obsExistante.latitude
            longitude = obsExistante.longitude
            taxon = obsExistante.taxon ?: Taxon.OISEAU
            nombre = obsExistante.nombre
            notes = obsExistante.notes
            sexe = obsExistante.sexe ?: ""
            stadeVie = obsExistante.stadeVie ?: ""
            techniqueObs = obsExistante.techniqueObs ?: ""
            statutBio = obsExistante.statutBio ?: ""
            etaBio = obsExistante.etaBio ?: ""
            preuveExist = obsExistante.preuveExist ?: ""
            objDenbr = obsExistante.objDenbr ?: ""
            typDenbr = obsExistante.typDenbr ?: ""
            comportement = obsExistante.comportement ?: ""
            methDetermin = obsExistante.methDetermin ?: ""
            determinateur = obsExistante.determinateur ?: ""
            cdNomManuel = obsExistante.cdNom?.toString() ?: ""
        } else {
            latitude = arguments?.getDouble("latitude") ?: 0.0
            longitude = arguments?.getDouble("longitude") ?: 0.0
        }

        binding.tvNombre.text = if (nombre == 1) "1 individu" else "$nombre individus"

        setupTaxonSelector()
        setupAutocomplete()
        setupNombreControls()
        setupDetails()

        if (obsExistante != null) {
            binding.etEspece.setText(obsExistante.espece)
            updateDetailsIndicator()
            requireActivity().title = "Modifier l'observation"
        }

        binding.btnAnnuler.setOnClickListener { findNavController().navigateUp() }
        binding.btnEnregistrer.setOnClickListener { enregistrer() }
    }

    private fun setupTaxonSelector() {
        updateTaxonUI()
        listOf(
            binding.btnTaxonOiseau     to Taxon.OISEAU,
            binding.btnTaxonMammifere  to Taxon.MAMMIFERE,
            binding.btnTaxonReptile    to Taxon.REPTILE,
            binding.btnTaxonBatracien  to Taxon.BATRACIEN,
            binding.btnTaxonPoisson    to Taxon.POISSON,
            binding.btnTaxonInsecte    to Taxon.INSECTE,
            binding.btnTaxonFonge      to Taxon.FONGE,
            binding.btnTaxonInvertebres to Taxon.INVERTEBRES,
            binding.btnTaxonPlante     to Taxon.PLANTE,
        ).forEach { (btn, t) ->
            btn.setOnClickListener { if (taxon != t) { taxon = t; onTaxonChanged() } }
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

        val couleurs = mapOf(
            Taxon.OISEAU      to ContextCompat.getColor(requireContext(), R.color.orange),
            Taxon.MAMMIFERE   to ContextCompat.getColor(requireContext(), R.color.brown),
            Taxon.REPTILE     to ContextCompat.getColor(requireContext(), R.color.colorSecondary),
            Taxon.BATRACIEN   to ContextCompat.getColor(requireContext(), R.color.blue_batracien),
            Taxon.POISSON     to ContextCompat.getColor(requireContext(), R.color.blue_poisson),
            Taxon.INSECTE     to ContextCompat.getColor(requireContext(), R.color.amber_insecte),
            Taxon.FONGE       to ContextCompat.getColor(requireContext(), R.color.brown_fonge),
            Taxon.INVERTEBRES to ContextCompat.getColor(requireContext(), R.color.purple_invertebres),
            Taxon.PLANTE      to ContextCompat.getColor(requireContext(), R.color.teal),
        )
        val boutons = mapOf(
            Taxon.OISEAU      to binding.btnTaxonOiseau,
            Taxon.MAMMIFERE   to binding.btnTaxonMammifere,
            Taxon.REPTILE     to binding.btnTaxonReptile,
            Taxon.BATRACIEN   to binding.btnTaxonBatracien,
            Taxon.POISSON     to binding.btnTaxonPoisson,
            Taxon.INSECTE     to binding.btnTaxonInsecte,
            Taxon.FONGE       to binding.btnTaxonFonge,
            Taxon.INVERTEBRES to binding.btnTaxonInvertebres,
            Taxon.PLANTE      to binding.btnTaxonPlante,
        )
        boutons.values.forEach { btn ->
            btn.backgroundTintList = transparent
            btn.setTextColor(gray)
            btn.iconTint = gray
        }
        boutons[taxon]?.let { btn ->
            val color = couleurs[taxon] ?: return
            btn.backgroundTintList = ColorStateList.valueOf(color)
            btn.setTextColor(Color.WHITE)
            btn.iconTint = white
        }
    }

    private fun refreshAutocompleteAdapter() {
        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = withContext(Dispatchers.Default) {
                TaxRefLocal.getSuggestionsAutocomplete(taxon, rechercheNomSci)
            }
            if (isAdded) binding.etEspece.setAdapter(accentInsensitiveAdapter(suggestions))
        }
    }

    private fun updateEspeceHint() {
        binding.tilEspece.hint = ""
        binding.tilEspece.placeholderText = if (rechercheNomSci) when (taxon) {
            Taxon.MAMMIFERE   -> "Nom scientifique (ex: Vulpes vulpes, Meles meles…)"
            Taxon.REPTILE     -> "Nom scientifique (ex: Podarcis muralis, Vipera aspis…)"
            Taxon.BATRACIEN   -> "Nom scientifique (ex: Rana temporaria, Salamandra salamandra…)"
            Taxon.POISSON     -> "Nom scientifique (ex: Esox lucius, Salmo trutta…)"
            Taxon.INSECTE     -> "Nom scientifique (ex: Papilio machaon, Apis mellifera…)"
            Taxon.FONGE       -> "Nom scientifique (ex: Boletus edulis, Cantharellus cibarius…)"
            Taxon.INVERTEBRES -> "Nom scientifique (ex: Helix pomatia, Homarus gammarus…)"
            Taxon.PLANTE      -> "Nom scientifique (ex: Quercus robur, Rosa canina…)"
            else              -> "Nom scientifique (ex: Turdus merula, Erithacus rubecula…)"
        } else when (taxon) {
            Taxon.MAMMIFERE   -> "Espèce observée (ex: Renard roux, Blaireau…)"
            Taxon.REPTILE     -> "Espèce observée (ex: Lézard des murailles, Vipère aspic…)"
            Taxon.BATRACIEN   -> "Espèce observée (ex: Grenouille verte, Salamandre tachetée…)"
            Taxon.POISSON     -> "Espèce observée (ex: Brochet, Truite fario…)"
            Taxon.INSECTE     -> "Espèce observée (ex: Criquet mélodieux, Machaon…)"
            Taxon.FONGE       -> "Espèce observée (ex: Cèpe de Bordeaux, Chanterelle…)"
            Taxon.INVERTEBRES -> "Espèce observée (ex: Escargot de Bourgogne, Homard…)"
            Taxon.PLANTE      -> "Espèce observée (ex: Chêne pédonculé, Genêt purgatif…)"
            else              -> "Espèce observée (ex: Merle noir, Rouge-gorge…)"
        }
    }

    private fun accentInsensitiveAdapter(suggestions: List<String>): ArrayAdapter<String> {
        // Pré-normaliser une seule fois — évite O(n) appels à normaliser() à chaque frappe
        val normalized: List<Pair<String, String>> = suggestions.map { TaxRefCache.normaliser(it) to it }
        return object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_dropdown_item_1line, suggestions.toMutableList()) {
            override fun getFilter() = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    val filtered: List<String> = if (constraint.isNullOrEmpty()) suggestions
                    else {
                        val query = TaxRefCache.normaliser(constraint.toString())
                        // Tri composite : startsWith(query) en premier, puis contains(query).
                        val starts = ArrayList<String>()
                        val contains = ArrayList<String>()
                        for ((key, display) in normalized) {
                            when {
                                key.startsWith(query) -> starts.add(display)
                                key.contains(query)   -> contains.add(display)
                            }
                        }
                        starts + contains
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

    private fun lancerDictee() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(requireContext(), "Reconnaissance vocale non disponible", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        demarrerEcoute()
    }

    private fun demarrerEcoute() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    binding.tilEspece.setEndIconDrawable(R.drawable.ic_mic_active)
                }
                override fun onResults(results: Bundle?) {
                    binding.tilEspece.setEndIconDrawable(R.drawable.ic_mic)
                    val texte = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    binding.etEspece.setText(texte)
                    binding.etEspece.setSelection(texte.length)
                }
                override fun onError(error: Int) {
                    binding.tilEspece.setEndIconDrawable(R.drawable.ic_mic)
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH      -> "Aucune correspondance — réessayez"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Aucune voix détectée"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Erreur réseau — connexion requise"
                        SpeechRecognizer.ERROR_AUDIO         -> "Erreur microphone"
                        else -> "Erreur reconnaissance ($error)"
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val partiel = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    binding.etEspece.setText(partiel)
                    binding.etEspece.setSelection(partiel.length)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
    }

    private fun setupAutocomplete() {
        binding.etEspece.threshold = 1
        refreshAutocompleteAdapter()
        binding.tilEspece.setEndIconOnClickListener { lancerDictee() }

        binding.switchNomSci.setOnCheckedChangeListener { _, isChecked ->
            rechercheNomSci = isChecked
            binding.etEspece.setText("")
            taxRefStatut = null
            updateTaxRefUI()
            refreshAutocompleteAdapter()
            updateEspeceHint()
        }

        binding.btnEnregistrer.isEnabled = false
        binding.etEspece.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Désactivé tant qu'on n'a pas un match TaxRef confirmé (ou un cd_nom manuel valide).
                updateBtnEnregistrerState()
                lancerRechercheTaxRef(s?.toString() ?: "")
            }
        })
    }

    private fun updateBtnEnregistrerState() {
        val texte = binding.etEspece.text?.toString()?.trim().orEmpty()
        val matchTaxRef = taxRefStatut is TaxRefStatut.Trouve
        val cdNomManuelOk = (cdNomManuel.trim().toIntOrNull() ?: 0) > 0
        binding.btnEnregistrer.isEnabled = texte.isNotEmpty() && (matchTaxRef || cdNomManuelOk)
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
            val (statut, _) = TaxRefService.rechercher(nom, taxon, gnConfig)
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
        updateBtnEnregistrerState()
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
            val cdNom = (taxRefStatut as? TaxRefStatut.Trouve)?.cdNom
            val groupe2Inpn = cdNom?.let { TaxRefCache.tousLesGroupes()[it.toString()] }
                ?: when (taxon) {
                    Taxon.MAMMIFERE   -> "Mammifères"
                    Taxon.REPTILE     -> "Reptiles"
                    Taxon.BATRACIEN   -> "Amphibiens"
                    Taxon.POISSON     -> "Poissons"
                    Taxon.INSECTE     -> "Insectes"
                    Taxon.PLANTE,
                    Taxon.FONGE,
                    Taxon.INVERTEBRES -> null
                    else              -> "Oiseaux"
                }
            val bundle = Bundle().apply {
                putString("taxon",         taxon.name)
                putString("groupe2Inpn",   groupe2Inpn)
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
            getLiveData<String>("cdNomManuel").observe(viewLifecycleOwner)   { cdNomManuel = it; updateBtnEnregistrerState() }
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
        val nomFinal = if (nomAffiche.isEmpty()) "Espèce inconnue" else nomAffiche

        val idExistant = obsId
        val obsBase = idExistant?.let { id ->
            traceViewModel.observations.value?.find { it.id == id }
        }

        if (obsBase != null) {
            traceViewModel.modifierObservation(obsBase.copy(
                espece        = nomFinal,
                taxon         = taxon,
                cdNom         = cdNomFinal,
                notes         = notes,
                nombre        = nombre,
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
            ))
        } else {
            traceViewModel.ajouterObservation(Observation(
                espece        = nomFinal,
                taxon         = taxon,
                cdNom         = cdNomFinal,
                latitude      = latitude,
                longitude     = longitude,
                notes         = notes,
                nombre        = nombre,
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
            ))
        }
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        taxRefJob?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _binding = null
    }

}