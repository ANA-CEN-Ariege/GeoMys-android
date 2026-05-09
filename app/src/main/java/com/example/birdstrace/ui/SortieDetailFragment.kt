package com.example.birdstrace.ui

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.core.content.ContextCompat
import com.example.birdstrace.R
import com.example.birdstrace.databinding.FragmentSortieDetailBinding
import com.example.birdstrace.gpx.genererGPX
import com.example.birdstrace.model.Observation
import com.example.birdstrace.model.Sortie
import com.example.birdstrace.network.GeoNatureService
import com.example.birdstrace.store.GeoNatureConfig
import com.example.birdstrace.store.SortieStore
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SortieDetailFragment : Fragment() {
    private var _binding: FragmentSortieDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var sortie: Sortie
    private lateinit var sortieStore: SortieStore
    private lateinit var gnConfig: GeoNatureConfig
    private var fondCarte = FondCarte.TOPO

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentSortieDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sortieStore = SortieStore(requireContext())
        gnConfig = GeoNatureConfig(requireContext())

        val sortieId = arguments?.getString("sortieId") ?: run {
            findNavController().navigateUp()
            return
        }
        sortie = sortieStore.charger().find { it.id == sortieId } ?: run {
            findNavController().navigateUp()
            return
        }

        setupMap()
        setupButtons()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            binding.btnRetour.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                topMargin = top + 12
            }
            binding.btnListeEspeces.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                topMargin = top + 12
            }
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            binding.conteneurFabs.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                bottomMargin = bottom + dp16
            }
            insets
        }
    }

    private fun setupButtons() {
        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }
        binding.btnFondCarte.setOnClickListener {
            fondCarte = fondCarte.suivant()
            binding.map.setTileSource(tileSourcePour(fondCarte))
            binding.map.invalidate()
        }
        binding.btnExporter.setOnClickListener { exporterGpx() }

        if (sortie.observations.isNotEmpty()) {
            binding.btnListeEspeces.visibility = View.VISIBLE
            binding.btnListeEspeces.setOnClickListener { montrerListeEspeces() }
        }

        val peutEnvoyer = !sortie.envoyeGeoNature && !sortie.estImportee
            && gnConfig.estConfiguree
            && sortie.observations.any { it.cdNom != null }
        if (peutEnvoyer) {
            binding.btnEnvoyerGn.visibility = View.VISIBLE
            binding.btnEnvoyerGn.setOnClickListener { envoyerVersGeoNature() }
        }
    }

    private fun montrerListeEspeces() {
        val agregees = sortie.observations
            .groupBy { it.espece }
            .map { (espece, obs) -> Triple(espece, obs.sumOf { it.nombre }, obs.firstOrNull()?.cdNom) }
            .sortedBy { it.first }

        val lignes = agregees.map { (espece, nombre, cdNom) ->
            buildString {
                append(espece)
                if (cdNom != null) append("\n  cd_nom : $cdNom")
                append("\n  $nombre ind.")
            }
        }
        val titre = "${agregees.size} espèce${if (agregees.size > 1) "s" else ""}"
        AlertDialog.Builder(requireContext())
            .setTitle(titre)
            .setItems(lignes.toTypedArray(), null)
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun envoyerVersGeoNature() {
        binding.overlayEnvoi.visibility = View.VISIBLE
        binding.btnEnvoyerGn.isEnabled = false
        lifecycleScope.launch {
            try {
                val (nb, total, premierReleve) = GeoNatureService.envoyer(sortie, gnConfig)
                var msg = "$nb/$total relevé${if (total > 1) "s" else ""} créé${if (nb > 1) "s" else ""} sur GeoNature"
                if (premierReleve != null) msg += "\nPremier id_releve_occtax : $premierReleve"
                sortieStore.marquerEnvoyee(sortie.id)
                binding.btnEnvoyerGn.visibility = View.GONE
                AlertDialog.Builder(requireContext())
                    .setTitle("GeoNature")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                binding.btnEnvoyerGn.isEnabled = true
                AlertDialog.Builder(requireContext())
                    .setTitle("Erreur d'envoi")
                    .setMessage(e.message ?: "Erreur inconnue")
                    .setPositiveButton("OK", null)
                    .show()
            } finally {
                binding.overlayEnvoi.visibility = View.GONE
            }
        }
    }

    private fun setupMap() {
        binding.map.setTileSource(tileSourcePour(fondCarte))
        binding.map.setMultiTouchControls(true)

        if (sortie.pointsParcours.size > 1) {
            val polyline = Polyline(binding.map).apply {
                setPoints(sortie.pointsParcours.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.color = 0xCC2196F3.toInt()
                outlinePaint.strokeWidth = 5f
                outlinePaint.strokeCap = Paint.Cap.ROUND
            }
            binding.map.overlays.add(polyline)
        }

        val markerIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_bird_marker)
        for (obs in sortie.observations) {
            val marker = Marker(binding.map).apply {
                position = GeoPoint(obs.latitude, obs.longitude)
                icon = markerIcon
                title = obs.espece
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                var sub = fmt.format(Date(obs.date))
                if (obs.nombre > 1) sub += " · ${obs.nombre} ind."
                if (obs.notes.isNotEmpty()) sub += " — ${obs.notes}"
                snippet = sub
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { m, _ ->
                    m.showInfoWindow()
                    true
                }
            }
            binding.map.overlays.add(marker)
        }

        val allPts = sortie.pointsParcours.map { GeoPoint(it.latitude, it.longitude) } +
            sortie.observations.map { GeoPoint(it.latitude, it.longitude) }

        if (allPts.isEmpty()) {
            binding.map.controller.setCenter(GeoPoint(46.5, 2.5))
            binding.map.controller.setZoom(6.0)
        } else {
            val box = BoundingBox.fromGeoPoints(allPts)
            val degenerate = (box.latNorth - box.latSouth) < 0.0001 && (box.lonEast - box.lonWest) < 0.0001
            if (degenerate) {
                binding.map.controller.setCenter(allPts[0])
                binding.map.controller.setZoom(15.0)
            } else {
                binding.map.post { binding.map.zoomToBoundingBox(box.increaseByScale(1.4f), false) }
            }
        }
    }

    override fun onResume() { super.onResume(); binding.map.onResume() }
    override fun onPause() { super.onPause(); binding.map.onPause() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private fun exporterGpx() {
        val gpxContent = genererGPX(sortie.observations, sortie.pointsParcours)
        val file = File(requireContext().cacheDir, "sortie_${sortie.id.take(8)}.gpx")
        file.writeText(gpxContent)
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Exporter GPX"))
    }
}