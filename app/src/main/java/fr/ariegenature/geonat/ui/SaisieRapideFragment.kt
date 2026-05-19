package fr.ariegenature.geonat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.TaxRefLocal
import fr.ariegenature.geonat.databinding.FragmentSaisieRapideBinding
import fr.ariegenature.geonat.model.Denombrement
import fr.ariegenature.geonat.model.Observation
import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.model.Sortie
import fr.ariegenature.geonat.network.GeoNatureUpload
import fr.ariegenature.geonat.network.TaxRefStatut
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.SortieStore
import fr.ariegenature.geonat.store.TaxRefCache
import fr.ariegenature.geonat.ui.saisie.SpeechToTextHelper
import fr.ariegenature.geonat.ui.saisie.TaxRefLookupController
import fr.ariegenature.geonat.ui.saisie.TaxonSelector
import fr.ariegenature.geonat.ui.saisie.createSpeciesAutocompleteAdapter
import fr.ariegenature.geonat.ui.saisie.filtrerBoutonsGroupesNonVides
import fr.ariegenature.geonat.ui.saisie.taxonCouleur
import fr.ariegenature.geonat.ui.saisie.taxonIcon
import fr.ariegenature.geonat.location.LocationForegroundService
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
    private var cdNomManuel = ""

    private lateinit var taxonSelector: TaxonSelector
    private lateinit var speech: SpeechToTextHelper
    private lateinit var taxrefLookup: TaxRefLookupController

    // Détails par défaut — caractérisation
    private var statutObs = ""
    private var notes = ""
    private var techniqueObs = ""
    private var statutBio = ""
    private var etaBio = ""
    private var preuveExist = ""
    private var comportement = ""
    private var methDetermin = ""
    private var determinateur = ""
    private var additionalFieldsReleve: Map<String, String> = emptyMap()
    private var additionalFieldsOccurrence: Map<String, String> = emptyMap()

    // Détails par défaut — dénombrement (counting #0 + éventuels countings additionnels)
    private var sexe = ""
    private var stadeVie = ""
    private var objDenbr = ""
    private var typDenbr = ""
    private var nombreMax: Int? = null
    private var mediaUrisCounting0: List<String> = emptyList()
    private var additionalFieldsCounting0: Map<String, String> = emptyMap()
    private var denombrementsAdditionnels: List<Denombrement> = emptyList()

    // État
    private var modeActif = false
    private var compteurSession = 0
    private var suivrePosition = true
    private var derniereObsId: String? = null
    private var snackJob: Job? = null
    /** false (défaut) = carte figée nord en haut du téléphone.
     *  true = carte tournée par la boussole pour garder le nord en haut de l'écran. */
    private var carteSuitBoussole = false

    private var sensorManager: SensorManager? = null
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var gravityReady = false
    private var geomagneticReady = false

    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val azimuth: Float = when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val R = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(R, event.values)
                    val o = FloatArray(3)
                    SensorManager.getOrientation(R, o)
                    Math.toDegrees(o[0].toDouble()).toFloat()
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                    gravityReady = true
                    return
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                    geomagneticReady = true
                    if (!gravityReady) return
                    val R = FloatArray(9)
                    if (!SensorManager.getRotationMatrix(R, null, gravity, geomagnetic)) return
                    val o = FloatArray(3)
                    SensorManager.getOrientation(R, o)
                    Math.toDegrees(o[0].toDouble()).toFloat()
                }
                else -> return
            }
            val compass = _binding?.compass ?: return
            compass.post { compass.setAzimuth(-azimuth) }
            // Mode boussole : la carte compense la rotation du téléphone — voir TraceFragment.
            if (carteSuitBoussole) {
                val map = _binding?.map ?: return
                map.post {
                    map.setMapOrientation(-azimuth)
                    map.invalidate()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
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
        if (granted) speech.demarrerEcoute()
        else Toast.makeText(requireContext(), "Permission micro refusée", Toast.LENGTH_SHORT).show()
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

        speech = SpeechToTextHelper(this, binding.tilEspece, binding.etEspece, micPermissionLauncher)
        taxrefLookup = TaxRefLookupController(
            scope = viewLifecycleOwner.lifecycleScope,
            progress = binding.taxrefProgress,
            tvStatut = binding.tvTaxrefStatut,
            taxonProvider = { taxon },
            configProvider = { gnConfig },
            onChange = { s -> taxRefStatut = s; updateDemarrerState() },
        )

        setupMap()
        setupTaxonSelector()
        setupAutocomplete()
        setupControls()
        observerViewModel()
        demanderPermissions()
        applyWindowInsets()

        updateModeUI()

        // Le bouton retour système doit traverser showConfirmTerminer() pour libérer
        // le GPS comme la croix retour — sans ça, le foreground service reste actif
        // après un navigateUp() implicite.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { showConfirmTerminer() }
            }
        )
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

        binding.compass.setOnClickListener {
            carteSuitBoussole = !carteSuitBoussole
            binding.compass.setActif(carteSuitBoussole)
            if (!carteSuitBoussole) {
                // Retour au mode figé : on remet la carte nord en haut.
                binding.map.setMapOrientation(0f)
                binding.map.invalidate()
            }
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
        val boutons = filtrerBoutonsGroupesNonVides(
            mapOf(
                Taxon.OISEAU      to binding.btnTaxonOiseau,
                Taxon.MAMMIFERE   to binding.btnTaxonMammifere,
                Taxon.REPTILE     to binding.btnTaxonReptile,
                Taxon.BATRACIEN   to binding.btnTaxonBatracien,
                Taxon.POISSON     to binding.btnTaxonPoisson,
                Taxon.INSECTE     to binding.btnTaxonInsecte,
                Taxon.FONGE       to binding.btnTaxonFonge,
                Taxon.MOLLUSQUE   to binding.btnTaxonMollusque,
                Taxon.INVERTEBRES to binding.btnTaxonInvertebres,
                Taxon.PLANTE      to binding.btnTaxonPlante,
            ),
            idListeFiltre = gnConfig.taxaListeId.trim().toIntOrNull(),
        )
        if (taxon !in boutons.keys) taxon = boutons.keys.firstOrNull() ?: taxon
        taxonSelector = TaxonSelector(
            requireContext(),
            boutons,
            initial = taxon,
        ) { t ->
            taxon = t
            binding.etEspece.setText("")
            taxRefStatut = null
            taxrefLookup.reset()
            refreshAutocompleteAdapter()
            updateEspeceHint()
        }
        taxonSelector.init()
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
            taxrefLookup.reset()
            refreshAutocompleteAdapter()
            updateEspeceHint()
        }

        binding.tilEspece.setEndIconOnClickListener { speech.lancer() }

        binding.btnDemarrer.isEnabled = false
        binding.etEspece.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Désactivé immédiatement quand le texte change ; réactivé après match TaxRef trouvé.
                updateDemarrerState()
                taxrefLookup.rechercher(s?.toString() ?: "")
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
                TaxRefLocal.getSuggestionsAutocomplete(
                    taxon,
                    rechercheNomSci,
                    idListeFiltre = gnConfig.taxaListeId.trim().toIntOrNull(),
                )
            }
            if (!isAdded || _binding == null) return@launch
            val adapter = createSpeciesAutocompleteAdapter(requireContext(), suggestions)
            binding.etEspece.setAdapter(adapter)
            // Race possible : si l'utilisateur a tapé avant la fin du scan asynchrone,
            // AutoCompleteTextView a déclenché le filtre sur un adapter encore vide et
            // ne le rejoue pas tout seul après setAdapter — on relance manuellement.
            val current = binding.etEspece.text?.toString().orEmpty()
            if (current.length >= binding.etEspece.threshold && binding.etEspece.hasFocus()) {
                adapter.filter.filter(current) { count ->
                    if (count > 0 && _binding != null && binding.etEspece.hasFocus()) {
                        binding.etEspece.showDropDown()
                    }
                }
            }
        }
    }

    private fun updateEspeceHint() {
        val nomGroupe = when (taxon) {
            Taxon.OISEAU      -> "Oiseaux"
            Taxon.MAMMIFERE   -> "Mammifères"
            Taxon.REPTILE     -> "Reptiles"
            Taxon.BATRACIEN   -> "Amphibiens"
            Taxon.POISSON     -> "Poissons"
            Taxon.INSECTE     -> "Insectes"
            Taxon.FONGE       -> "Champignons"
            Taxon.MOLLUSQUE   -> "Mollusques"
            Taxon.INVERTEBRES -> "Autres invertébrés"
            Taxon.PLANTE      -> "Plantes"
        }
        binding.tilEspece.hint = if (rechercheNomSci) "Nom scientifique ($nomGroupe)"
                                 else                "Espèce ($nomGroupe)"
    }


    // ─── Contrôles principaux ─────────────────────────────────────────────────

    private fun setupControls() {
        // Croix en haut à gauche : même comportement que le bouton "Terminer".
        // Si aucune obs n'a encore été saisie, showConfirmTerminer() retombe sur
        // navigateUp() — on quitte directement sans dialog parasite.
        binding.btnRetour.setOnClickListener { showConfirmTerminer() }

        binding.btnCaracterisationDefaut.setOnClickListener { ouvrirCaracterisationDefaut() }
        binding.btnDenombrementDefaut.setOnClickListener { ouvrirDenombrementDefaut() }

        binding.btnDemarrer.setOnClickListener { demarrer() }
        binding.btnAnnulerSaisie.setOnClickListener {
            // Annuler avant de démarrer = on n'a rien saisi, mais le GPS a été lancé à
            // l'ouverture de l'écran : on le libère explicitement.
            LocationForegroundService.stop(requireContext())
            findNavController().navigateUp()
        }

        binding.btnModifierParams.setOnClickListener {
            modeActif = false
            updateModeUI()
        }

        binding.btnEnregistrerIci.setOnClickListener { enregistrerIci() }


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
            // ─── Retour de CaracterisationFragment ───
            getLiveData<String>("statutObs").observe(viewLifecycleOwner)     { statutObs = it; updateDetailsIndicator() }
            getLiveData<String>("techniqueObs").observe(viewLifecycleOwner)  { techniqueObs = it; updateDetailsIndicator() }
            getLiveData<String>("etaBio").observe(viewLifecycleOwner)        { etaBio = it; updateDetailsIndicator() }
            getLiveData<String>("comportement").observe(viewLifecycleOwner)  { comportement = it; updateDetailsIndicator() }
            getLiveData<String>("statutBio").observe(viewLifecycleOwner)     { statutBio = it; updateDetailsIndicator() }
            getLiveData<String>("methDetermin").observe(viewLifecycleOwner)  { methDetermin = it; updateDetailsIndicator() }
            getLiveData<String>("preuveExist").observe(viewLifecycleOwner)   { preuveExist = it; updateDetailsIndicator() }
            getLiveData<String>("determinateur").observe(viewLifecycleOwner) { determinateur = it }
            getLiveData<String>("notes").observe(viewLifecycleOwner)         { notes = it; updateDetailsIndicator() }
            val mapType = object : TypeToken<Map<String, String>>() {}.type
            getLiveData<String>("addReleveJson").observe(viewLifecycleOwner) { json ->
                additionalFieldsReleve = try {
                    Gson().fromJson<Map<String, String>?>(json, mapType) ?: emptyMap()
                } catch (_: Exception) { emptyMap() }
            }
            getLiveData<String>("addOccJson").observe(viewLifecycleOwner) { json ->
                additionalFieldsOccurrence = try {
                    Gson().fromJson<Map<String, String>?>(json, mapType) ?: emptyMap()
                } catch (_: Exception) { emptyMap() }
            }
            // ─── Retour de DenombrementFragment ───
            // counting #0 (= 1ʳᵉ entrée de la liste) → champs flat de la saisie rapide.
            // Les éventuels countings additionnels sont mémorisés pour être réémis tels quels
            // à chaque "Enregistrer ici".
            val listType = object : TypeToken<List<Denombrement>>() {}.type
            getLiveData<String>("denombrementsJson").observe(viewLifecycleOwner) { json ->
                val tous: List<Denombrement> = try {
                    Gson().fromJson<List<Denombrement>?>(json, listType) ?: emptyList()
                } catch (_: Exception) { emptyList() }
                if (tous.isNotEmpty()) {
                    val c0 = tous[0]
                    nombre = c0.nombreMin
                    nombreMax = if (c0.nombreMax != c0.nombreMin) c0.nombreMax else null
                    sexe = c0.sexe.orEmpty()
                    stadeVie = c0.stadeVie.orEmpty()
                    objDenbr = c0.objDenbr.orEmpty()
                    typDenbr = c0.typDenbr.orEmpty()
                    mediaUrisCounting0 = c0.mediaUris
                    additionalFieldsCounting0 = c0.additionalFields
                    denombrementsAdditionnels = tous.drop(1)
                } else {
                    denombrementsAdditionnels = emptyList()
                }
                updateDetailsIndicator()
            }
        }
    }

    private fun determinateurParDefaut(): String =
        gnConfig.observateurDefautNom.ifEmpty {
            gnConfig.nomUtilisateur.ifEmpty { gnConfig.login }
        }

    /** cd_nom courant — résolu via TaxRef si l'autocomplete a trouvé une correspondance,
     *  sinon parse le champ saisi à la main. Null tant qu'aucune espèce n'est identifiée. */
    private fun cdNomCourant(): Int? =
        (taxRefStatut as? TaxRefStatut.Trouve)?.cdNom ?: cdNomManuel.trim().toIntOrNull()

    /** group2_inpn courant — déduit du cd_nom si connu, sinon fallback par taxon
     *  (alignement avec la logique de SaisieObservationFragment). */
    private fun groupe2InpnDefaut(): String? =
        cdNomCourant()?.let { TaxRefCache.tousLesGroupes()[it.toString()] }
            ?: when (taxon) {
                Taxon.MAMMIFERE   -> "Mammifères"
                Taxon.REPTILE     -> "Reptiles"
                Taxon.BATRACIEN   -> "Amphibiens"
                Taxon.POISSON     -> "Poissons"
                Taxon.INSECTE     -> "Insectes"
                Taxon.PLANTE,
                Taxon.FONGE,
                Taxon.MOLLUSQUE,
                Taxon.INVERTEBRES -> null
                else              -> "Oiseaux"
            }

    private fun ouvrirCaracterisationDefaut() {
        val gson = Gson()
        val bundle = Bundle().apply {
            putString("taxon",               taxon.name)
            putString("groupe2Inpn",         groupe2InpnDefaut())
            putInt("cdNom",                  cdNomCourant() ?: -1)
            putString("statutObs",           statutObs)
            putString("techniqueObs",        techniqueObs)
            putString("etaBio",              etaBio)
            putString("comportement",        comportement)
            putString("statutBio",           statutBio)
            putString("methDetermin",        methDetermin)
            putString("determinateur",       determinateur)
            putString("determinateurDefaut", determinateurParDefaut())
            putString("preuveExist",         preuveExist)
            putString("notes",               notes)
            putString("addReleveJson",       gson.toJson(additionalFieldsReleve))
            putString("addOccJson",          gson.toJson(additionalFieldsOccurrence))
        }
        findNavController().navigate(R.id.action_saisieRapide_to_caracterisation, bundle)
    }

    private fun ouvrirDenombrementDefaut() {
        val counting0 = Denombrement(
            nombreMin = nombre,
            nombreMax = nombreMax ?: nombre,
            sexe = sexe.ifEmpty { null },
            stadeVie = stadeVie.ifEmpty { null },
            objDenbr = objDenbr.ifEmpty { null },
            typDenbr = typDenbr.ifEmpty { null },
            mediaUris = mediaUrisCounting0,
            additionalFields = additionalFieldsCounting0,
        )
        val tous = listOf(counting0) + denombrementsAdditionnels
        val bundle = Bundle().apply {
            putString("taxon",             taxon.name)
            putString("groupe2Inpn",       groupe2InpnDefaut())
            putInt("cdNom",                cdNomCourant() ?: -1)
            putString("espece",            binding.etEspece.text.toString().ifEmpty { taxon.nomGroupe() })
            putString("denombrementsJson", Gson().toJson(tous))
        }
        findNavController().navigate(R.id.action_saisieRapide_to_denombrement, bundle)
    }

    private fun updateDetailsIndicator() {
        val hasDetails = notes.isNotEmpty() || sexe.isNotEmpty() || stadeVie.isNotEmpty() ||
            techniqueObs.isNotEmpty() || statutBio.isNotEmpty() || etaBio.isNotEmpty() ||
            preuveExist.isNotEmpty() || objDenbr.isNotEmpty() || typDenbr.isNotEmpty() ||
            comportement.isNotEmpty() || methDetermin.isNotEmpty() || statutObs.isNotEmpty() ||
            denombrementsAdditionnels.isNotEmpty()
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
            nombreMax     = nombreMax,
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
            determinateur = determinateur.ifEmpty { null },
            statutObs     = statutObs.ifEmpty { null },
            mediaUrisCounting0          = mediaUrisCounting0,
            additionalFieldsCounting0   = additionalFieldsCounting0,
            additionalFieldsReleve      = additionalFieldsReleve,
            additionalFieldsOccurrence  = additionalFieldsOccurrence,
            denombrementsAdditionnels   = denombrementsAdditionnels,
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
        binding.compass.visibility       = if (modeActif) View.VISIBLE else View.GONE
        if (modeActif) binding.compass.setActif(carteSuitBoussole)
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
        binding.ivTaxonIconActif.setImageResource(taxonIcon(taxon))
        binding.ivTaxonIconActif.imageTintList =
            ColorStateList.valueOf(taxonCouleur(requireContext(), taxon))

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

    private fun applyWindowInsets() {
        // Carte plein écran, overlays et panneaux à l'écart des barres système.
        binding.btnRetour.applyStatusBarMargin()
        binding.btnFondCarte.applyStatusBarMargin()
        binding.compass.applyStatusBarMargin()
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
        // Boussole : préférence au rotation_vector, fallback accel + magnéto. Même
        // logique que TraceFragment.
        val sm = requireContext().getSystemService(SensorManager::class.java)
        sensorManager = sm
        val rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVec != null) {
            sm.registerListener(compassListener, rotVec, SensorManager.SENSOR_DELAY_UI)
        } else {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sm.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        locationOverlay?.disableMyLocation()
        sensorManager?.unregisterListener(compassListener)
        gravityReady = false
        geomagneticReady = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        snackJob?.cancel()
        taxrefLookup.cancel()
        envoiJob?.cancel()
        speech.destroy()
        observationMarkers.clear()
        _binding = null
    }

    // ─── Terminer la saisie ───────────────────────────────────────────────────

    private fun showConfirmTerminer() {
        val obs = traceViewModel.observations.value ?: emptyList()
        if (obs.isEmpty()) {
            // Pas d'obs en cours = on quitte sans dialog, mais on libère quand même
            // le foreground service GPS lancé à l'ouverture de l'écran.
            LocationForegroundService.stop(requireContext())
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
                val res = GeoNatureUpload.envoyer(sortie, gnConfig)
                sortieStore.marquerEnvoyee(sortie.id)
                var msg = "${res.nbCrees}/${res.nbTotal} relevé${if (res.nbTotal > 1) "s" else ""} créé${if (res.nbCrees > 1) "s" else ""}"
                if (res.premierIdReleve != null) msg += " (premier #${res.premierIdReleve})"
                if (res.mediasOK > 0) msg += "\n${res.mediasOK} média(s) uploadé(s)"
                if (res.mediasKO > 0) msg += "\n⚠ ${res.mediasKO} média(s) échoué(s) : ${res.mediaErreurMsg ?: ""}"
                if (res.relevesOrphelins.isNotEmpty()) msg += "\n⚠ ${res.relevesOrphelins.size} relevé(s) vide(s) côté GeoNature (id : ${res.relevesOrphelins.joinToString(", ")}), à supprimer manuellement."
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
    Taxon.MOLLUSQUE   -> "Mollusque"
    Taxon.INVERTEBRES -> "Autre invertébré"
    Taxon.PLANTE      -> "Plante"
}
