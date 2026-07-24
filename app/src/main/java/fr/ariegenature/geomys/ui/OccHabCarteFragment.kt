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

import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentOcchabCarteBinding
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Définit la géométrie d'une station OccHab par taps sur la carte : mode Point (un seul point,
 * déplaçable) ou mode Polygone (on ajoute des sommets). Contrôles carte identiques à Occtax
 * (mode point/polygone à gauche, zoom bas-gauche, centrer/boussole/fond bas-droite).
 * « Valider » écrit la géométrie dans [OccHabViewModel] et enchaîne sur le formulaire de station.
 */
class OccHabCarteFragment : Fragment(), MapEventsReceiver {
    private var _binding: FragmentOcchabCarteBinding? = null
    private val binding get() = _binding!!
    private val occhabViewModel: OccHabViewModel by activityViewModels()
    private var fondCarte: FondChoisi = FondChoisi.EnLigne(FondCarte.TOPO)
    private var locationOverlay: MyLocationNewOverlay? = null

    private enum class Mode { POINT, POLYGONE }
    private var mode = Mode.POINT
    private var pointChoisi: GeoPoint? = null
    private val sommets = mutableListOf<GeoPoint>()
    // Marker unique du point (mode Point), markers draggables des sommets (mode Polygone) et
    // overlay de forme (polygone). Séparés pour pouvoir repeindre la forme SANS recréer les
    // markers pendant un drag (cf. TraceFragment).
    private var markerPoint: Marker? = null
    private val markersSommets = mutableListOf<Marker>()
    private var overlayForme: Overlay? = null

    // Boussole (repris de TraceFragment) : rotation de la carte selon l'orientation du téléphone.
    private lateinit var sensorManager: SensorManager
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var gravityReady = false
    private var geomagneticReady = false
    private var useRotationVector = false
    private var carteSuitBoussole = false

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
            if (carteSuitBoussole) {
                compass.post { compass.setAzimuth(-azimuth) }
                val map = _binding?.map ?: return
                map.post { map.setMapOrientation(-azimuth); map.invalidate() }
            } else {
                compass.post { compass.setAzimuth(0f) }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentOcchabCarteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.bandeauSaisie.root.applyStatusBarMargin()
        // Comme Occtax : les clusters de coins ET le bandeau du bas passent au-dessus de la barre
        // système (marge XML + inset). Les 100dp de marge des clusters les gardent au-dessus du
        // bandeau du bas, qui ne les masque donc pas.
        binding.llZoom.applyNavBarMargin()
        binding.llCarteControles.applyNavBarMargin()
        binding.panelBas.applyNavBarMargin()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "OccHab")

        fondCarte = chargerFondChoisi(requireContext())
        appliquerFond(binding.map, fondCarte, requireContext())
        binding.map.setMultiTouchControls(true)
        binding.map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        binding.btnZoomIn.setOnClickListener { binding.map.controller.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.map.controller.zoomOut() }
        binding.btnFondCarte.setOnClickListener {
            choisirFondCarte(requireContext(), fondCarte) { choisi ->
                fondCarte = choisi
                appliquerFond(binding.map, fondCarte, requireContext())
                enregistrerFondChoisi(requireContext(), fondCarte)
            }
        }

        // Bouton centrer sur la position GPS.
        binding.btnCentrer.setOnClickListener {
            val loc = locationOverlay?.myLocation
            if (loc != null) binding.map.controller.animateTo(loc)
            else Toast.makeText(requireContext(), "Acquisition GPS en cours…", Toast.LENGTH_SHORT).show()
        }

        // Boussole (toggle rotation carte), comme Occtax.
        binding.compass.setActif(carteSuitBoussole)
        binding.compass.setOnClickListener {
            carteSuitBoussole = !carteSuitBoussole
            binding.compass.setActif(carteSuitBoussole)
            if (!carteSuitBoussole) {
                binding.map.setMapOrientation(0f)
                binding.map.invalidate()
                binding.compass.setAzimuth(0f)
            }
        }

        // Overlay de captation des taps (placer point / ajouter sommet), en tout premier.
        binding.map.overlays.add(0, MapEventsOverlay(this))

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), binding.map).apply {
            // Même point bleu que la Saisie multi-taxons (TraceFragment) : icône ET flèche de
            // direction = ic_gps_blue_dot, hotspot centré.
            setPersonIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap())
            setPersonHotspot(10f, 10f)
            setDirectionArrow(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap(),
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap(),
            )
            enableMyLocation()
            runOnFirstFix {
                val loc = myLocation ?: return@runOnFirstFix
                binding.map.post {
                    if (pointChoisi == null && sommets.isEmpty()) {
                        binding.map.controller.setZoom(16.0)
                        binding.map.controller.setCenter(loc)
                    }
                }
            }
        }
        binding.map.overlays.add(locationOverlay)

        preremplirDepuisViewModel()

        if (pointChoisi == null && sommets.isEmpty()) {
            binding.map.controller.setZoom(11.0)
            binding.map.controller.setCenter(GeoPoint(42.93, 1.40))
        }

        binding.btnModePoint.setOnClickListener { changerMode(Mode.POINT) }
        binding.btnModePolygone.setOnClickListener { changerMode(Mode.POLYGONE) }
        binding.btnAnnulerPoint.setOnClickListener {
            if (mode == Mode.POLYGONE && sommets.isNotEmpty()) {
                sommets.removeAt(sommets.size - 1)
                redessiner(); majBoutons()
            }
        }
        binding.btnValider.setOnClickListener { valider() }

        changerMode(mode)
    }

    private fun preremplirDepuisViewModel() {
        val s = occhabViewModel.station
        when {
            s.geometryType == "Polygon" && !s.geometryCoordsJson.isNullOrEmpty() -> {
                try {
                    val arr = JSONArray(s.geometryCoordsJson)
                    for (i in 0 until arr.length()) {
                        val pt = arr.getJSONArray(i)
                        sommets.add(GeoPoint(pt.getDouble(1), pt.getDouble(0)))
                    }
                    mode = Mode.POLYGONE
                } catch (_: Exception) {}
            }
            s.geometryType == "Point" && (s.latitude != 0.0 || s.longitude != 0.0) -> {
                pointChoisi = GeoPoint(s.latitude, s.longitude)
                mode = Mode.POINT
            }
        }
        if (pointChoisi != null || sommets.isNotEmpty()) {
            val centre = pointChoisi ?: sommets.first()
            binding.map.controller.setZoom(16.0)
            binding.map.controller.setCenter(centre)
            redessiner()
        }
    }

    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
        when (mode) {
            Mode.POINT -> pointChoisi = p
            Mode.POLYGONE -> sommets.add(p)
        }
        redessiner()
        majBoutons()
        return true
    }

    override fun longPressHelper(p: GeoPoint): Boolean = false

    private fun changerMode(m: Mode) {
        mode = m
        mettreEnEvidenceBoutonMode()
        binding.tvInstructions.text = if (m == Mode.POINT)
            "Touchez pour placer le point · appui long pour le déplacer" else
            "Touchez pour ajouter des sommets (≥ 3) · appui long pour déplacer"
        redessiner()
        majBoutons()
    }

    /** Bouton de mode actif = fond colorPrimary + icône blanche ; inactif = fond blanc + icône
     *  colorPrimary. Même rendu que le sélecteur d'Occtax (mettreEnEvidenceBoutonMode). */
    private fun mettreEnEvidenceBoutonMode() {
        val primaire = ContextCompat.getColor(requireContext(), R.color.colorPrimary)
        val blanc = Color.WHITE
        fun appliquer(btn: ImageButton, estActif: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(if (estActif) primaire else blanc)
            btn.imageTintList = ColorStateList.valueOf(if (estActif) blanc else primaire)
        }
        appliquer(binding.btnModePoint, mode == Mode.POINT)
        appliquer(binding.btnModePolygone, mode == Mode.POLYGONE)
    }

    private fun redessiner() {
        // Purge ancien rendu (forme + markers).
        overlayForme?.let { binding.map.overlays.remove(it) }
        overlayForme = null
        markersSommets.forEach { binding.map.overlays.remove(it) }
        markersSommets.clear()
        markerPoint?.let { binding.map.overlays.remove(it) }
        markerPoint = null

        if (mode == Mode.POINT) {
            pointChoisi?.let { pt ->
                markerPoint = markerDraggable(pt).apply {
                    setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                        override fun onMarkerDrag(m: Marker) { pointChoisi = m.position }
                        override fun onMarkerDragEnd(m: Marker) { pointChoisi = m.position; majBoutons() }
                        override fun onMarkerDragStart(m: Marker) {}
                    })
                }
                binding.map.overlays.add(markerPoint)
            }
        } else {
            // Forme d'abord (sous les markers → markers restent draggables).
            redessinerForme()
            sommets.forEachIndexed { idx, pt ->
                val marker = markerDraggable(pt).apply {
                    title = "Sommet ${idx + 1}"
                    setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                        override fun onMarkerDrag(m: Marker) {
                            // Live update : la liste suit le doigt, la forme est repeinte sans
                            // recréer les markers (sinon le drag en cours saute).
                            val i = markersSommets.indexOf(m)
                            if (i in sommets.indices) { sommets[i] = m.position; redessinerForme() }
                        }
                        override fun onMarkerDragEnd(m: Marker) { majBoutons() }
                        override fun onMarkerDragStart(m: Marker) {}
                    })
                }
                binding.map.overlays.add(marker)
                markersSommets.add(marker)
            }
        }
        binding.map.invalidate()
    }

    /** (Re)dessine UNIQUEMENT la forme (polygone), sans toucher aux markers — appelé pendant le
     *  drag d'un sommet pour ne pas interrompre l'événement de drag. */
    private fun redessinerForme() {
        overlayForme?.let { binding.map.overlays.remove(it) }
        overlayForme = null
        if (mode == Mode.POLYGONE && sommets.size >= 2) {
            val poly = Polygon(binding.map).apply {
                points = sommets.toList()
                fillPaint.color = 0x552196F3
                outlinePaint.color = 0xFF1976D2.toInt()
                outlinePaint.strokeWidth = 4f
            }
            binding.map.overlays.add(poly)
            overlayForme = poly
        }
        binding.map.invalidate()
    }

    /** Marker de sommet/point : goutte ic_pin_drop, ancrée en bas au centre, DRAGGABLE (appui
     *  long pour repositionner) — même look/comportement que la Saisie multi-taxons. */
    private fun markerDraggable(pt: GeoPoint): Marker = Marker(binding.map).apply {
        position = pt
        icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_pin_drop)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        isDraggable = true
        setInfoWindow(null)
    }

    private fun majBoutons() {
        val geomOk = (mode == Mode.POINT && pointChoisi != null) ||
            (mode == Mode.POLYGONE && sommets.size >= 3)
        binding.btnValider.isEnabled = geomOk
        binding.btnValider.alpha = if (geomOk) 1f else 0.5f
        binding.btnAnnulerPoint.isEnabled = mode == Mode.POLYGONE && sommets.isNotEmpty()
    }

    private fun valider() {
        if (mode == Mode.POINT) {
            val pt = pointChoisi ?: return
            occhabViewModel.definirGeometrie("Point", pt.latitude, pt.longitude, null)
        } else {
            if (sommets.size < 3) return
            val coords = JSONArray()
            var sLat = 0.0; var sLon = 0.0
            sommets.forEach { pt ->
                coords.put(JSONArray().put(pt.longitude).put(pt.latitude))
                sLat += pt.latitude; sLon += pt.longitude
            }
            occhabViewModel.definirGeometrie(
                "Polygon", sLat / sommets.size, sLon / sommets.size, coords.toString(),
            )
        }
        findNavController().naviguerSur(R.id.action_occhab_carte_to_station)
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        locationOverlay?.enableMyLocation()
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
        binding.map.onPause()
        locationOverlay?.disableMyLocation()
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(compassListener)
        gravityReady = false
        geomagneticReady = false
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
