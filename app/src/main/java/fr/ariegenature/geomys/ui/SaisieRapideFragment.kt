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
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.TaxRefLocal
import fr.ariegenature.geomys.databinding.FragmentSaisieRapideBinding
import fr.ariegenature.geomys.model.Denombrement
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.network.GeoNatureUpload
import fr.ariegenature.geomys.network.TaxRefStatut
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.SortieStore
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.ui.saisie.SpeechToTextHelper
import fr.ariegenature.geomys.ui.saisie.TaxRefLookupController
import fr.ariegenature.geomys.ui.saisie.TaxonSelector
import fr.ariegenature.geomys.ui.saisie.createSpeciesAutocompleteAdapter
import fr.ariegenature.geomys.ui.saisie.filtrerBoutonsGroupesNonVides
import fr.ariegenature.geomys.ui.saisie.taxonCouleur
import fr.ariegenature.geomys.ui.saisie.taxonIcon
import fr.ariegenature.geomys.location.LocationForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.core.graphics.drawable.toBitmap
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
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
    private var taxon: Taxon = Taxon.OISEAU  // remplacé par la valeur mémorisée au setupTaxonSelector
    private var especeDefaut = ""
    private var cdNomDefaut: Int? = null
    private var nombre = 1
    private var rechercheNomSci = false  // remplacé par la valeur mémorisée au setupAutocomplete
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

    /** Override du relevé édités via « Détails du relevé » (jeu de données + observateur).
     *  null = valeur par défaut (config / login). Communs à toutes les obs de la session mono. */
    private var idDatasetReleveSession: Int? = null
    private var observateursReleveIdsSession: List<Int> = emptyList()
    private var observateursReleveNomsSession: List<String> = emptyList()
    private var commentReleveSession: String = ""
    private var cdHabReleveSession: Int? = null
    private var habitatReleveLabelSession: String? = null
    private var dateDebutReleveSession: Long? = null
    private var dateFinReleveSession: Long? = null
    /** Type de regroupement (TYP_GRP) du relevé mono. "" = non renseigné. */
    private var typGrpReleveSession: String = ""
    /** Champs relevé supplémentaires pilotés par form_fields (clé = clé form_fields). */
    private var champsReleveExtraSession: Map<String, String> = emptyMap()

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

    /** Point manuellement posé par tap sur la carte. Null = utilise la position GPS du
     *  téléphone. Reset à chaque enregistrement réussi. */
    private var pointManuel: GeoPoint? = null
    /** Marker draggable matérialisant le [pointManuel] courant — null si pas de point posé. */
    private var markerPointManuel: Marker? = null
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
            // Voir TraceFragment : aiguille tournante uniquement en mode boussole actif,
            // sinon figée à 0 (carte et téléphone alignés nord en haut).
            if (carteSuitBoussole) {
                compass.post { compass.setAzimuth(-azimuth) }
                val map = _binding?.map ?: return
                map.post {
                    map.setMapOrientation(-azimuth)
                    map.invalidate()
                }
            } else {
                compass.post { compass.setAzimuth(0f) }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
    private var fondCarte = FondCarte.OSM

    // Zoom/centre carte mémorisés entre deux affichages de la vue (la vue est détruite/recréée
    // quand on va éditer Caractérisation/Dénombrement). Sans ça, setupMap rezoomait à 15 au retour
    // → carte « trop large ». -1 = pas encore mémorisé (1er affichage → zoom par défaut).
    private var savedMapZoom: Double = -1.0
    private var savedMapCenter: org.osmdroid.util.GeoPoint? = null

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
        // La popup système peut revenir après destruction de la vue → garde isAdded.
        if (!isAdded || !::speech.isInitialized) return@registerForActivityResult
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
        // Restaure les overrides « Détails du relevé » après rotation / process-death (sinon le
        // jeu de données / observateur choisis seraient silencieusement réinitialisés).
        savedInstanceState?.let { st ->
            if (st.containsKey("rs_ds")) idDatasetReleveSession = st.getInt("rs_ds")
            st.getIntArray("rs_obs")?.let { observateursReleveIdsSession = it.toList() }
            st.getStringArrayList("rs_obsnom")?.let { observateursReleveNomsSession = it.toList() }
            st.getString("rs_comment")?.let { commentReleveSession = it }
            if (st.containsKey("rs_cdhab")) cdHabReleveSession = st.getInt("rs_cdhab")
            st.getString("rs_habnom")?.let { habitatReleveLabelSession = it }
            if (st.containsKey("rs_datedeb")) dateDebutReleveSession = st.getLong("rs_datedeb")
            if (st.containsKey("rs_datefin")) dateFinReleveSession = st.getLong("rs_datefin")
            st.getString("rs_add")?.let { json ->
                additionalFieldsReleve = try {
                    Gson().fromJson(json, object : TypeToken<Map<String, String>>() {}.type) ?: additionalFieldsReleve
                } catch (_: Exception) { additionalFieldsReleve }
            }
            st.getString("rs_extra")?.let { json ->
                champsReleveExtraSession = try {
                    Gson().fromJson(json, object : TypeToken<Map<String, String>>() {}.type) ?: champsReleveExtraSession
                } catch (_: Exception) { champsReleveExtraSession }
            }
        }

        // Champs additionnels niveau RELEVE saisis une seule fois en amont via DetailsReleveFragment
        // (écran intercalé à l'entrée de la saisie mono-taxons). Ces valeurs deviennent le défaut
        // de session : chaque obs enregistrée — un relevé séparé côté serveur — les porte à l'identique.
        arguments?.getString("addReleveJson")?.let { json ->
            val mapType = object : TypeToken<Map<String, String>>() {}.type
            additionalFieldsReleve = try {
                Gson().fromJson<Map<String, String>?>(json, mapType) ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
        }

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

        // Écran d'entrée du flux mono-taxons : mémorise le type pour que tous les écrans de
        // saisie en aval affichent le même bandeau "🏠 › Saisie mono-taxons".
        traceViewModel.typeSaisieLabel = getString(R.string.saisie_mono_taxons)
        binding.bandeauSaisie.root.applyStatusBarMargin()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), traceViewModel.typeSaisieLabel)

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
        fondCarte = chargerFondCarte(requireContext(), fondCarte)
        binding.map.setTileSource(tileSourcePour(fondCarte))
        binding.map.setMultiTouchControls(true)
        // Restaure le zoom/centre mémorisés (retour d'édition Caractérisation/Dénombrement) ; au 1er
        // affichage seulement, zoom par défaut 15 + centre sur la position courante.
        binding.map.controller.setZoom(if (savedMapZoom > 0) savedMapZoom else 15.0)
        binding.map.controller.setCenter(
            savedMapCenter
                ?: traceViewModel.locationTracker.position.value?.let { GeoPoint(it.latitude, it.longitude) }
                ?: GeoPoint(46.5, 2.5)
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

        // Tap = pose un point manuel sous le doigt. Le clic "+" l'utilisera comme
        // coord d'enregistrement à la place de la position GPS. Drag du marker = ajuste.
        binding.map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (!modeActif) return false
                poserPointManuel(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))

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
            enregistrerFondCarte(requireContext(), fondCarte)
        }

        binding.compass.setOnClickListener {
            carteSuitBoussole = !carteSuitBoussole
            binding.compass.setActif(carteSuitBoussole)
            if (!carteSuitBoussole) {
                // Retour au mode figé : carte ET boussole reviennent à 0.
                binding.map.setMapOrientation(0f)
                binding.map.invalidate()
                binding.compass.setAzimuth(0f)
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
        // Restaure le dernier groupe choisi par l'utilisateur (toutes saisies confondues).
        // Si le groupe mémorisé n'est plus disponible (boutons filtrés selon la liste de
        // taxons configurée), on retombe sur le 1er groupe dispo.
        fr.ariegenature.geomys.ui.saisie.PreferencesSaisie.dernierTaxon(requireContext())
            ?.let { taxon = it }
        if (taxon !in boutons.keys) taxon = boutons.keys.firstOrNull() ?: taxon
        taxonSelector = TaxonSelector(
            requireContext(),
            boutons,
            initial = taxon,
        ) { t ->
            taxon = t
            fr.ariegenature.geomys.ui.saisie.PreferencesSaisie.memoiserTaxon(requireContext(), t)
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

        // Restaure l'état du switch "noms scientifiques" depuis la dernière session.
        rechercheNomSci = fr.ariegenature.geomys.ui.saisie.PreferencesSaisie
            .rechercheNomSci(requireContext())
        binding.switchNomSci.isChecked = rechercheNomSci
        binding.switchNomSci.setOnCheckedChangeListener { _, isChecked ->
            rechercheNomSci = isChecked
            fr.ariegenature.geomys.ui.saisie.PreferencesSaisie
                .memoiserNomSci(requireContext(), isChecked)
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

        // Bouton "Détails du relevé" : TOUJOURS visible — le dialog permet d'éditer le jeu de
        // données et l'observateur du relevé (+ les éventuels champs additionnels OCCTAX_RELEVE).
        // Les valeurs sont communes à toutes les obs de la session.
        binding.btnDetailsReleve.visibility = View.VISIBLE
        binding.btnDetailsReleve.setOnClickListener {
            val defsReleve = fr.ariegenature.geomys.ui.saisie.AdditionalFieldsRenderer
                .fromJson(gnConfig.additionalFieldsOcctaxJsonActif)
                .filter { it.appliqueA(fr.ariegenature.geomys.network.AdditionalFieldsObject.RELEVE) }
                .filter { it.visiblePour(gnConfig.idDataset.toIntOrNull(), emptyList()) }
            val datasets = datasetsPourDetailsReleve(gnConfig)
            val observateurs = observateursPourDetailsReleve(gnConfig)
            val idDsInitial = idDatasetReleveSession ?: gnConfig.idDataset.toIntOrNull()
            val idsObsInitial = observateursReleveIdsSession.ifEmpty {
                listOfNotNull(
                    gnConfig.observateurDefautId.toIntOrNull()
                        ?: gnConfig.idRoleUtilisateur.takeIf { it > 0 }
                )
            }
            val nomsObsInitial = observateursReleveNomsSession.ifEmpty {
                listOfNotNull(
                    gnConfig.observateurDefautNom.ifEmpty { gnConfig.nomUtilisateur.ifEmpty { gnConfig.login } }
                        .takeIf { it.isNotBlank() }
                )
            }
            val nomDsInitial = gnConfig.nomDataset.takeIf { it.isNotEmpty() }
            ouvrirDialogDetailsReleve(
                requireContext(), emptyList(), datasets, idDsInitial, nomDsInitial,
                observateurs, idsObsInitial, nomsObsInitial, defsReleve, additionalFieldsReleve,
                gnConfig.settingsOcctaxJson, gnConfig.formFieldsJson, typGrpReleveSession, commentReleveSession,
                dateDebutReleveSession, dateFinReleveSession, gnConfig.heuresVisibles, gnConfig.dateFinVisible,
                cdHabReleveSession, habitatReleveLabelSession,
                champsReleveExtraSession,
                viewLifecycleOwner.lifecycleScope,
                { terme -> fr.ariegenature.geomys.network.HabitatService.rechercher(gnConfig.urlServeur, terme) },
            ) { res ->
                idDatasetReleveSession = res.idDataset
                observateursReleveIdsSession = res.idsObservateurs
                observateursReleveNomsSession = res.nomsObservateurs
                additionalFieldsReleve = res.additionnels
                commentReleveSession = res.comment
                cdHabReleveSession = res.cdHab
                habitatReleveLabelSession = res.habitatLabel
                dateDebutReleveSession = res.dateDebut
                dateFinReleveSession = res.dateFin
                typGrpReleveSession = res.typGrp
                champsReleveExtraSession = res.champsExtra
            }
        }

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

    /** Pose ou déplace le marker manuel à [p]. Le clic "+" prendra ces coords la prochaine
     *  fois. Drag du marker = ajuste la position en live. */
    private fun poserPointManuel(p: GeoPoint) {
        pointManuel = p
        val existant = markerPointManuel
        if (existant != null) {
            existant.position = p
            binding.map.invalidate()
            return
        }
        val nouveau = Marker(binding.map).apply {
            position = p
            icon = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.ic_pin_drop
            )
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = true
            title = "Position de l'observation"
            // Désactive l'InfoWindow par défaut — on n'a pas besoin de bulle, et certaines
            // versions osmdroid l'ouvrent au tap court avant que le drag long-press démarre,
            // ce qui interfère avec le geste.
            setInfoWindow(null)
            // Single tap = ne rien faire ET consume l'event, pour qu'il ne descende pas
            // au MapEventsOverlay et ne pose pas un nouveau point sur place.
            setOnMarkerClickListener { _, _ -> true }
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) {
                    pointManuel = marker.position
                }
                override fun onMarkerDragEnd(marker: Marker) { pointManuel = marker.position }
                override fun onMarkerDragStart(marker: Marker) {}
            })
        }
        binding.map.overlays.add(nouveau)
        markerPointManuel = nouveau
        binding.map.invalidate()
    }

    /** Retire le marker manuel et vide la coord stockée — prochaine saisie revient au GPS. */
    private fun effacerPointManuel() {
        markerPointManuel?.let { binding.map.overlays.remove(it) }
        markerPointManuel = null
        pointManuel = null
        binding.map.invalidate()
    }

    private fun enregistrerIci() {
        val loc = traceViewModel.locationTracker.position.value
        val lat: Double
        val lon: Double

        val manuel = pointManuel
        if (manuel != null) {
            // Priorité au point tappé manuellement par l'utilisateur.
            lat = manuel.latitude
            lon = manuel.longitude
        } else if (loc != null) {
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
            // Session vide (écran relevé sauté + « Détails » non ouvert) ⇒ valeurs par défaut serveur.
            additionalFieldsReleve      = additionalFieldsReleve.ifEmpty {
                fr.ariegenature.geomys.ui.saisie.AdditionalFieldsRenderer
                    .defautsChampsReleve(gnConfig.additionalFieldsOcctaxJsonActif, gnConfig.idDataset.toIntOrNull())
            },
            idDatasetReleve             = idDatasetReleveSession,
            typGrpReleve                = typGrpReleveSession.ifEmpty { null },
            champsReleveExtra           = champsReleveExtraSession,
            observateursReleveIds       = observateursReleveIdsSession,
            observateursReleveNoms      = observateursReleveNomsSession,
            commentReleve               = commentReleveSession.ifEmpty { null },
            cdHabReleve                 = cdHabReleveSession,
            habitatReleveLabel          = habitatReleveLabelSession,
            dateDebutReleve             = dateDebutReleveSession,
            dateFinReleve               = dateFinReleveSession,
            additionalFieldsOccurrence  = additionalFieldsOccurrence,
            denombrementsAdditionnels   = denombrementsAdditionnels,
        )
        traceViewModel.ajouterObservation(obs)
        derniereObsId = obs.id
        compteurSession++
        updateCompteur()
        montrerConfirmation(obs)
        vibrer()
        // Reset du point manuel : la prochaine observation repart sur la position GPS,
        // sauf si l'utilisateur retap explicitement sur la carte.
        effacerPointManuel()
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
        binding.bandeauPositionnement.visibility = if (modeActif) View.VISIBLE else View.GONE
        // Plus de réticule en saisie mono-taxons : le clic "+" prend la position GPS par
        // défaut, et un tap sur la carte permet de poser un point manuel à un endroit
        // précis (matérialisé par un marker "goutte").
        binding.reticule.visibility      = View.GONE
        // La visibilité du compteur dépend AUSSI du nombre d'obs (cf updateCompteur) :
        // on délègue plutôt que de la pré-positionner ici.
        updateCompteur()
        // Carte et stack de contrôles (GPS / boussole / fond) cachés tant qu'on n'a pas
        // démarré la saisie. Le wrapper ll_carte_controles porte la visibilité du groupe ;
        // les trois boutons à l'intérieur héritent. La boussole reste pilotée individuellement
        // pour son état actif/inactif (rotation map ou non).
        binding.map.visibility           = if (modeActif) View.VISIBLE else View.GONE
        binding.llCarteControles.visibility = if (modeActif) View.VISIBLE else View.GONE
        // Coche « Terminer » (haut droite) : utile seulement sur la carte (mode actif). En
        // mode config (écran de sélection du taxon) elle ne sert à rien — on quitte par le
        // bouton retour système — donc on la masque pour ne pas surcharger l'écran.
        binding.btnRetour.visibility     = if (modeActif) View.VISIBLE else View.GONE
        if (modeActif) binding.compass.setActif(carteSuitBoussole)
        if (!modeActif) {
            snackJob?.cancel()
            binding.snackConfirmation.visibility = View.GONE
        }
    }

    private fun updateCompteur() {
        val n = compteurSession
        binding.tvCompteur.text = "$n obs. enregistrée${if (n > 1) "s" else ""}"
        // Bandeau masqué tant qu'aucune observation n'a été enregistrée — évite un libellé
        // "0 obs. enregistrée" inutile en haut de la carte au démarrage de la saisie.
        binding.tvCompteur.visibility = if (modeActif && n > 0) View.VISIBLE else View.GONE
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
            val existant = observationMarkers[obs.id]
            if (existant != null) {
                // L'observation peut avoir été repositionnée par ailleurs (drag d'un autre
                // écran, edition…) — on synchronise la position si elle a changé.
                if (existant.position.latitude != obs.latitude || existant.position.longitude != obs.longitude) {
                    existant.position = GeoPoint(obs.latitude, obs.longitude)
                }
                return@forEach
            }
            val marker = Marker(binding.map).apply {
                position = GeoPoint(obs.latitude, obs.longitude)
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_bird_marker)
                title = obs.espece
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                snippet = fmt.format(Date(obs.date)) + if (obs.nombre > 1) " · ${obs.nombre} ind." else ""
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                // Observations validées repositionnables : drag pour ajuster, la nouvelle
                // position est persistée immédiatement dans le ViewModel.
                isDraggable = true
                setOnMarkerClickListener { _, _ ->
                    val toutes = traceViewModel.observations.value ?: emptyList()
                    montrerListeEspeces(obsProches(obs, toutes).ifEmpty { listOf(obs) })
                    true
                }
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) {}
                    override fun onMarkerDrag(marker: Marker) {}
                    override fun onMarkerDragEnd(marker: Marker) {
                        traceViewModel.mettreAJourObservationPosition(
                            obs.id, marker.position.latitude, marker.position.longitude,
                        )
                    }
                })
            }
            observationMarkers[obs.id] = marker
            binding.map.overlays.add(marker)
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
        // btnRetour (coche verte) reste en haut → status bar margin. Le stack
        // ll_carte_controles est en bas → nav bar margin pour ne pas chevaucher la barre
        // de navigation gestuelle. Les paddings/marges XML d'origine sont conservés.
        binding.btnRetour.applyStatusBarMargin()
        // Même décalage qu'en multi-taxons (status bar en plus du paddingTop XML) pour que le
        // bandeau de positionnement soit à la même hauteur.
        binding.bandeauPositionnement.applyStatusBarMargin()
        binding.llCarteControles.applyNavBarMargin()
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
        // Mémorise zoom + centre pour les restaurer si la vue est recréée (retour d'un sous-écran
        // d'édition) — sinon la carte se réaffiche « trop large » (zoom fixe 15).
        savedMapZoom = binding.map.zoomLevelDouble
        savedMapCenter = binding.map.mapCenter.let { org.osmdroid.util.GeoPoint(it.latitude, it.longitude) }
        binding.map.onPause()
        locationOverlay?.disableMyLocation()
        sensorManager?.unregisterListener(compassListener)
        gravityReady = false
        geomagneticReady = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Overrides « Détails du relevé » — survivent à la rotation / process-death.
        idDatasetReleveSession?.let { outState.putInt("rs_ds", it) }
        if (observateursReleveIdsSession.isNotEmpty())
            outState.putIntArray("rs_obs", observateursReleveIdsSession.toIntArray())
        if (observateursReleveNomsSession.isNotEmpty())
            outState.putStringArrayList("rs_obsnom", ArrayList(observateursReleveNomsSession))
        if (commentReleveSession.isNotEmpty()) outState.putString("rs_comment", commentReleveSession)
        cdHabReleveSession?.let { outState.putInt("rs_cdhab", it) }
        habitatReleveLabelSession?.let { outState.putString("rs_habnom", it) }
        dateDebutReleveSession?.let { outState.putLong("rs_datedeb", it) }
        dateFinReleveSession?.let { outState.putLong("rs_datefin", it) }
        if (additionalFieldsReleve.isNotEmpty()) outState.putString("rs_add", Gson().toJson(additionalFieldsReleve))
        if (champsReleveExtraSession.isNotEmpty()) outState.putString("rs_extra", Gson().toJson(champsReleveExtraSession))
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
        // Plus de « Supprimer la sortie » ici : la suppression reste possible depuis « Mes saisies ».
        options.add(getString(R.string.continuer_sortie))

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.terminer_sortie)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.enregistrer_quitter) -> terminerSaisie(envoyerGN = false)
                    getString(R.string.enregistrer_envoyer_gn) -> terminerSaisie(envoyerGN = true)
                    // « Continuer la saisie » : ne rien faire, fermer le dialog
                }
            }.show()
    }

    private fun terminerSaisie(envoyerGN: Boolean) {
        LocationForegroundService.stop(requireContext())
        // La saisie a déjà été auto-sauvée au fil de l'eau sous `sortieEnEditionId` : on met
        // à jour cette même entrée (préserve id + date + place dans la liste) au lieu d'en
        // créer une nouvelle, sinon on dupliquerait le brouillon déjà présent dans le store.
        val id = traceViewModel.sortieEnEditionId
        val existante = id?.let { sid -> sortieStore.charger().firstOrNull { it.id == sid } }
        val sortie = (existante ?: Sortie()).copy(
            observations = traceViewModel.observations.value?.toList() ?: emptyList(),
        )
        if (sortie.observations.isNotEmpty()) {
            if (id != null) sortieStore.remplacer(id, sortie) else sortieStore.ajouter(sortie)
        } else {
            id?.let { sortieStore.supprimer(it) }
        }
        traceViewModel.reinitialiser()

        if (envoyerGN) envoyerVersGeoNature(sortie)
        else findNavController().navigateUp()
    }

    private fun envoyerVersGeoNature(sortie: Sortie) {
        envoiJob = viewLifecycleOwner.lifecycleScope.launch {
            // Envoi + mise à jour du store factorisés (cf. envoyerSortieVersGeoNature).
            val res = fr.ariegenature.geomys.network.envoyerSortieVersGeoNature(
                sortie, sortieStore, gnConfig)
            showResult(res.message, res.succes)
        }
    }

    private fun showResult(msg: String, success: Boolean) {
        if (!isAdded) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(if (success) "GeoNature" else getString(R.string.erreur_envoi))
            .setMessage(msg)
            .setPositiveButton("OK") { _, _ -> if (isAdded) findNavController().navigateUp() }
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
