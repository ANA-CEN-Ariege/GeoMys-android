package com.example.birdstrace.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.birdstrace.R
import com.example.birdstrace.databinding.FragmentExplorerBinding
import com.example.birdstrace.model.Taxon
import com.example.birdstrace.network.GeoNatureService
import com.example.birdstrace.network.ObsExplorer
import com.example.birdstrace.store.GeoNatureConfig
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.Marker

class ExplorerFragment : Fragment() {
    private var _binding: FragmentExplorerBinding? = null
    private val binding get() = _binding!!
    private lateinit var gnConfig: GeoNatureConfig
    private var fetchJob: Job? = null
    private var debounceJob: Job? = null
    private val iconeCache = mutableMapOf<Pair<Taxon, Int>, BitmapDrawable>()
    private var useSatellite = false
    private var taxonFiltre: Taxon? = null
    private var observationsBrutes: List<ObsExplorer> = emptyList()
    private var sensorManager: android.hardware.SensorManager? = null
    private var sensorListener: android.hardware.SensorEventListener? = null

    private val ESRI_SAT = object : OnlineTileSourceBase(
        "ESRIWorldImagery", 1, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            "${baseUrl}${MapTileIndex.getZoom(pMapTileIndex)}/" +
            "${MapTileIndex.getY(pMapTileIndex)}/" +
            "${MapTileIndex.getX(pMapTileIndex)}.jpg"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentExplorerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gnConfig = GeoNatureConfig(requireContext())

        setupMap()
        setupCompass()
        setupFiltres()
        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }
        binding.btnFondCarte.setOnClickListener { toggleFondCarte() }
        binding.btnCentrer.setOnClickListener { centrerSurPosition() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val dp = resources.displayMetrics.density
            val dp32 = (32 * dp).toInt()
            binding.btnRetour.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { topMargin = top + 12 }
            binding.tvCompteur.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { topMargin = top + 12 }
            binding.barreFiltres.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { topMargin = top + 12 }
            binding.ctrlDroite.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { bottomMargin = bottom + (12 * dp).toInt() }
            binding.overlayChargement.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { bottomMargin = bottom + dp32 }
            binding.tvNonConfigure.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { bottomMargin = bottom + dp32 }
            insets
        }

        if (!gnConfig.connexionConfiguree) {
            binding.tvNonConfigure.visibility = View.VISIBLE
            return
        }

        binding.map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { planifierChargement(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { planifierChargement(); return false }
        })

        binding.map.post { chargerObservations() }
    }

    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        val lastLoc = try {
            val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            @Suppress("MissingPermission")
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { null }
        if (lastLoc != null) {
            binding.map.controller.setZoom(12.0)
            binding.map.controller.setCenter(GeoPoint(lastLoc.latitude, lastLoc.longitude))
        } else {
            binding.map.controller.setZoom(6.0)
            binding.map.controller.setCenter(GeoPoint(46.5, 2.5))
        }
    }

    private fun setupFiltres() {
        binding.btnFiltreOiseau.setOnClickListener { toggleFiltre(Taxon.OISEAU) }
        binding.btnFiltreMammifere.setOnClickListener { toggleFiltre(Taxon.MAMMIFERE) }
        binding.btnFiltreReptile.setOnClickListener { toggleFiltre(Taxon.REPTILE) }
        mettreAJourBoutonsFiltres()
    }

    private fun toggleFiltre(taxon: Taxon) {
        taxonFiltre = if (taxonFiltre == taxon) null else taxon
        mettreAJourBoutonsFiltres()
        afficherMarkers(observationsBrutes.filtrees())
    }

    private fun mettreAJourBoutonsFiltres() {
        data class BtnInfo(val view: android.widget.ImageButton, val taxon: Taxon, val couleur: Int)
        val boutons = listOf(
            BtnInfo(binding.btnFiltreOiseau,    Taxon.OISEAU,    0xFFFF6D00.toInt()),
            BtnInfo(binding.btnFiltreMammifere, Taxon.MAMMIFERE, 0xFF795548.toInt()),
            BtnInfo(binding.btnFiltreReptile,   Taxon.REPTILE,   0xFF388E3C.toInt())
        )
        for ((btn, taxon, couleur) in boutons) {
            val actif = taxonFiltre == taxon
            btn.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (actif) couleur else Color.WHITE)
            }
            btn.imageTintList = android.content.res.ColorStateList.valueOf(
                if (actif) Color.WHITE else couleur
            )
        }
    }

    private fun List<ObsExplorer>.filtrees() =
        if (taxonFiltre == null) this else filter { it.taxon == taxonFiltre }

    private fun setupCompass() {
        sensorManager = requireContext().getSystemService(android.content.Context.SENSOR_SERVICE)
            as android.hardware.SensorManager
        val sensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
            ?: return
        val rotMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        sensorListener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                android.hardware.SensorManager.getOrientation(rotMatrix, orientation)
                val deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                binding.compass.setAzimuth(-deg)
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        sensorManager?.registerListener(sensorListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }

    private fun toggleFondCarte() {
        useSatellite = !useSatellite
        binding.map.setTileSource(if (useSatellite) ESRI_SAT else TileSourceFactory.MAPNIK)
        binding.map.invalidate()
    }

    private fun centrerSurPosition() {
        val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE)
            as LocationManager
        val loc = try {
            @Suppress("MissingPermission")
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { null }
        if (loc != null) {
            binding.map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        }
    }

    private fun planifierChargement() {
        debounceJob?.cancel()
        debounceJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(800)
            chargerObservations()
        }
    }

    private fun chargerObservations() {
        val bbox = binding.map.boundingBox
        fetchJob?.cancel()
        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.overlayChargement.visibility = View.VISIBLE
            try {
                val obs = GeoNatureService.recupererObsExplorer(
                    gnConfig,
                    minLon = bbox.lonWest, minLat = bbox.latSouth,
                    maxLon = bbox.lonEast, maxLat = bbox.latNorth
                )
                observationsBrutes = obs
                afficherMarkers(obs.filtrees())
                binding.tvCompteur.text = "${obs.filtrees().size} obs. (12 mois)"
                binding.tvCompteur.visibility = View.VISIBLE
                binding.tvNonConfigure.visibility = View.GONE
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                binding.tvNonConfigure.text = "Erreur : ${e.message}"
                binding.tvNonConfigure.visibility = View.VISIBLE
            } finally {
                binding.overlayChargement.visibility = View.GONE
            }
        }
    }

    private fun afficherMarkers(observations: List<ObsExplorer>) {
        binding.map.overlays.removeAll(binding.map.overlays.filterIsInstance<Marker>().toSet())

        // Grouper par coordonnées arrondies à 3 décimales (≈ 110m) comme iOS cluster
        val groupes = observations.groupBy { "%.3f,%.3f".format(it.latitude, it.longitude) }

        for ((_, groupe) in groupes) {
            val rep = groupe.first()
            val marker = Marker(binding.map).apply {
                position = GeoPoint(rep.latitude, rep.longitude)
                icon = creerIconeMarqueur(rep.taxon, groupe.size)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                // Pas de callout natif : on gère l'affichage via le sheet
                setOnMarkerClickListener { _, _ ->
                    montrerListeObs(groupe)
                    true
                }
            }
            binding.map.overlays.add(marker)
        }
        binding.map.invalidate()
    }

    private fun creerIconeMarqueur(taxon: Taxon, count: Int): BitmapDrawable {
        iconeCache[Pair(taxon, count)]?.let { return it }

        val dp = resources.displayMetrics.density
        val taille = (42 * dp).toInt()
        val bitmap = Bitmap.createBitmap(taille, taille, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = taille / 2f

        val couleur = when (taxon) {
            Taxon.MAMMIFERE -> 0xFF795548.toInt()
            Taxon.REPTILE   -> 0xFF388E3C.toInt()
            else            -> 0xFFFF6D00.toInt()
        }

        val paintCercle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleur }
        canvas.drawCircle(r, r, r - 2, paintCercle)

        if (count > 1) {
            val paintTexte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = r * (if (count < 10) 1.0f else if (count < 100) 0.82f else 0.62f)
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(count.toString(), r, r + paintTexte.textSize * 0.36f, paintTexte)
        } else {
            val iconeRes = when (taxon) {
                Taxon.MAMMIFERE -> R.drawable.ic_paw_small
                Taxon.REPTILE   -> R.drawable.ic_reptile_small
                else            -> R.drawable.ic_bird_small
            }
            val drawable = ContextCompat.getDrawable(requireContext(), iconeRes)!!.mutate()
            DrawableCompat.setTint(drawable, Color.WHITE)
            val pad = (taille * 0.22f).toInt()
            drawable.setBounds(pad, pad, taille - pad, taille - pad)
            drawable.draw(canvas)
        }

        return BitmapDrawable(resources, bitmap).also { iconeCache[Pair(taxon, count)] = it }
    }

    private fun montrerListeObs(groupe: List<ObsExplorer>) {
        val triees = groupe.sortedByDescending { it.date }

        val sheet = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_obs_explorer, null)
        sheet.setContentView(view)

        view.findViewById<android.widget.TextView>(R.id.tv_titre).text =
            "${triees.size} obs. (12 mois)"

        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = triees.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(layoutInflater.inflate(R.layout.item_obs_explorer, parent, false)) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val obs = triees[position]
                val nom = obs.nomVern.ifEmpty { obs.nomCite }
                holder.itemView.findViewById<android.widget.TextView>(R.id.tv_nom).text =
                    nom.replaceFirstChar { it.uppercaseChar() }
                holder.itemView.findViewById<android.widget.TextView>(R.id.tv_detail).text =
                    buildString {
                        if (obs.date.isNotEmpty()) append(obs.date)
                        if (obs.nombre > 1) { if (isNotEmpty()) append(" · "); append("${obs.nombre} ind.") }
                        if (obs.nomSci.isNotEmpty() && obs.nomSci != nom) {
                            if (isNotEmpty()) append(" · "); append(obs.nomSci)
                        }
                    }
            }
        }
        sheet.show()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        val sensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null && sensorListener != null) {
            sensorManager?.registerListener(sensorListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        sensorManager?.unregisterListener(sensorListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()
        debounceJob?.cancel()
        sensorManager?.unregisterListener(sensorListener)
        sensorManager = null
        sensorListener = null
        iconeCache.values.forEach { it.bitmap?.recycle() }
        iconeCache.clear()
        _binding = null
    }
}
