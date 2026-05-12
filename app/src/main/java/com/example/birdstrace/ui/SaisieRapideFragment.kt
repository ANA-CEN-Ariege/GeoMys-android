package com.example.birdstrace.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.birdstrace.R
import com.example.birdstrace.TaxRefLocal
import com.example.birdstrace.databinding.FragmentSaisieRapideBinding
import com.example.birdstrace.model.Observation
import com.example.birdstrace.model.Taxon
import com.example.birdstrace.model.Sortie
import com.example.birdstrace.network.GeoNatureService
import com.example.birdstrace.network.TaxRefService
import com.example.birdstrace.network.TaxRefStatut
import com.example.birdstrace.store.GeoNatureConfig
import com.example.birdstrace.store.SortieStore
import com.example.birdstrace.store.TaxRefCache
import com.example.birdstrace.location.LocationForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.core.graphics.drawable.toBitmap
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SaisieRapideFragment : Fragment() {
    private var _binding: FragmentSaisieRapideBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()
    private lateinit var sortieStore: SortieStore
    private lateinit var gnConfig: GeoNatureConfig
    private var envoiJob: Job? = null

    // Paramètres par défaut
    private var taxon: Taxon = Taxon.OISEAU
    private var especeDefaut = ""
    private var cdNomDefaut: Int? = null
    private var nombre = 1
    private var rechercheNomSci = false
    private var taxRefStatut: TaxRefStatut? = null
    private var taxRefJob: Job? = null
    private var cdNomManuel = ""

    // Détails par défaut
    private var notes = ""
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

    // État
    private var modeActif = false
    private var compteurSession = 0
    private var suivrePosition = true
    private var derniereObsId: String? = null
    private var snackJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var fondCarte = FondCarte.OSM

    // Carte
    private var locationOverlay: MyLocationNewOverlay? = null
    private val observationMarkers = mutableMapOf<String, Marker>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            demarrerLocalisation()
        }
    }

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) demarrerEcoute() else Toast.makeText(requireContext(), "Permission micro refusée", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentSaisieRapideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sortieStore = SortieStore(requireContext())
        gnConfig = GeoNatureConfig(requireContext())
        setupMap()
        setupTaxonSelector()
        setupAutocomplete()
        setupControls()
        observerViewModel()
        demanderPermissions()
        applyWindowInsets()

        // Reprise d'une session rapide en cours : restaurer l'état avant d'afficher l'UI.
        traceViewModel.saisieRapideSession.value?.let { restaurerSession(it) }
        updateModeUI()
    }

    // ─── Carte ────────────────────────────────────────────────────────────────

    private fun setupMap() {
        binding.map.setTileSource(tileSourcePour(fondCarte))
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(15.0)
        binding.map.controller.setCenter(
            traceViewModel.locationTracker.position.value?.let {
                GeoPoint(it.latitude, it.longitude)
            } ?: GeoPoint(46.5, 2.5)
        )
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), binding.map).apply {
            setPersonIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap())
            setPersonHotspot(10f, 10f)
            setDirectionArrow(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap(),
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap()
            )
            setDrawAccuracyEnabled(true)
        }
        binding.map.overlays.add(locationOverlay)

        binding.map.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE && suivrePosition) {
                suivrePosition = false
                updateGpsLockIcon()
            }
            false
        }

        binding.btnFondCarte.setOnClickListener {
            fondCarte = fondCarte.suivant()
            binding.map.setTileSource(tileSourcePour(fondCarte))
            binding.map.invalidate()
        }
    }

    private fun demarrerLocalisation() {
        locationOverlay?.enableMyLocation()
        LocationForegroundService.start(requireContext())
    }

    private fun demanderPermissions() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        if (ContextCompat.checkSelfPermission(requireContext(), fine) == PackageManager.PERMISSION_GRANTED) {
            demarrerLocalisation()
        } else {
            permissionLauncher.launch(arrayOf(fine, coarse))
        }
    }

    // ─── Sélecteur taxon ──────────────────────────────────────────────────────

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
            btn.setOnClickListener {
                if (taxon != t) {
                    taxon = t
                    binding.etEspece.setText("")
                    taxRefStatut = null
                    updateTaxRefUI()
                    updateTaxonUI()
                    refreshAutocompleteAdapter()
                }
            }
        }
    }

    private fun updateTaxonUI() {
        val transparent = ColorStateList.valueOf(Color.TRANSPARENT)
        val white = ColorStateList.valueOf(Color.WHITE)
        val gray = ContextCompat.getColorStateList(requireContext(), android.R.color.darker_gray)!!
        val couleurs = taxonCouleurs()
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

    // ─── Autocomplete ─────────────────────────────────────────────────────────

    private fun setupAutocomplete() {
        binding.etEspece.threshold = 1
        refreshAutocompleteAdapter()
        updateEspeceHint()

        binding.etEspece.setOnItemClickListener { _, _, _, _ ->
            hideKeyboard()
            traceViewModel.locationTracker.position.value?.let { loc ->
                suivrePosition = true
                binding.map.controller.setZoom(19.0)
                binding.map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                updateGpsLockIcon()
            }
        }

        binding.switchNomSci.setOnCheckedChangeListener { _, isChecked ->
            rechercheNomSci = isChecked
            binding.etEspece.setText("")
            taxRefStatut = null
            updateTaxRefUI()
            refreshAutocompleteAdapter()
            updateEspeceHint()
        }

        binding.tilEspece.setEndIconOnClickListener { lancerDictee() }

        binding.btnDemarrer.isEnabled = false
        binding.etEspece.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Désactivé immédiatement quand le texte change ; réactivé après match TaxRef trouvé.
                updateDemarrerState()
                lancerRechercheTaxRef(s?.toString() ?: "")
            }
        })
    }

    private fun updateDemarrerState() {
        val texte = binding.etEspece.text?.toString()?.trim().orEmpty()
        val matchTaxRef = taxRefStatut is TaxRefStatut.Trouve
        val cdNomManuelOk = (cdNomManuel.trim().toIntOrNull() ?: 0) > 0
        binding.btnDemarrer.isEnabled = texte.isNotEmpty() && (matchTaxRef || cdNomManuelOk)
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
        binding.tilEspece.hint = if (rechercheNomSci) when (taxon) {
            Taxon.MAMMIFERE   -> "Nom scientifique (ex : Vulpes vulpes)"
            Taxon.REPTILE     -> "Nom scientifique (ex : Podarcis muralis)"
            Taxon.BATRACIEN   -> "Nom scientifique (ex : Rana temporaria)"
            Taxon.POISSON     -> "Nom scientifique (ex : Esox lucius)"
            Taxon.INSECTE     -> "Nom scientifique (ex : Papilio machaon)"
            Taxon.FONGE       -> "Nom scientifique (ex : Boletus edulis)"
            Taxon.INVERTEBRES -> "Nom scientifique (ex : Helix pomatia)"
            Taxon.PLANTE      -> "Nom scientifique (ex : Quercus robur)"
            else              -> "Nom scientifique (ex : Turdus merula)"
        } else when (taxon) {
            Taxon.MAMMIFERE   -> "Espèce (ex : Renard roux)"
            Taxon.REPTILE     -> "Espèce (ex : Lézard des murailles)"
            Taxon.BATRACIEN   -> "Espèce (ex : Grenouille verte)"
            Taxon.POISSON     -> "Espèce (ex : Brochet)"
            Taxon.INSECTE     -> "Espèce (ex : Machaon)"
            Taxon.FONGE       -> "Espèce (ex : Cèpe de Bordeaux)"
            Taxon.INVERTEBRES -> "Espèce (ex : Escargot de Bourgogne)"
            Taxon.PLANTE      -> "Espèce (ex : Chêne pédonculé)"
            else              -> "Espèce (ex : Merle noir)"
        }
    }

    private fun accentInsensitiveAdapter(suggestions: List<String>): ArrayAdapter<String> {
        val normalized = suggestions.map { TaxRefCache.normaliser(it) to it }
        return object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_dropdown_item_1line, suggestions.toMutableList()) {
            override fun getFilter() = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    val filtered = if (constraint.isNullOrEmpty()) suggestions
                    else {
                        val q = TaxRefCache.normaliser(constraint.toString())
                        val starts = ArrayList<String>()
                        val contains = ArrayList<String>()
                        for ((k, d) in normalized) {
                            when {
                                k.startsWith(q) -> starts.add(d)
                                k.contains(q)   -> contains.add(d)
                            }
                        }
                        starts + contains
                    }
                    results.values = filtered; results.count = filtered.size; return results
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

    private fun lancerRechercheTaxRef(nom: String) {
        taxRefJob?.cancel()
        if (nom.length < 2) { taxRefStatut = null; updateTaxRefUI(); return }
        val gnConfig = GeoNatureConfig(requireContext())
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
            null -> { binding.tvTaxrefStatut.visibility = View.GONE; binding.taxrefProgress.visibility = View.GONE }
        }
        updateDemarrerState()
    }

    // ─── Contrôles principaux ─────────────────────────────────────────────────

    private fun setupControls() {
        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }

        binding.btnDetailsDefaut.setOnClickListener {
            val bundle = Bundle().apply {
                putString("taxon", taxon.name)
                putString("groupe2Inpn", null)
                putString("notes", notes)
                putString("sexe", sexe)
                putString("stadeVie", stadeVie)
                putString("techniqueObs", techniqueObs)
                putString("statutBio", statutBio)
                putString("etaBio", etaBio)
                putString("preuveExist", preuveExist)
                putString("objDenbr", objDenbr)
                putString("typDenbr", typDenbr)
                putString("comportement", comportement)
                putString("methDetermin", methDetermin)
                putString("determinateur", determinateur)
                putString("cdNomManuel", cdNomManuel)
                putBoolean("taxrefNonTrouve", false)
                putDouble("latitude", 0.0)
                putDouble("longitude", 0.0)
            }
            findNavController().navigate(R.id.action_saisieRapide_to_details, bundle)
        }

        binding.btnDemarrer.setOnClickListener { demarrer() }
        binding.btnAnnulerSaisie.setOnClickListener { findNavController().navigateUp() }

        binding.btnModifierParams.setOnClickListener {
            modeActif = false
            updateModeUI()
        }

        binding.btnEnregistrerIci.setOnClickListener { enregistrerIci() }

        binding.btnTerminerRapide.setOnClickListener { showConfirmTerminer() }

        binding.btnCentrerGps.setOnClickListener {
            suivrePosition = true
            updateGpsLockIcon()
            traceViewModel.locationTracker.position.value?.let {
                binding.map.controller.animateTo(GeoPoint(it.latitude, it.longitude))
            }
        }

        binding.btnSnackModifier.setOnClickListener {
            val id = derniereObsId ?: return@setOnClickListener
            snackJob?.cancel()
            binding.snackConfirmation.visibility = View.GONE
            val bundle = Bundle().apply { putString("obsId", id) }
            findNavController().navigate(R.id.action_saisieRapide_to_saisie, bundle)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
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
            getLiveData<String>("cdNomManuel").observe(viewLifecycleOwner)   { cdNomManuel = it; updateDemarrerState() }
        }
    }

    private fun updateDetailsIndicator() {
        val hasDetails = notes.isNotEmpty() || sexe.isNotEmpty() || stadeVie.isNotEmpty() ||
            techniqueObs.isNotEmpty() || statutBio.isNotEmpty() || etaBio.isNotEmpty() ||
            preuveExist.isNotEmpty() || objDenbr.isNotEmpty() || typDenbr.isNotEmpty() ||
            comportement.isNotEmpty() || methDetermin.isNotEmpty()
        val chips = listOfNotNull(
            sexe.ifEmpty { null },
            stadeVie.ifEmpty { null },
            techniqueObs.ifEmpty { null }
        )
        binding.tvDetailsIndicator.visibility = if (hasDetails) View.VISIBLE else View.GONE
        binding.tvDetailsIndicator.text = chips.joinToString(" · ").ifEmpty {
            if (hasDetails) "Détails renseignés" else ""
        }
    }

    // ─── Logique de saisie ────────────────────────────────────────────────────

    private fun demarrer() {
        hideKeyboard()
        val especeText = binding.etEspece.text.toString().trim()
        especeDefaut = when (val s = taxRefStatut) {
            is TaxRefStatut.Trouve -> s.nomFrancais ?: s.nomScientifique
            else -> especeText.ifEmpty { taxon.nomGroupe() }
        }
        cdNomDefaut = (taxRefStatut as? TaxRefStatut.Trouve)?.cdNom
            ?: cdNomManuel.trim().toIntOrNull()

        modeActif = true
        snackJob?.cancel()
        updateModeUI()
        updateResumeActif()

        traceViewModel.locationTracker.position.value?.let { loc ->
            suivrePosition = true
            binding.map.controller.setZoom(19.0)
            binding.map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        }
        updateGpsLockIcon()

        // Persister la session pour pouvoir reprendre depuis l'accueil.
        traceViewModel.demarrerSaisieRapide(snapshotSession())
    }

    private fun snapshotSession(): TraceViewModel.SaisieRapideSession =
        TraceViewModel.SaisieRapideSession(
            taxon = taxon.name,
            especeDefaut = especeDefaut,
            cdNomDefaut = cdNomDefaut,
            rechercheNomSci = rechercheNomSci,
            sexe = sexe,
            stadeVie = stadeVie,
            techniqueObs = techniqueObs,
            statutBio = statutBio,
            etaBio = etaBio,
            preuveExist = preuveExist,
            objDenbr = objDenbr,
            typDenbr = typDenbr,
            comportement = comportement,
            methDetermin = methDetermin,
            determinateur = determinateur,
            notes = notes,
            cdNomManuel = cdNomManuel,
        )

    private fun restaurerSession(s: TraceViewModel.SaisieRapideSession) {
        taxon = try { Taxon.valueOf(s.taxon) } catch (_: Exception) { Taxon.OISEAU }
        especeDefaut = s.especeDefaut
        cdNomDefaut = s.cdNomDefaut
        rechercheNomSci = s.rechercheNomSci
        sexe = s.sexe
        stadeVie = s.stadeVie
        techniqueObs = s.techniqueObs
        statutBio = s.statutBio
        etaBio = s.etaBio
        preuveExist = s.preuveExist
        objDenbr = s.objDenbr
        typDenbr = s.typDenbr
        comportement = s.comportement
        methDetermin = s.methDetermin
        determinateur = s.determinateur
        notes = s.notes
        cdNomManuel = s.cdNomManuel
        modeActif = true
        updateTaxonUI()
        updateResumeActif()
        updateGpsLockIcon()

        // Même zoom et centrage que lors du démarrage initial — vue rue/champ proche.
        traceViewModel.locationTracker.position.value?.let { loc ->
            suivrePosition = true
            binding.map.controller.setZoom(19.0)
            binding.map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
        }
    }

    private fun hideKeyboard() {
        val view = activity?.currentFocus ?: view?.findFocus()
        if (view != null) {
            val imm = ContextCompat.getSystemService(requireContext(), android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun enregistrerIci() {
        val loc = traceViewModel.locationTracker.position.value
        val lat: Double
        val lon: Double

        if (suivrePosition && loc != null) {
            lat = loc.latitude
            lon = loc.longitude
        } else {
            val center = binding.map.mapCenter
            lat = center.latitude
            lon = center.longitude
        }

        val obs = Observation(
            espece        = especeDefaut.ifEmpty { taxon.nomGroupe() },
            taxon         = taxon,
            cdNom         = cdNomDefaut,
            latitude      = lat,
            longitude     = lon,
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
        )
        traceViewModel.ajouterObservation(obs)
        derniereObsId = obs.id
        compteurSession++
        updateCompteur()
        montrerConfirmation(obs)
        vibrer()
        // Re-locker sur le GPS pour l'observation suivante
        suivrePosition = true
        updateGpsLockIcon()
        traceViewModel.locationTracker.position.value?.let {
            binding.map.controller.animateTo(GeoPoint(it.latitude, it.longitude))
        }
    }

    private fun vibrer() {
        val vib = requireContext().getSystemService(Vibrator::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(60)
        }
    }

    private fun montrerConfirmation(obs: Observation) {
        snackJob?.cancel()
        val label = "${obs.espece} × ${obs.nombre}"
        binding.tvSnackEspece.text = label
        binding.snackConfirmation.visibility = View.VISIBLE
        snackJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(3000)
            if (isActive) binding.snackConfirmation.visibility = View.GONE
        }
    }

    // ─── UI ───────────────────────────────────────────────────────────────────

    private fun updateGpsLockIcon() {
        binding.btnCentrerGps.setImageResource(
            if (suivrePosition) R.drawable.ic_location_on else R.drawable.ic_location_off
        )
    }

    private fun updateModeUI() {
        binding.panneauConfig.visibility = if (modeActif) View.GONE else View.VISIBLE
        binding.panneauActif.visibility  = if (modeActif) View.VISIBLE else View.GONE
        binding.reticule.visibility      = if (modeActif) View.VISIBLE else View.GONE
        binding.tvCompteur.visibility    = if (modeActif) View.VISIBLE else View.GONE
        // Carte et bouton fond de carte cachés tant qu'on n'a pas démarré la saisie
        binding.map.visibility           = if (modeActif) View.VISIBLE else View.GONE
        binding.btnFondCarte.visibility  = if (modeActif) View.VISIBLE else View.GONE
        if (!modeActif) {
            snackJob?.cancel()
            binding.snackConfirmation.visibility = View.GONE
        }
    }

    private fun updateCompteur() {
        val n = compteurSession
        binding.tvCompteur.text = "$n obs. enregistrée${if (n > 1) "s" else ""}"
    }

    private fun updateResumeActif() {
        val (iconRes, colorRes) = taxonIconEtCouleur(taxon)
        binding.ivTaxonIconActif.setImageResource(iconRes)
        binding.ivTaxonIconActif.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))

        val label = especeDefaut.ifEmpty { taxon.nomGroupe() }
        binding.tvResumeParams.text = "$label × $nombre"

        val chips = listOfNotNull(sexe.ifEmpty { null }, stadeVie.ifEmpty { null })
        binding.tvResumeDetails.text = chips.joinToString(" · ")
        binding.tvResumeDetails.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
    }

    // ─── Markers ──────────────────────────────────────────────────────────────

    private fun observerViewModel() {
        traceViewModel.locationTracker.position.observe(viewLifecycleOwner) { loc ->
            loc ?: return@observe
            if (suivrePosition) binding.map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        }
        traceViewModel.observations.observe(viewLifecycleOwner) { obs ->
            updateMarkers(obs)
        }
    }

    private fun updateMarkers(observations: List<Observation>) {
        val currentIds = observations.map { it.id }.toSet()
        observationMarkers.keys.filter { it !in currentIds }.forEach { id ->
            observationMarkers[id]?.let { binding.map.overlays.remove(it) }
            observationMarkers.remove(id)
        }
        observations.forEach { obs ->
            if (obs.id !in observationMarkers) {
                val marker = Marker(binding.map).apply {
                    position = GeoPoint(obs.latitude, obs.longitude)
                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_bird_marker)
                    title = obs.espece
                    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                    snippet = fmt.format(Date(obs.date)) + if (obs.nombre > 1) " · ${obs.nombre} ind." else ""
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ ->
                        val toutes = traceViewModel.observations.value ?: emptyList()
                        montrerListeEspeces(obsProches(obs, toutes).ifEmpty { listOf(obs) })
                        true
                    }
                }
                observationMarkers[obs.id] = marker
                binding.map.overlays.add(marker)
            }
        }
        binding.map.invalidate()
    }

    private fun obsProches(reference: Observation, toutes: List<Observation>): List<Observation> {
        val out = FloatArray(1)
        return toutes.filter {
            android.location.Location.distanceBetween(
                reference.latitude, reference.longitude, it.latitude, it.longitude, out
            )
            out[0] <= 30f
        }
    }

    private fun montrerListeEspeces(observations: List<Observation>) {
        val triees = observations.sortedByDescending { it.date }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val items = triees.map { o ->
            val heure = fmt.format(Date(o.date))
            val n = if (o.nombre > 1) " · ${o.nombre} ind." else ""
            val notes = if (o.notes.isNotEmpty()) "\n   ${o.notes}" else ""
            "$heure  ${o.espece}$n$notes"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("${triees.size} obs. ici — appuyer pour modifier")
            .setItems(items) { _, which ->
                val bundle = Bundle().apply { putString("obsId", triees[which].id) }
                findNavController().navigate(R.id.action_saisieRapide_to_saisie, bundle)
            }
            .setPositiveButton("Fermer", null)
            .show()
    }

    // ─── Dictée vocale ────────────────────────────────────────────────────────

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
                override fun onReadyForSpeech(params: Bundle?) { binding.tilEspece.setEndIconDrawable(R.drawable.ic_mic_active) }
                override fun onResults(results: Bundle?) {
                    binding.tilEspece.setEndIconDrawable(R.drawable.ic_mic)
                    val texte = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    binding.etEspece.setText(texte)
                    binding.etEspece.setSelection(texte.length)
                }
                override fun onError(error: Int) { binding.tilEspece.setEndIconDrawable(R.drawable.ic_mic) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    binding.etEspece.setText(p); binding.etEspece.setSelection(p.length)
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

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun taxonCouleurs() = mapOf(
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

    private fun taxonIconEtCouleur(t: Taxon): Pair<Int, Int> = when (t) {
        Taxon.OISEAU      -> R.drawable.oiseaux           to R.color.orange
        Taxon.MAMMIFERE   -> R.drawable.mammiferes2        to R.color.brown
        Taxon.REPTILE     -> R.drawable.reptiles2          to R.color.colorSecondary
        Taxon.BATRACIEN   -> R.drawable.amphibiens      to R.color.blue_batracien
        Taxon.POISSON     -> R.drawable.poissons          to R.color.blue_poisson
        Taxon.INSECTE     -> R.drawable.insectes           to R.color.amber_insecte
        Taxon.FONGE       -> R.drawable.champignons2      to R.color.brown_fonge
        Taxon.INVERTEBRES -> R.drawable.mollusques       to R.color.purple_invertebres
        Taxon.PLANTE      -> R.drawable.fleurs           to R.color.teal
    }

    private fun applyWindowInsets() {
        // Carte plein écran, overlays et panneaux à l'écart des barres système.
        binding.btnRetour.applyStatusBarMargin()
        binding.btnFondCarte.applyStatusBarMargin()
        binding.tvCompteur.applyStatusBarMargin()
        binding.panneauConfig.applySystemBarInsets(includeIme = true)
        binding.panneauActif.applyNavBarInset()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationOverlay?.enableMyLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        locationOverlay?.disableMyLocation()
        traceViewModel.sauvegarder()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        snackJob?.cancel()
        taxRefJob?.cancel()
        envoiJob?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        observationMarkers.clear()
        _binding = null
    }

    // ─── Terminer la saisie ───────────────────────────────────────────────────

    private fun showConfirmTerminer() {
        val obs = traceViewModel.observations.value ?: emptyList()
        if (obs.isEmpty()) {
            findNavController().navigateUp()
            return
        }

        val peutEnvoyerGn = gnConfig.estConfiguree && obs.any { it.cdNom != null }
        val options = mutableListOf(getString(R.string.enregistrer_quitter))
        if (peutEnvoyerGn) options.add(getString(R.string.enregistrer_envoyer_gn))
        options.add(getString(R.string.supprimer_sortie))
        options.add(getString(R.string.continuer_sortie))

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.terminer_sortie)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.enregistrer_quitter) -> terminerSaisie(envoyerGN = false)
                    getString(R.string.enregistrer_envoyer_gn) -> terminerSaisie(envoyerGN = true)
                    getString(R.string.supprimer_sortie) -> {
                        traceViewModel.reinitialiser()
                        LocationForegroundService.stop(requireContext())
                        findNavController().navigateUp()
                    }
                    // Continuer : ne rien faire, fermer le dialog
                }
            }.show()
    }

    private fun terminerSaisie(envoyerGN: Boolean) {
        LocationForegroundService.stop(requireContext())
        val sortie = Sortie(
            date = System.currentTimeMillis(),
            pointsParcours = emptyList(),
            observations = traceViewModel.observations.value?.toList() ?: emptyList(),
            distanceTotale = 0.0
        )
        if (sortie.observations.isNotEmpty()) sortieStore.ajouter(sortie)
        traceViewModel.reinitialiser()

        if (envoyerGN) envoyerVersGeoNature(sortie)
        else findNavController().navigateUp()
    }

    private fun envoyerVersGeoNature(sortie: Sortie) {
        envoiJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (nb, total, premierReleve) = GeoNatureService.envoyer(sortie, gnConfig)
                sortieStore.marquerEnvoyee(sortie.id)
                var msg = "$nb/$total relevé${if (total > 1) "s" else ""} créé${if (nb > 1) "s" else ""}"
                if (premierReleve != null) msg += " (premier #$premierReleve)"
                showResult(msg, true)
            } catch (e: Exception) {
                showResult(e.message ?: "Erreur inconnue", false)
            }
        }
    }

    private fun showResult(msg: String, success: Boolean) {
        if (!isAdded) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(if (success) "GeoNature" else getString(R.string.erreur_envoi))
            .setMessage(msg)
            .setPositiveButton("OK") { _, _ -> findNavController().navigateUp() }
            .show()
    }
}

private fun Taxon.nomGroupe(): String = when (this) {
    Taxon.OISEAU      -> "Oiseau"
    Taxon.MAMMIFERE   -> "Mammifère"
    Taxon.REPTILE     -> "Reptile"
    Taxon.BATRACIEN   -> "Batracien"
    Taxon.POISSON     -> "Poisson"
    Taxon.INSECTE     -> "Insecte"
    Taxon.FONGE       -> "Champignon"
    Taxon.INVERTEBRES -> "Invertébré"
    Taxon.PLANTE      -> "Plante"
}
