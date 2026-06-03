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

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.core.content.ContextCompat
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentSortieDetailBinding
import fr.ariegenature.geomys.gpx.genererGPX
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.network.GeoNatureUpload
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.SortieStore
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
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

        // Carte plein écran, boutons à l'écart des barres système.
        binding.btnRetour.applyStatusBarMargin()
        // Envoyer/Partager en haut à droite → sous la status bar ; contrôles carte en bas
        // à droite → au-dessus de la nav bar.
        binding.conteneurActionsHaut.applyStatusBarMargin()
        binding.conteneurCarte.applyNavBarMargin()
    }

    private fun setupButtons() {
        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }
        binding.btnFondCarte.setOnClickListener {
            fondCarte = fondCarte.suivant()
            binding.map.setTileSource(tileSourcePour(fondCarte))
            binding.map.invalidate()
            enregistrerFondCarte(requireContext(), fondCarte)
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
                val res = GeoNatureUpload.envoyer(sortie, gnConfig)
                val nb = res.nbCrees; val total = res.nbTotal; val premierReleve = res.premierIdReleve
                var msg = "$nb/$total relevé${if (total > 1) "s" else ""} créé${if (nb > 1) "s" else ""} sur GeoNature"
                if (premierReleve != null) msg += "\nPremier id_releve_occtax : $premierReleve"
                if (res.mediasOK > 0) msg += "\n${res.mediasOK} média(s) uploadé(s)"
                if (res.mediasKO > 0) msg += "\n⚠ ${res.mediasKO} média(s) échoué(s) : ${res.mediaErreurMsg ?: ""}"
                if (res.relevesOrphelins.isNotEmpty()) msg += "\n⚠ ${res.relevesOrphelins.size} relevé(s) vide(s) côté GeoNature (id : ${res.relevesOrphelins.joinToString(", ")}), à supprimer manuellement."
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
        fondCarte = chargerFondCarte(requireContext(), fondCarte)
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
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        // Plusieurs obs peuvent partager le même point (saisie multi-taxons d'un coup).
        // On regroupe par coordonnées arrondies (1e-6° ≈ 11 cm) — sinon un seul marker
        // est cliquable et masque les autres espèces du même point.
        val groupes = sortie.observations.groupBy {
            Pair((it.latitude * 1_000_000).toLong(), (it.longitude * 1_000_000).toLong())
        }
        // Sommets de toutes les géométries ligne/polygone, pour étendre le cadrage final.
        val geomPts = mutableListOf<GeoPoint>()
        for ((_, obsGroup) in groupes) {
            val rep = obsGroup.first()
            // Géométrie du relevé (ligne / polygone) : redessinée à partir des sommets
            // stockés, sinon on ne verrait que le centroïde (un simple point).
            val sommets = parseCoordsGeom(rep.geometryCoordsJson)
            val estPolygone = rep.geometryType == "Polygon" && sommets.size >= 3
            val estLigne = rep.geometryType == "LineString" && sommets.size >= 2

            val titre: String
            val contenu: String
            if (obsGroup.size == 1) {
                titre = rep.espece
                var sub = fmt.format(Date(rep.date))
                if (rep.nombre > 1) sub += " · ${rep.nombre} ind."
                if (rep.notes.isNotEmpty()) sub += " — ${rep.notes}"
                contenu = sub
            } else {
                // Une espèce par ligne — l'InfoWindow custom n'a pas de maxLines, donc
                // toute la liste s'affiche même au-delà de 3 espèces.
                titre = "${obsGroup.size} espèces · ${fmt.format(Date(rep.date))}"
                contenu = obsGroup.joinToString("\n") { o ->
                    val n = if (o.nombre > 1) " × ${o.nombre}" else ""
                    val notes = if (o.notes.isNotEmpty()) " — ${o.notes}" else ""
                    "• ${o.espece}$n$notes"
                }
            }

            when {
                // Ligne / polygone : on dessine la forme et on rattache l'InfoWindow à un tap
                // sur la forme elle-même. PAS de marker central — le centroïde n'a pas de sens
                // pour une géométrie surfacique/linéaire et embrouille la lecture.
                estPolygone || estLigne -> {
                    val iw = ObsInfoWindow(binding.map, titre, contenu)
                    val overlay = if (estPolygone) {
                        Polygon(binding.map).apply {
                            points = sommets
                            fillPaint.color = 0x402196F3
                            outlinePaint.color = 0xCC2196F3.toInt()
                            outlinePaint.strokeWidth = 5f
                            infoWindow = iw
                            setOnClickListener { _, _, eventPos -> iw.open(this, eventPos, 0, 0); true }
                        }
                    } else {
                        Polyline(binding.map).apply {
                            setPoints(sommets)
                            outlinePaint.color = 0xCC2196F3.toInt()
                            outlinePaint.strokeWidth = 5f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            infoWindow = iw
                            setOnClickListener { _, _, eventPos -> iw.open(this, eventPos, 0, 0); true }
                        }
                    }
                    binding.map.overlays.add(overlay)
                    geomPts += sommets
                }
                // Point (ou géométrie absente) : marker classique cliquable.
                else -> {
                    val marker = Marker(binding.map).apply {
                        position = GeoPoint(rep.latitude, rep.longitude)
                        icon = markerIcon
                        title = titre
                        snippet = contenu
                        infoWindow = ObsInfoWindow(binding.map, titre, contenu)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { m, _ ->
                            m.showInfoWindow()
                            true
                        }
                    }
                    binding.map.overlays.add(marker)
                }
            }
        }

        val allPts = sortie.pointsParcours.map { GeoPoint(it.latitude, it.longitude) } +
            sortie.observations.map { GeoPoint(it.latitude, it.longitude) } +
            geomPts

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

/** InfoWindow custom : layout sans maxLines, donc affiche n'importe quel nombre
 *  d'espèces sur des lignes séparées contrairement à l'InfoWindow par défaut
 *  d'osmdroid-bonuspack qui limite title/snippet à 2-3 lignes. */
private class ObsInfoWindow(
    map: org.osmdroid.views.MapView,
    private val titre: String,
    private val contenu: String,
) : org.osmdroid.views.overlay.infowindow.InfoWindow(R.layout.infowindow_obs, map) {
    override fun onOpen(item: Any?) {
        closeAllInfoWindowsOn(mMapView)
        mView.findViewById<android.widget.TextView>(R.id.tv_title).text = titre
        val tvContent = mView.findViewById<android.widget.TextView>(R.id.tv_content)
        if (contenu.isEmpty()) {
            tvContent.visibility = android.view.View.GONE
        } else {
            tvContent.text = contenu
            tvContent.visibility = android.view.View.VISIBLE
        }
    }
    override fun onClose() {}
}
