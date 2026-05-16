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
    private var modePositionnement = false
    private var obsARepositionner: Observation? = null
    private var fondCarte = FondCarte.OSM

    private var savedMapCenter: GeoPoint? = null
    private var savedMapZoom: Double = 18.0

    private var locationOverlay: MyLocationNewOverlay? = null
    private var tracePolyline: Polyline? = null
    private val observationMarkers = mutableMapOf<String, Marker>()

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
            compass.post { compass.setAzimuth(-azimuth) }
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

        enregistrerTrace = requireContext()
            .getSharedPreferences("GeoNat_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("enregistrer_trace", true)

        setupMap()
        setupControls()
        observerViewModel()
        demanderPermissions()
        applyWindowInsets()

        binding.btnParcours.visibility = if (enregistrerTrace) View.VISIBLE else View.GONE

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
        binding.map.setTileSource(tileSourcePour(fondCarte))
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(savedMapZoom)
        binding.map.controller.setCenter(savedMapCenter ?: GeoPoint(46.5, 2.5))

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), binding.map).apply {
            setPersonIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap())
            setPersonHotspot(10f, 10f)
            setDirectionArrow(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap(),
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap()
            )
        }
        binding.map.overlays.add(locationOverlay)

        binding.map.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_MOVE
                && !modePositionnement && obsARepositionner == null) {
                suivrePosition = false
                binding.btnCentrer.setImageResource(R.drawable.ic_location_off)
            }
            if (event.action == android.view.MotionEvent.ACTION_UP) v.performClick()
            false
        }
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
        }

        binding.btnValiderPosition.setOnClickListener {
            val center = binding.map.mapCenter
            if (obsARepositionner != null) {
                val obs = obsARepositionner!!
                traceViewModel.mettreAJourObservationPosition(obs.id, center.latitude, center.longitude)
                obsARepositionner = null
                updateModePositionnement()
            } else {
                val bundle = Bundle().apply {
                    putDouble("latitude", center.latitude)
                    putDouble("longitude", center.longitude)
                }
                modePositionnement = false
                updateModePositionnement()
                findNavController().navigate(R.id.action_trace_to_saisie, bundle)
            }
        }

        binding.btnAnnulerPosition.setOnClickListener {
            modePositionnement = false
            obsARepositionner = null
            updateModePositionnement()
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
        val inMode = modePositionnement || obsARepositionner != null
        binding.reticule.visibility = if (inMode) View.VISIBLE else View.GONE
        binding.bandeauPositionnement.visibility = if (inMode) View.VISIBLE else View.GONE
        binding.panneauControle.visibility = if (inMode) View.GONE else View.VISIBLE
        binding.panneauValidationPosition.visibility = if (inMode) View.VISIBLE else View.GONE
        if (inMode) {
            binding.tvBandeauPositionnement.text = if (obsARepositionner != null)
                getString(R.string.repositionner_observation)
            else
                getString(R.string.positionner_observation)
        }
    }

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
            if (obs.id !in observationMarkers) {
                val marker = createObservationMarker(obs)
                observationMarkers[obs.id] = marker
                binding.map.overlays.add(marker)
            }
        }
        binding.map.invalidate()
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
        marker.setOnMarkerClickListener { _, _ ->
            val toutes = traceViewModel.observations.value ?: emptyList()
            montrerListeEspeces(obsProches(obs, toutes).ifEmpty { listOf(obs) })
            true
        }
        return marker
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
        AlertDialog.Builder(requireContext())
            .setTitle("${triees.size} obs. ici — appuyer pour modifier")
            .setItems(items) { _, which ->
                val bundle = Bundle().apply { putString("obsId", triees[which].id) }
                findNavController().navigate(R.id.action_trace_to_saisie, bundle)
            }
            .setPositiveButton("Fermer", null)
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
        val sortie = Sortie(
            date = System.currentTimeMillis(),
            pointsParcours = traceViewModel.locationTracker.parcours.value ?: emptyList(),
            observations = traceViewModel.observations.value?.toList() ?: emptyList(),
            distanceTotale = traceViewModel.locationTracker.distanceTotale.value ?: 0.0
        )
        if (sortie.pointsParcours.isNotEmpty() || sortie.observations.isNotEmpty()) {
            sortieStore.ajouter(sortie)
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
        tracePolyline = null
        _binding = null
    }


    private fun applyWindowInsets() {
        // Carte plein écran, overlays à l'écart des barres système. Les helpers
        // cumulent avec les marges XML (12dp pour les boutons, 68dp pour le bouton
        // fond carte sous la boussole) — pas de double application.
        binding.btnRetour.applyStatusBarMargin()
        binding.compass.applyStatusBarMargin()
        binding.btnFondCarte.applyStatusBarMargin()
        binding.bandeauPositionnement.applyStatusBarMargin()
        binding.panneauControle.applyNavBarMargin()
        binding.panneauValidationPosition.applyNavBarMargin()
        binding.infoBarre.applyNavBarMargin()
    }
}