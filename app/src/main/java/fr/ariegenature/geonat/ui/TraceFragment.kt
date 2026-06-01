package fr.ariegenature.geonat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentTraceBinding
import fr.ariegenature.geonat.model.Observation
import fr.ariegenature.geonat.model.PointTrace
import fr.ariegenature.geonat.model.Sortie
import fr.ariegenature.geonat.location.LocationForegroundService
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.SortieStore
import fr.ariegenature.geonat.network.GeoNatureUpload
import androidx.core.graphics.drawable.toBitmap
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class TraceFragment : Fragment() {
    private var _binding: FragmentTraceBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()
    private lateinit var sortieStore: SortieStore
    private lateinit var gnConfig: GeoNatureConfig

    private var suivrePosition = true
    /** Vrai uniquement au 1er affichage après une reprise de sortie : demande à [setupMap] de
     *  cadrer la carte sur les obs/trace déjà saisies plutôt que sur la position courante. */
    private var doitCentrerSurObs = false
    private var modePositionnement = false
    /** IDs des obs à repositionner sur la carte. Vide = pas en mode reposition. Un seul ID pour
     *  une obs solo, N IDs pour un relevé multi-taxons (toutes déplacées au même point). */
    private var obsARepositionnerIds: List<String> = emptyList()
    private var fondCarte = FondCarte.OSM
    /** false (défaut) = carte figée nord en haut du téléphone.
     *  true = carte tournée par la boussole pour garder le nord en haut de l'écran. */
    private var carteSuitBoussole = false

    /** Mode de saisie de la géométrie d'un nouveau relevé. POINT par défaut. */
    private enum class ModeGeom { POINT, LINE, POLYGON }
    private var modeGeom: ModeGeom = ModeGeom.POINT

    /** Sommets accumulés en mode LINE/POLYGON (latitude, longitude). En mode POINT,
     *  reste vide et la position finale est lue depuis le centre de la carte. */
    private val sommetsCourants = mutableListOf<org.osmdroid.util.GeoPoint>()

    /** Overlay osmdroid affichant la géométrie en cours de tracé (polyline ou polygone). */
    private var overlayGeomEnCours: org.osmdroid.views.overlay.Overlay? = null

    /** Markers draggables marquant chaque sommet en cours de saisie. Indexés par leur
     *  position dans [sommetsCourants] pour retrouver et mettre à jour la coord au drag. */
    private val markersSommets = mutableListOf<Marker>()

    private var savedMapCenter: GeoPoint? = null
    private var savedMapZoom: Double = 18.0

    private var locationOverlay: MyLocationNewOverlay? = null
    private var tracePolyline: Polyline? = null
    private val observationMarkers = mutableMapOf<String, Marker>()
    // Géométries (ligne/polygone) des relevés repris en édition : forme redessinée +
    // sommets draggables (déplacement des points existants). Clé = identité stable du
    // relevé (releveId, ou id de l'obs si mono). Sinon on ne verrait que le centroïde.
    private val releveGeoms = mutableMapOf<String, GeomReleveEditable>()

    /** Forme éditable d'un relevé : ses sommets, l'overlay polyline/polygone et les markers
     *  draggables associés (1 par sommet). */
    private class GeomReleveEditable(
        val type: String,
        val ids: List<String>,
        val sommets: MutableList<GeoPoint>,
        var overlay: org.osmdroid.views.overlay.Overlay,
        val markers: MutableList<Marker> = mutableListOf(),
    )

    private var enregistrerTrace = true
    private var envoiEnCours = false
    private var currentJob: Job? = null

    private lateinit var sensorManager: SensorManager
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var gravityReady = false
    private var geomagneticReady = false
    private var useRotationVector = false

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
            // Mode boussole actif → l'aiguille tourne avec le téléphone ET la carte
            // compense pour garder le nord en haut. Mode figé → aiguille et carte
            // alignées sur le téléphone : nord en haut = aiguille au repos (0°).
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

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true || perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            demarrerLocalisation()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentTraceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sortieStore = SortieStore(requireContext())
        gnConfig = GeoNatureConfig(requireContext())

        // Reprise éventuelle d'une sortie sauvegardée (depuis l'onglet "À envoyer" → édition).
        // À faire AVANT setupMap pour que les overlays (parcours, obs) trouvent les données
        // déjà dans le ViewModel et le LocationTracker.
        val sortieIdReprise = arguments?.getString("sortieId")?.takeIf { it.isNotEmpty() }
        if (sortieIdReprise != null && traceViewModel.sortieEnEditionId != sortieIdReprise) {
            val sortie = sortieStore.charger().firstOrNull { it.id == sortieIdReprise }
            if (sortie != null) {
                traceViewModel.reprendreSortie(sortie)
                // Reprise depuis « Mes saisies » : on cadrera la carte sur les obs/trace déjà
                // saisies (pas sur la position courante du téléphone).
                doitCentrerSurObs = sortie.observations.isNotEmpty() || sortie.pointsParcours.isNotEmpty()
            }
        }

        enregistrerTrace = requireContext()
            .getSharedPreferences("GeoNat_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("enregistrer_trace", true)

        setupMap()
        setupControls()
        observerViewModel()
        demanderPermissions()
        applyWindowInsets()

        // Écran d'entrée du flux multi-taxons : mémorise le type pour que tous les écrans de
        // saisie en aval affichent le même bandeau "🏠 › Saisie multi-taxons".
        traceViewModel.typeSaisieLabel = getString(R.string.saisie_multi_taxons)
        binding.bandeauSaisie.root.applyStatusBarMargin()
        appliquerBandeauSaisie(binding.bandeauSaisie.root, findNavController(), traceViewModel.typeSaisieLabel)

        // Enchaînement multi-taxons : si on revient d'un relevé tout juste validé (coche),
        // SaisieObservationFragment a posé ce flag pour qu'on reparte directement en mode
        // positionnement, prêt à placer le relevé suivant — comme un clic sur le bouton « + ».
        val sv = findNavController().currentBackStackEntry?.savedStateHandle
        if (sv?.remove<Boolean>("demarrerSaisieSuivante") == true) {
            suivrePosition = false
            modePositionnement = true
            updateModePositionnement()
        }

        // On masque LE WRAPPER (case poids 1) et non le bouton seul : sinon la case vide
        // continue de prendre sa part de largeur et les 2 boutons restants ne se
        // recentreraient pas. Le wrapper en GONE → poids retiré → + et Liste se partagent
        // 50/50, centrés sur leur moitié.
        binding.wrapParcours.visibility = if (enregistrerTrace) View.VISIBLE else View.GONE

        // Le bouton retour système doit traverser le dialog « Terminer la sortie »
        // pour libérer le GPS comme le clic sur la croix — sinon le foreground service
        // continue de tourner après navigateUp().
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { showConfirmTerminer() }
            }
        )
    }

    private fun setupMap() {
        fondCarte = chargerFondCarte(requireContext(), fondCarte)
        binding.map.setTileSource(tileSourcePour(fondCarte))
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(savedMapZoom)
        if (doitCentrerSurObs) {
            // Reprise d'une sortie : on cadre sur les obs/trace existantes et on NE suit PAS
            // la position (sinon le 1er fix GPS recentrerait aussitôt sur le téléphone).
            doitCentrerSurObs = false
            suivrePosition = false
            binding.btnCentrer.setImageResource(R.drawable.ic_location_off)
            centrerSurObservations()
        } else {
            // Au retour sur la carte (après saisie), on recentre sur la position du téléphone
            // et on réactive le suivi pour que la carte (et donc le réticule, fixé au centre
            // de l'écran) suive le déplacement. Sans ça, la carte restait collée à l'endroit
            // de la dernière obs validée et `suivrePosition` était à false depuis le pan manuel.
            val positionCourante = traceViewModel.locationTracker.position.value
                ?.let { GeoPoint(it.latitude, it.longitude) }
            binding.map.controller.setCenter(positionCourante ?: savedMapCenter ?: GeoPoint(46.5, 2.5))
            suivrePosition = true
            binding.btnCentrer.setImageResource(R.drawable.ic_location_on)
        }
        // Restaure l'état du mode boussole (persisté sur l'instance fragment, mais
        // le binding est recréé). En mode figé, on remet la carte nord en haut.
        binding.compass.setActif(carteSuitBoussole)
        if (!carteSuitBoussole) binding.map.setMapOrientation(0f)

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), binding.map).apply {
            setPersonIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap())
            setPersonHotspot(10f, 10f)
            setDirectionArrow(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap(),
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap()
            )
        }
        binding.map.overlays.add(locationOverlay)

        // MapEvents : capture les taps utilisateur pour poser les sommets directement
        // sous le doigt en mode saisie nouvelle géométrie (sans réticule).
        binding.map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (!modePositionnement || obsARepositionnerIds.isNotEmpty()) return false
                ajouterOuRemplacerSommet(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))

        binding.map.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_MOVE
                && !modePositionnement && obsARepositionnerIds.isEmpty()) {
                suivrePosition = false
                binding.btnCentrer.setImageResource(R.drawable.ic_location_off)
            }
            if (event.action == android.view.MotionEvent.ACTION_UP) v.performClick()
            false
        }
    }

    /** Pose ou remplace un sommet selon le mode courant.
     *  - POINT : remplace le sommet unique (chaque tap = nouvel emplacement).
     *  - LINE / POLYGON : ajoute le sommet en fin de liste. */
    private fun ajouterOuRemplacerSommet(p: GeoPoint) {
        if (modeGeom == ModeGeom.POINT) {
            sommetsCourants.clear()
            sommetsCourants.add(p)
        } else {
            sommetsCourants.add(p)
        }
        rafraichirGeomEnCours()
    }

    private fun setupControls() {
        binding.btnParcours.setOnClickListener {
            val enCours = traceViewModel.locationTracker.estEnCours.value == true
            if (enCours) {
                traceViewModel.locationTracker.arreterParcours()
            } else {
                traceViewModel.locationTracker.demarrerParcours()
            }
        }

        binding.btnObservation.setOnClickListener {
            suivrePosition = false
            modePositionnement = true
            updateModePositionnement()
        }

        binding.btnObservation.setOnLongClickListener {
            findNavController().navigate(R.id.action_trace_to_saisie_rapide)
            true
        }

        binding.btnListe.setOnClickListener {
            findNavController().navigate(R.id.action_trace_to_observations)
        }

        binding.btnCentrer.setOnClickListener {
            suivrePosition = true
            binding.btnCentrer.setImageResource(R.drawable.ic_location_on)
            traceViewModel.locationTracker.position.value?.let {
                binding.map.controller.animateTo(GeoPoint(it.latitude, it.longitude))
            }
        }

        binding.btnRetour.setOnClickListener {
            // Même menu que le bouton terminer en bas — l'utilisateur choisit
            // explicitement entre enregistrer/supprimer/continuer.
            showConfirmTerminer()
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
                // Retour au mode figé : carte ET boussole reviennent à 0 (nord en haut
                // du téléphone). Sans le reset compass, l'aiguille reste tournée jusqu'au
                // prochain event sensor (effet visuel incorrect).
                binding.map.setMapOrientation(0f)
                binding.map.invalidate()
                binding.compass.setAzimuth(0f)
            }
        }

        binding.btnValiderPosition.setOnClickListener {
            val center = binding.map.mapCenter
            if (obsARepositionnerIds.isNotEmpty()) {
                obsARepositionnerIds.forEach { id ->
                    traceViewModel.mettreAJourObservationPosition(id, center.latitude, center.longitude)
                }
                obsARepositionnerIds = emptyList()
                updateModePositionnement()
            } else {
                // Construit la géométrie selon le mode actuel. Pour POINT : juste le centre.
                // Pour LINE/POLYGON : la liste des sommets accumulés (+ le dernier sommet
                // au centre, équivalent à un clic implicite final sur Ajouter sommet).
                val (geomType, coords, lat, lon) = construireGeometrieFinale(center)
                if (geomType == null) return@setOnClickListener  // pas assez de sommets
                val bundle = Bundle().apply {
                    putDouble("latitude", lat)
                    putDouble("longitude", lon)
                    putString("geometryType", geomType)
                    if (coords != null) putString("geometryCoordsJson", coords)
                }
                modePositionnement = false
                resetSaisieGeom()
                updateModePositionnement()
                // Si le serveur déclare des champs additionnels OCCTAX_RELEVE pour le dataset
                // courant, on intercale l'écran "Détails du relevé" : il valide les champs
                // required avant de passer à la saisie des espèces.
                val aDesChampsReleve = fr.ariegenature.geonat.ui.saisie.AdditionalFieldsRenderer
                    .fromJson(gnConfig.additionalFieldsOcctaxJson)
                    .filter { it.appliqueA(fr.ariegenature.geonat.network.AdditionalFieldsObject.RELEVE) }
                    .any { it.visiblePour(gnConfig.idDataset.toIntOrNull(), emptyList()) }
                val cible = if (aDesChampsReleve) R.id.action_trace_to_details_releve
                            else R.id.action_trace_to_saisie
                findNavController().navigate(cible, bundle)
            }
        }

        // Sélecteur de mode : un clic change le mode et réinitialise les sommets.
        binding.btnModePoint.setOnClickListener { changerMode(ModeGeom.POINT) }
        binding.btnModeLine.setOnClickListener { changerMode(ModeGeom.LINE) }
        binding.btnModePolygon.setOnClickListener { changerMode(ModeGeom.POLYGON) }

        binding.btnAjouterSommet.setOnClickListener {
            val center = binding.map.mapCenter
            sommetsCourants.add(org.osmdroid.util.GeoPoint(center.latitude, center.longitude))
            rafraichirGeomEnCours()
        }

        binding.btnAnnulerPosition.setOnClickListener {
            modePositionnement = false
            obsARepositionnerIds = emptyList()
            // Efface tout sommet/marker/polyline en cours — sinon les sommets restent
            // affichés sur la carte et seraient repris à la prochaine saisie.
            resetSaisieGeom()
            updateModePositionnement()
        }
    }

    /** Cadre la carte sur l'emprise des observations (et de la trace) de la sortie reprise.
     *  Posté après le layout de la carte car [org.osmdroid.views.MapView.zoomToBoundingBox]
     *  a besoin des dimensions réelles de la vue. Un seul point → simple centrage + zoom. */
    private fun centrerSurObservations() {
        // Points valides uniquement : on écarte les (0,0) (obs/trace sans position fixée),
        // sinon l'emprise s'étirerait jusqu'au golfe de Guinée.
        val points = mutableListOf<GeoPoint>()
        traceViewModel.observations.value?.forEach {
            if (it.latitude != 0.0 || it.longitude != 0.0) points.add(GeoPoint(it.latitude, it.longitude))
            // Inclut les sommets des géométries ligne/polygone pour cadrer sur la forme
            // entière (sinon on cadre seulement sur le centroïde → polygone hors écran).
            points.addAll(parseCoordsGeom(it.geometryCoordsJson))
        }
        traceViewModel.locationTracker.parcours.value?.forEach {
            if (it.latitude != 0.0 || it.longitude != 0.0) points.add(GeoPoint(it.latitude, it.longitude))
        }
        if (points.isEmpty()) return
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        binding.map.post {
            if (_binding == null) return@post
            // Emprise quasi nulle (un seul point distinct — cas fréquent en multi-taxons où
            // toutes les espèces partagent le même point) → centrage simple. Sinon
            // zoomToBoundingBox sur une boîte d'aire nulle calcule un zoom aberrant et
            // projette la carte n'importe où (Alaska).
            val latSpan = maxLat - minLat
            val lonSpan = maxLon - minLon
            if (latSpan < 1e-4 && lonSpan < 1e-4) {
                binding.map.controller.setZoom(18.0)
                binding.map.controller.setCenter(GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2))
            } else {
                // Une ligne ~horizontale/verticale donne une boîte d'aire nulle (un axe
                // d'épaisseur ~0) → zoomToBoundingBox diverge. On garantit une épaisseur
                // minimale (~50 m) sur l'axe plat.
                val padLat = if (latSpan < 5e-4) 5e-4 else 0.0
                val padLon = if (lonSpan < 5e-4) 5e-4 else 0.0
                val box = org.osmdroid.util.BoundingBox(
                    maxLat + padLat, maxLon + padLon, minLat - padLat, minLon - padLon,
                )
                // Marge en pixels = hauteur du bandeau de boutons du bas (qui recouvre la
                // carte) + une marge de respiration, pour que la forme et ses extrémités
                // ne collent pas au bord du bandeau et restent bien dans la partie utile.
                val respiration = (32 * resources.displayMetrics.density).toInt()
                val border = (_binding?.panneauControle?.height ?: 0)
                    .coerceAtLeast((48 * resources.displayMetrics.density).toInt()) + respiration
                binding.map.zoomToBoundingBox(box, false, border)
            }
        }
    }

    private fun observerViewModel() {
        traceViewModel.locationTracker.position.observe(viewLifecycleOwner) { loc ->
            loc ?: return@observe
            if (suivrePosition) {
                binding.map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
            }
        }

        traceViewModel.locationTracker.estEnCours.observe(viewLifecycleOwner) { enCours ->
            updateParcoursButton(enCours)
            updateInfoBarre(enCours)
        }

        traceViewModel.locationTracker.parcours.observe(viewLifecycleOwner) { pts ->
            updatePolyline(pts)
            updateInfoBarre(traceViewModel.locationTracker.estEnCours.value == true)
        }

        traceViewModel.locationTracker.distanceTotale.observe(viewLifecycleOwner) { dist ->
            updateInfoBarre(traceViewModel.locationTracker.estEnCours.value == true)
        }

        traceViewModel.observations.observe(viewLifecycleOwner) { obs ->
            updateMarkers(obs)
            updateListeBadge(obs.size)
        }
    }

    private fun updateParcoursButton(enCours: Boolean) {
        binding.btnParcours.setImageResource(
            if (enCours) R.drawable.ic_stop else R.drawable.ic_play
        )
    }

    private fun updateInfoBarre(enCours: Boolean) {
        binding.infoBarre.visibility = if (enCours && enregistrerTrace) View.VISIBLE else View.GONE
        if (enCours && enregistrerTrace) {
            val dist = traceViewModel.locationTracker.distanceTotale.value ?: 0.0
            val nbObs = traceViewModel.observations.value?.size ?: 0
            binding.tvDistance.text = "%.0f m".format(dist)
            binding.tvNbObs.text = "$nbObs obs."
        }
    }

    private fun updateListeBadge(count: Int) {
        binding.listeBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.listeBadge.text = count.toString()
    }

    private fun updateModePositionnement() {
        val inMode = modePositionnement || obsARepositionnerIds.isNotEmpty()
        val saisieNouvelle = modePositionnement && obsARepositionnerIds.isEmpty()
        // En saisie nouvelle on utilise le doigt directement (tap pour poser, drag pour
        // ajuster) → on masque le réticule. Il reste utile en repositionnement d'une obs
        // existante (cible plus précise sur le centre).
        binding.reticule.visibility = if (inMode && !saisieNouvelle) View.VISIBLE else View.GONE
        binding.bandeauPositionnement.visibility = if (inMode) View.VISIBLE else View.GONE
        binding.panneauControle.visibility = if (inMode) View.GONE else View.VISIBLE
        binding.panneauValidationPosition.visibility = if (inMode) View.VISIBLE else View.GONE
        binding.llModeGeom.visibility = if (saisieNouvelle) View.VISIBLE else View.GONE
        // Le bouton "+ Sommet" est rendu inutile par la saisie au doigt — on le masque
        // toujours (les sommets se posent en tappant la carte).
        binding.btnAjouterSommet.visibility = View.GONE
        if (inMode) {
            binding.tvBandeauPositionnement.text = when {
                obsARepositionnerIds.isNotEmpty() -> getString(R.string.repositionner_observation)
                modeGeom == ModeGeom.LINE -> "Tap sur la carte pour poser chaque sommet (drag pour ajuster)"
                modeGeom == ModeGeom.POLYGON -> "Tap pour les sommets du polygone (drag pour ajuster)"
                else -> "Tap sur la carte pour positionner (drag pour ajuster)"
            }
            // Libellé du bouton de validation dépend du mode (et du repositionnement).
            binding.btnValiderPosition.text = when {
                obsARepositionnerIds.isNotEmpty() -> getString(R.string.valider_position)
                modeGeom == ModeGeom.LINE -> "Valider cette ligne"
                modeGeom == ModeGeom.POLYGON -> "Valider ce polygone"
                else -> "Valider ce point"
            }
            mettreEnEvidenceBoutonMode()
        }
    }

    /** Change le mode courant + réinitialise les sommets accumulés + reset rendu live. */
    private fun changerMode(nouveau: ModeGeom) {
        if (modeGeom == nouveau) return
        modeGeom = nouveau
        resetSaisieGeom()
        updateModePositionnement()
    }

    /** Met en surbrillance le bouton de mode actif. On teinte l'ovale `btn_map_bg`
     *  (au lieu de setBackgroundColor qui en faisait un carré) : actif = fond colorPrimary
     *  + icône blanche, inactif = fond blanc OPAQUE + icône colorPrimary (bien plus
     *  visible que l'ancien blanc semi-transparent). */
    private fun mettreEnEvidenceBoutonMode() {
        val primaire = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorPrimary)
        val blanc = android.graphics.Color.WHITE
        fun appliquer(btn: android.widget.ImageButton, estActif: Boolean) {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (estActif) primaire else blanc)
            btn.imageTintList = android.content.res.ColorStateList.valueOf(if (estActif) blanc else primaire)
        }
        appliquer(binding.btnModePoint, modeGeom == ModeGeom.POINT)
        appliquer(binding.btnModeLine, modeGeom == ModeGeom.LINE)
        appliquer(binding.btnModePolygon, modeGeom == ModeGeom.POLYGON)
    }

    /** Retire l'overlay et les markers de sommets, vide la liste. */
    private fun resetSaisieGeom() {
        overlayGeomEnCours?.let { binding.map.overlays.remove(it) }
        overlayGeomEnCours = null
        markersSommets.forEach { binding.map.overlays.remove(it) }
        markersSommets.clear()
        sommetsCourants.clear()
        binding.map.invalidate()
    }

    /** Met à jour l'overlay (polyline/polygone) ET les markers draggables des sommets. */
    private fun rafraichirGeomEnCours() {
        // Purge ancien rendu (overlay polyline/polygone + tous les markers de sommets).
        overlayGeomEnCours?.let { binding.map.overlays.remove(it) }
        overlayGeomEnCours = null
        markersSommets.forEach { binding.map.overlays.remove(it) }
        markersSommets.clear()

        // Polyline/Polygon visible dès 2 sommets pour la ligne, 2 pour le polygone aussi
        // (polygon avec 2 points = juste un segment, pas grave visuellement).
        if (sommetsCourants.size >= 2 && modeGeom != ModeGeom.POINT) {
            val overlay: org.osmdroid.views.overlay.Overlay = if (modeGeom == ModeGeom.LINE) {
                Polyline(binding.map).apply {
                    setPoints(sommetsCourants.toList())
                    outlinePaint.color = 0xCCFF3333.toInt()
                    outlinePaint.strokeWidth = 6f
                }
            } else {
                org.osmdroid.views.overlay.Polygon(binding.map).apply {
                    points = sommetsCourants.toList()
                    fillPaint.color = 0x55FF3333.toInt()
                    outlinePaint.color = 0xCCFF3333.toInt()
                    outlinePaint.strokeWidth = 4f
                }
            }
            binding.map.overlays.add(overlay)
            overlayGeomEnCours = overlay
        }

        // Markers draggables pour chaque sommet — un appui long démarre le drag,
        // onMarkerDragEnd met à jour la liste et redessine.
        sommetsCourants.forEachIndexed { idx, point ->
            val marker = Marker(binding.map).apply {
                position = point
                // Icône épurée en goutte (drop pin) — pointe vers le bas, ancrage en bas
                // au centre comme un pin classique.
                icon = androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), R.drawable.ic_pin_drop
                )
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                isDraggable = true
                title = "Sommet ${idx + 1}"
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker) {
                        // Live update : la liste suit le doigt pour que la polyline/polygon
                        // reste cohérente avec le marker pendant le drag.
                        val i = markersSommets.indexOf(marker)
                        if (i in sommetsCourants.indices) {
                            sommetsCourants[i] = marker.position
                            // Repeint juste l'overlay, sans recréer les markers (sinon
                            // le drag en cours saute).
                            redessinerOverlay()
                        }
                    }
                    override fun onMarkerDragEnd(marker: Marker) { /* déjà à jour */ }
                    override fun onMarkerDragStart(marker: Marker) {}
                })
            }
            binding.map.overlays.add(marker)
            markersSommets.add(marker)
        }
        binding.map.invalidate()
    }

    /** Redessine UNIQUEMENT l'overlay polyline/polygone (sans toucher aux markers).
     *  Appelé pendant le drag d'un sommet pour ne pas interrompre l'événement de drag. */
    private fun redessinerOverlay() {
        overlayGeomEnCours?.let { binding.map.overlays.remove(it) }
        overlayGeomEnCours = null
        if (sommetsCourants.size < 2 || modeGeom == ModeGeom.POINT) {
            binding.map.invalidate()
            return
        }
        val overlay: org.osmdroid.views.overlay.Overlay = if (modeGeom == ModeGeom.LINE) {
            Polyline(binding.map).apply {
                setPoints(sommetsCourants.toList())
                outlinePaint.color = 0xCCFF3333.toInt()
                outlinePaint.strokeWidth = 6f
            }
        } else {
            org.osmdroid.views.overlay.Polygon(binding.map).apply {
                points = sommetsCourants.toList()
                fillPaint.color = 0x55FF3333.toInt()
                outlinePaint.color = 0xCCFF3333.toInt()
                outlinePaint.strokeWidth = 4f
            }
        }
        // Insère l'overlay sous les markers (sinon il passe par-dessus le marker en cours
        // de drag et désaligne le rendu). overlays.add ajoute en fin → on l'insère avant
        // le premier marker pour qu'il soit dessiné en-dessous.
        val idx = binding.map.overlays.indexOfFirst { it in markersSommets }
        if (idx >= 0) binding.map.overlays.add(idx, overlay)
        else binding.map.overlays.add(overlay)
        overlayGeomEnCours = overlay
        binding.map.invalidate()
    }

    /** Construit la géométrie finale au moment de la validation. Lit la liste des sommets
     *  effectivement posés par l'utilisateur (tap + drag). En cas de mode POINT sans
     *  sommet, on tombe en fallback sur le centre de la carte (cas où l'utilisateur clique
     *  Valider sans avoir tappé). Retourne (null,…) si invalide. */
    private fun construireGeometrieFinale(
        center: org.osmdroid.api.IGeoPoint,
    ): GeomFinale {
        return when (modeGeom) {
            ModeGeom.POINT -> {
                val p = sommetsCourants.firstOrNull()
                    ?: org.osmdroid.util.GeoPoint(center.latitude, center.longitude)
                GeomFinale("Point", null, p.latitude, p.longitude)
            }
            ModeGeom.LINE, ModeGeom.POLYGON -> {
                val min = if (modeGeom == ModeGeom.LINE) 2 else 3
                if (sommetsCourants.size < min) {
                    android.widget.Toast.makeText(requireContext(),
                        "Au moins $min sommets requis", android.widget.Toast.LENGTH_SHORT).show()
                    return GeomFinale(null, null, 0.0, 0.0)
                }
                val type = if (modeGeom == ModeGeom.LINE) "LineString" else "Polygon"
                val coordsJson = com.google.gson.Gson().toJson(sommetsCourants.map {
                    doubleArrayOf(it.longitude, it.latitude)
                })
                val lat = sommetsCourants.map { it.latitude }.average()
                val lon = sommetsCourants.map { it.longitude }.average()
                GeomFinale(type, coordsJson, lat, lon)
            }
        }
    }

    private data class GeomFinale(
        val geomType: String?,
        val coordsJson: String?,
        val lat: Double,
        val lon: Double,
    )

    private fun updatePolyline(pts: List<PointTrace>) {
        tracePolyline?.let { binding.map.overlays.remove(it) }
        if (pts.size > 1) {
            tracePolyline = Polyline(binding.map).apply {
                setPoints(pts.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.color = 0xCC2196F3.toInt()
                outlinePaint.strokeWidth = 6f
                outlinePaint.strokeCap = Paint.Cap.ROUND
            }
            binding.map.overlays.add(tracePolyline)
        }
        binding.map.invalidate()
    }

    private fun updateMarkers(observations: List<Observation>) {
        val currentIds = observations.map { it.id }.toSet()
        val toRemove = observationMarkers.keys.filter { it !in currentIds }
        toRemove.forEach { id ->
            observationMarkers[id]?.let { binding.map.overlays.remove(it) }
            observationMarkers.remove(id)
        }
        observations.forEach { obs ->
            val aForme = (obs.geometryType == "Polygon" || obs.geometryType == "LineString") &&
                !obs.geometryCoordsJson.isNullOrEmpty()
            val existant = observationMarkers[obs.id]
            if (aForme) {
                // Relevé ligne/polygone : on n'affiche que la forme, pas le marker centroïde.
                if (existant != null) {
                    binding.map.overlays.remove(existant)
                    observationMarkers.remove(obs.id)
                }
            } else if (existant != null) {
                // Mise à jour de la position si elle a changé (déplacement de relevé).
                val pos = existant.position
                if (pos.latitude != obs.latitude || pos.longitude != obs.longitude) {
                    existant.position = GeoPoint(obs.latitude, obs.longitude)
                }
            } else {
                val marker = createObservationMarker(obs)
                observationMarkers[obs.id] = marker
                binding.map.overlays.add(marker)
            }
        }
        majGeometriesReleves(observations)
        binding.map.invalidate()
    }

    /** Identité stable d'un relevé (insensible aux changements de sommets). */
    private fun cleReleve(obs: Observation): String =
        obs.releveId?.takeIf { it.isNotEmpty() } ?: obs.id

    /** Redessine + rend draggables les géométries ligne/polygone des relevés repris.
     *  Idempotent : si la forme existe déjà (ex. retour de notre propre maj après un
     *  drag), on resynchronise les positions sans tout recréer (évite flicker/interruption). */
    private fun majGeometriesReleves(observations: List<Observation>) {
        val parCle = observations
            .filter { (it.geometryType == "Polygon" || it.geometryType == "LineString") && !it.geometryCoordsJson.isNullOrEmpty() }
            .groupBy { cleReleve(it) }
        // Retire les relevés disparus.
        releveGeoms.keys.filter { it !in parCle }.forEach { cle ->
            releveGeoms.remove(cle)?.let { g ->
                binding.map.overlays.remove(g.overlay)
                g.markers.forEach { binding.map.overlays.remove(it) }
            }
        }
        parCle.forEach { (cle, obsReleve) ->
            val rep = obsReleve.first()
            val type = rep.geometryType!!
            val sommets = parseCoordsGeom(rep.geometryCoordsJson)
            if (type == "Polygon" && sommets.size < 3) return@forEach
            if (type == "LineString" && sommets.size < 2) return@forEach
            val existant = releveGeoms[cle]
            if (existant != null && existant.type == type && existant.markers.size == sommets.size) {
                // Resync (déjà dessiné) : on aligne sommets/markers sur le modèle.
                sommets.forEachIndexed { i, pt ->
                    existant.sommets[i] = pt
                    existant.markers[i].position = pt
                }
                redessinerFormeReleve(existant)
                return@forEach
            }
            // (Re)création complète.
            existant?.let { g ->
                binding.map.overlays.remove(g.overlay)
                g.markers.forEach { binding.map.overlays.remove(it) }
            }
            releveGeoms[cle] = creerGeomReleveEditable(cle, type, obsReleve.map { it.id }, sommets)
        }
    }

    /** Construit la forme (overlay) + les markers draggables d'un relevé repris. */
    private fun creerGeomReleveEditable(
        cle: String, type: String, ids: List<String>, sommetsInit: List<GeoPoint>,
    ): GeomReleveEditable {
        val sommets = sommetsInit.toMutableList()
        val g = GeomReleveEditable(type, ids, sommets, construireFormeReleve(type, sommets, cle))
        binding.map.overlays.add(0, g.overlay) // sous les markers
        sommets.forEachIndexed { idx, pt ->
            val marker = Marker(binding.map).apply {
                position = pt
                icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_pin_drop)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                isDraggable = true
                title = "Sommet ${idx + 1}"
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> true } // pas de bulle au tap
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) {}
                    override fun onMarkerDrag(marker: Marker) {
                        val i = g.markers.indexOf(marker)
                        if (i in g.sommets.indices) {
                            g.sommets[i] = marker.position
                            redessinerFormeReleve(g) // live, sans toucher au modèle
                        }
                    }
                    override fun onMarkerDragEnd(marker: Marker) {
                        // Écrit la nouvelle forme + recentre le centroïde sur toutes les obs.
                        val coordsJson = com.google.gson.Gson().toJson(
                            g.sommets.map { doubleArrayOf(it.longitude, it.latitude) }
                        )
                        val lat = g.sommets.map { it.latitude }.average()
                        val lon = g.sommets.map { it.longitude }.average()
                        traceViewModel.mettreAJourGeometrieReleve(g.ids, coordsJson, lat, lon)
                    }
                })
            }
            binding.map.overlays.add(marker)
            g.markers.add(marker)
        }
        binding.map.invalidate()
        return g
    }

    /** Crée un overlay Polyline/Polygon bleu pour la forme d'un relevé, avec tap → liste obs. */
    private fun construireFormeReleve(type: String, sommets: List<GeoPoint>, cle: String): org.osmdroid.views.overlay.Overlay {
        val onTap = {
            val obsReleve = traceViewModel.observations.value?.filter { cleReleve(it) == cle } ?: emptyList()
            if (obsReleve.isNotEmpty()) montrerOptionsReleve(obsReleve)
        }
        return if (type == "LineString") {
            Polyline(binding.map).apply {
                setPoints(sommets.toList())
                outlinePaint.color = 0xCC2196F3.toInt()
                outlinePaint.strokeWidth = 5f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                setOnClickListener { _, _, _ -> onTap(); true }
            }
        } else {
            org.osmdroid.views.overlay.Polygon(binding.map).apply {
                points = sommets.toList()
                fillPaint.color = 0x402196F3
                outlinePaint.color = 0xCC2196F3.toInt()
                outlinePaint.strokeWidth = 5f
                setOnClickListener { _, _, _ -> onTap(); true }
            }
        }
    }

    /** Repeint l'overlay d'un relevé à partir de g.sommets, sans recréer les markers
     *  (utilisé en live pendant le drag d'un sommet). On mute les points de l'overlay
     *  existant au lieu de le recréer : le type ne change jamais pour un relevé donné, et
     *  recréer un Polygon/Polyline à chaque frame de drag (+ rescan O(n) des overlays)
     *  provoquait du jank sur les formes à nombreux sommets. */
    private fun redessinerFormeReleve(g: GeomReleveEditable) {
        when (val o = g.overlay) {
            is Polyline -> o.setPoints(g.sommets.toList())
            is org.osmdroid.views.overlay.Polygon -> o.points = g.sommets.toList()
        }
        binding.map.invalidate()
    }

    /** Parse un geometryCoordsJson (`[[lon,lat], …]`) en liste de GeoPoint. */
    private fun parseCoordsGeom(json: String?): List<GeoPoint> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONArray(i)?.let { GeoPoint(it.getDouble(1), it.getDouble(0)) }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun createObservationMarker(obs: Observation): Marker {
        val marker = Marker(binding.map)
        marker.position = GeoPoint(obs.latitude, obs.longitude)
        marker.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_bird_marker)
        marker.title = obs.espece
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        var sub = fmt.format(Date(obs.date))
        if (obs.nombre > 1) sub += " · ${obs.nombre} ind."
        if (obs.notes.isNotEmpty()) sub += " — ${obs.notes}"
        marker.snippet = sub
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        // Drag direct pour repositionner. Pour un relevé multi-taxons (toutes les obs au
        // même point), on déplace TOUTES les obs du même releveId d'un coup — sinon on
        // casserait la cohérence "1 relevé = 1 point" attendue côté OCCTAX.
        marker.isDraggable = true
        marker.setOnMarkerClickListener { _, _ ->
            val toutes = traceViewModel.observations.value ?: emptyList()
            val obsDuReleve = if (!obs.releveId.isNullOrEmpty())
                toutes.filter { it.releveId == obs.releveId }
            else listOf(obs)
            montrerOptionsReleve(obsDuReleve)
            true
        }
        marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {}
            override fun onMarkerDrag(marker: Marker) {}
            override fun onMarkerDragEnd(marker: Marker) {
                val toutes = traceViewModel.observations.value ?: return
                val idsADeplacer = if (!obs.releveId.isNullOrEmpty())
                    toutes.filter { it.releveId == obs.releveId }.map { it.id }
                else listOf(obs.id)
                idsADeplacer.forEach { id ->
                    traceViewModel.mettreAJourObservationPosition(
                        id, marker.position.latitude, marker.position.longitude,
                    )
                }
            }
        })
        return marker
    }

    /** Dialog d'options pour un relevé pointé sur la carte : liste lisible des espèces
     *  + boutons Éditer (ouvre la saisie multi-taxons) et Déplacer (entre en mode reposition,
     *  toutes les obs du relevé se déplaceront ensemble au point validé). */
    private fun montrerOptionsReleve(observations: List<Observation>) {
        if (observations.isEmpty()) return
        val premiere = observations.first()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val especes = observations.joinToString("\n") { o ->
            val n = if (o.nombre > 1) " × ${o.nombre}" else ""
            val notes = if (o.notes.isNotEmpty()) " — ${o.notes}" else ""
            "• ${o.espece}$n$notes"
        }
        val titre = if (observations.size == 1)
            "${premiere.espece} · ${fmt.format(Date(premiere.date))}"
        else
            "${observations.size} espèces · ${fmt.format(Date(premiere.date))}"
        AlertDialog.Builder(requireContext())
            .setTitle(titre)
            .setMessage(especes)
            .setPositiveButton("Éditer") { _, _ ->
                val bundle = Bundle().apply {
                    val rid = premiere.releveId
                    if (!rid.isNullOrEmpty()) putString("releveId", rid)
                    else putString("obsId", premiere.id)
                }
                findNavController().navigate(R.id.action_trace_to_saisie, bundle)
            }
            // Plus de bouton "Déplacer" : le point se drague directement sur la carte, et
            // les sommets d'un polygone/ligne aussi.
            .setNegativeButton("Fermer", null)
            .show()
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

    private fun demarrerLocalisation() {
        locationOverlay?.enableMyLocation()
        LocationForegroundService.start(requireContext())
    }

    private fun showConfirmTerminer() {
        // Aucune observation = rien à enregistrer ; on quitte directement sans dialog,
        // mais on libère quand même le GPS pour ne pas laisser le foreground service
        // tourner en arrière-plan.
        val obs = traceViewModel.observations.value ?: emptyList()
        if (obs.isEmpty()) {
            traceViewModel.locationTracker.arreterParcours()
            LocationForegroundService.stop(requireContext())
            findNavController().navigateUp()
            return
        }

        val peutEnvoyerGn = gnConfig.estConfiguree && obs.any { it.cdNom != null }

        val options = mutableListOf(
            getString(R.string.enregistrer_quitter),
        )
        if (peutEnvoyerGn) options.add(getString(R.string.enregistrer_envoyer_gn))
        options.add(getString(R.string.supprimer_sortie))
        options.add(getString(R.string.continuer_sortie))

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.terminer_sortie)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.enregistrer_quitter) -> terminerSortie(envoyerGN = false)
                    getString(R.string.enregistrer_envoyer_gn) -> terminerSortie(envoyerGN = true)
                    getString(R.string.supprimer_sortie) -> {
                        // En mode reprise : supprimer aussi l'entrée du store, sinon la
                        // sortie reste dans la liste avec ses anciennes obs/trace.
                        traceViewModel.sortieEnEditionId?.let { sortieStore.supprimer(it) }
                        traceViewModel.locationTracker.arreterParcours()
                        traceViewModel.reinitialiser()
                        LocationForegroundService.stop(requireContext())
                        findNavController().navigateUp()
                    }
                }
            }.show()
    }

    private fun terminerSortie(envoyerGN: Boolean) {
        traceViewModel.locationTracker.arreterParcours()
        LocationForegroundService.stop(requireContext())
        // En reprise : on garde le même id et la date d'origine pour que la sortie reste à
        // sa place chronologique dans la liste. Sinon, nouvelle sortie classique.
        val idReprise = traceViewModel.sortieEnEditionId
        val sortieExistante = idReprise?.let { id ->
            sortieStore.charger().firstOrNull { it.id == id }
        }
        val sortie = if (sortieExistante != null) {
            sortieExistante.copy(
                pointsParcours = traceViewModel.locationTracker.parcours.value ?: emptyList(),
                observations = traceViewModel.observations.value?.toList() ?: emptyList(),
                distanceTotale = traceViewModel.locationTracker.distanceTotale.value ?: 0.0,
            )
        } else {
            Sortie(
                date = System.currentTimeMillis(),
                pointsParcours = traceViewModel.locationTracker.parcours.value ?: emptyList(),
                observations = traceViewModel.observations.value?.toList() ?: emptyList(),
                distanceTotale = traceViewModel.locationTracker.distanceTotale.value ?: 0.0,
            )
        }
        if (sortie.pointsParcours.isNotEmpty() || sortie.observations.isNotEmpty()) {
            if (idReprise != null) sortieStore.remplacer(idReprise, sortie)
            else sortieStore.ajouter(sortie)
        }
        traceViewModel.reinitialiser()

        if (envoyerGN) {
            envoyerVersGeoNature(sortie)
        } else {
            findNavController().navigateUp()
        }
    }

    private fun envoyerVersGeoNature(sortie: Sortie) {
        binding.overlayEnvoi.visibility = View.VISIBLE
        envoiEnCours = true
        currentJob = lifecycleScope.launch {
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
            } finally {
                envoiEnCours = false
                binding.overlayEnvoi.visibility = View.GONE
            }
        }
    }

    private fun showResult(msg: String, success: Boolean) {
        AlertDialog.Builder(requireContext())
            .setTitle(if (success) "GeoNature" else getString(R.string.erreur_envoi))
            .setMessage(msg)
            .setPositiveButton("OK") { _, _ -> findNavController().navigateUp() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        sensorManager = requireContext().getSystemService(SensorManager::class.java)
        val rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVec != null) {
            useRotationVector = true
            sensorManager.registerListener(compassListener, rotVec, SensorManager.SENSOR_DELAY_UI)
        } else {
            useRotationVector = false
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        savedMapCenter = binding.map.mapCenter.let { GeoPoint(it.latitude, it.longitude) }
        savedMapZoom = binding.map.zoomLevelDouble
        binding.map.onPause()
        sensorManager.unregisterListener(compassListener)
        gravityReady = false
        geomagneticReady = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        currentJob?.cancel()
        observationMarkers.clear()
        releveGeoms.clear()
        tracePolyline = null
        _binding = null
    }


    private fun applyWindowInsets() {
        // Carte plein écran, overlays à l'écart des barres système. La coche verte reste
        // en haut → status bar margin. Le stack ll_carte_controles est en bas → nav bar
        // margin pour ne pas chevaucher la barre de navigation gestuelle. Les marges
        // XML d'origine (12dp / 16dp) sont conservées et cumulées par les helpers.
        binding.btnRetour.applyStatusBarMargin()
        binding.bandeauPositionnement.applyStatusBarMargin()
        binding.llCarteControles.applyNavBarMargin()
        binding.panneauControle.applyNavBarMargin()
        binding.panneauValidationPosition.applyNavBarMargin()
        binding.infoBarre.applyNavBarMargin()
    }
}