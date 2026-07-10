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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentExplorerBinding
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.network.GeoNatureBrowse
import fr.ariegenature.geomys.network.ObsExplorer
import fr.ariegenature.geomys.store.GeoNatureConfig
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

class ExplorerFragment : Fragment() {
    private var _binding: FragmentExplorerBinding? = null
    private val binding get() = _binding!!
    private lateinit var gnConfig: GeoNatureConfig
    private var fetchJob: Job? = null
    private var debounceJob: Job? = null
    private val iconeCache = mutableMapOf<Pair<Taxon, Int>, BitmapDrawable>()
    private var fondCarte: FondChoisi = FondChoisi.EnLigne(FondCarte.OSM)
    private var taxonFiltre: Taxon? = null
    private var observationsBrutes: List<ObsExplorer> = emptyList()
    private var sensorManager: android.hardware.SensorManager? = null
    private var sensorListener: android.hardware.SensorEventListener? = null

    // Localisation en direct : indispensable pour les GPS externes (RTK via SW Maps
    // en position fictive). getLastKnownLocation seul renvoie souvent null au lancement
    // → la carte tombait sur le fallback "centre de la France".
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var derniereLoc: Location? = null
    private var aCentreSurPosition = false
    private var positionMarker: Marker? = null
    private var iconePositionCache: BitmapDrawable? = null

    /** Demande de permission localisation : si accordée, on démarre l'écoute GPS en direct.
     *  Sans ça, `requestLocationUpdates` lançait une SecurityException avalée → pas de GPS
     *  et aucune invite pour l'utilisateur arrivant directement sur l'Explorer. */
    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { resultats ->
        if (resultats.values.any { it }) demarrerLocalisation()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentExplorerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gnConfig = GeoNatureConfig(requireContext())
        fondCarte = chargerFondChoisi(requireContext())

        setupMap()
        setupLocalisation()
        setupCompass()
        setupFiltres()
        binding.btnFondCarte.setOnClickListener { toggleFondCarte() }
        binding.btnCentrer.setOnClickListener { centrerSurPosition() }
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "Explorer")

        // Carte plein écran, boutons à l'écart des barres système. Les marges XML
        // (12dp pour les boutons, 96dp pour la barre de filtres, 32dp pour les overlays)
        // sont conservées et cumulées avec les insets.
        binding.bandeauSaisie.root.applyStatusBarMargin()
        binding.barreFiltres.applyStatusBarMargin()
        binding.ctrlDroite.applyNavBarMargin()
        binding.llZoom.applyNavBarMargin()
        binding.tvCompteur.applyNavBarMargin()
        binding.overlayChargement.applyNavBarMargin()
        binding.tvNonConfigure.applyNavBarMargin()

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
        appliquerFond(binding.map, fondCarte, requireContext())
        binding.map.setMultiTouchControls(true)
        // Boutons de zoom osmdroid désactivés au profit de nos boutons +/- (cluster bas-gauche).
        binding.map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        binding.btnZoomIn.setOnClickListener { binding.map.controller.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.map.controller.zoomOut() }
        val lastLoc = try {
            val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            @Suppress("MissingPermission")
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { null }
        if (lastLoc != null) {
            derniereLoc = lastLoc
            aCentreSurPosition = true
            binding.map.controller.setZoom(12.0)
            binding.map.controller.setCenter(GeoPoint(lastLoc.latitude, lastLoc.longitude))
            majMarqueurPosition(lastLoc)
        } else {
            // Pas de position en cache : on affiche le fallback (centre France), mais
            // demarrerLocalisation() recentrera dès la 1ère position reçue en direct.
            binding.map.controller.setZoom(6.0)
            binding.map.controller.setCenter(GeoPoint(46.5, 2.5))
        }
    }

    /** Prépare l'écoute du GPS en direct. L'abonnement réel est démarré dans onResume
     *  et arrêté dans onPause pour respecter le cycle de vie. */
    private fun setupLocalisation() {
        locationManager = requireContext()
            .getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                derniereLoc = loc
                majMarqueurPosition(loc)
                // Recentre automatiquement sur la 1ère position reçue tant qu'on n'a
                // pas encore centré sur une vraie position (carte sur le fallback).
                if (!aCentreSurPosition) {
                    aCentreSurPosition = true
                    _binding?.map?.controller?.setZoom(16.0)
                    _binding?.map?.controller?.animateTo(GeoPoint(loc.latitude, loc.longitude))
                }
            }
            @Deprecated("Deprecated") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
    }

    @Suppress("MissingPermission")
    private fun demarrerLocalisation() {
        val lm = locationManager ?: return
        val listener = locationListener ?: return
        // Sans permission : on la demande (au lieu de partir en SecurityException muette).
        // Le callback du launcher rappellera demarrerLocalisation() si elle est accordée.
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = android.Manifest.permission.ACCESS_COARSE_LOCATION
        val accordee = ContextCompat.checkSelfPermission(requireContext(), fine) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(), coarse) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!accordee) {
            permissionLauncher.launch(arrayOf(fine, coarse))
            return
        }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, listener)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, listener)
            }
        } catch (_: Exception) {}
    }

    private fun arreterLocalisation() {
        val listener = locationListener ?: return
        try { locationManager?.removeUpdates(listener) } catch (_: Exception) {}
    }

    /** Affiche / déplace le marqueur "ma position" sur la carte. Réutilise un seul Marker
     *  qu'on garde sous référence pour ne pas le recréer ni le supprimer au rechargement
     *  des observations (cf. afficherMarkers). */
    private fun majMarqueurPosition(loc: Location) {
        val map = _binding?.map ?: return
        val pt = GeoPoint(loc.latitude, loc.longitude)
        val marker = positionMarker ?: Marker(map).also { m ->
            m.icon = iconePosition()
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.isFlat = true
            m.setInfoWindow(null)
            m.setOnMarkerClickListener { _, _ -> true } // pas d'action au tap
            positionMarker = m
            map.overlays.add(m)
        }
        marker.position = pt
        map.invalidate()
    }

    /** Pastille "ma position" : disque bleu cerclé de blanc, style classique des cartes. */
    private fun iconePosition(): BitmapDrawable {
        iconePositionCache?.let { return it }
        val dp = resources.displayMetrics.density
        val taille = (20 * dp).toInt()
        val bitmap = Bitmap.createBitmap(taille, taille, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = taille / 2f
        val bleu = ContextCompat.getColor(requireContext(), R.color.blue_poisson)
        // Halo blanc
        canvas.drawCircle(c, c, c - 1, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        // Disque bleu
        canvas.drawCircle(c, c, c - (3 * dp), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bleu })
        return BitmapDrawable(resources, bitmap).also { iconePositionCache = it }
    }

    private fun boutonsParTaxon(): Map<Taxon, com.google.android.material.button.MaterialButton> = mapOf(
        Taxon.OISEAU      to binding.btnFiltreOiseau,
        Taxon.MAMMIFERE   to binding.btnFiltreMammifere,
        Taxon.REPTILE     to binding.btnFiltreReptile,
        Taxon.BATRACIEN   to binding.btnFiltreBatracien,
        Taxon.MOLLUSQUE   to binding.btnFiltreMollusque,
        Taxon.INVERTEBRES to binding.btnFiltreInvertebres,
        Taxon.POISSON     to binding.btnFiltrePoisson,
        Taxon.INSECTE     to binding.btnFiltreInsecte,
        Taxon.FONGE       to binding.btnFiltreFonge,
        Taxon.PLANTE      to binding.btnFiltrePlante,
    )

    private fun couleurParTaxon(taxon: Taxon): Int = ContextCompat.getColor(
        requireContext(),
        when (taxon) {
            Taxon.OISEAU      -> R.color.orange
            Taxon.MAMMIFERE   -> R.color.brown
            Taxon.REPTILE     -> R.color.colorSecondary
            Taxon.BATRACIEN   -> R.color.blue_batracien
            Taxon.POISSON     -> R.color.blue_poisson
            Taxon.INSECTE     -> R.color.amber_insecte
            Taxon.FONGE       -> R.color.brown_fonge
            Taxon.MOLLUSQUE   -> R.color.purple_invertebres
            Taxon.INVERTEBRES -> R.color.purple_invertebres
            Taxon.PLANTE      -> R.color.teal
        }
    )

    private fun setupFiltres() {
        for ((taxon, btn) in boutonsParTaxon()) {
            btn.setOnClickListener { toggleFiltre(taxon) }
        }
        mettreAJourBoutonsFiltres()
    }

    private fun toggleFiltre(taxon: Taxon) {
        taxonFiltre = if (taxonFiltre == taxon) null else taxon
        mettreAJourBoutonsFiltres()
        afficherMarkers(observationsBrutes.filtrees())
    }

    private fun mettreAJourBoutonsFiltres() {
        val transparent = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        val white = android.content.res.ColorStateList.valueOf(Color.WHITE)
        for ((taxon, btn) in boutonsParTaxon()) {
            val couleur = couleurParTaxon(taxon)
            val actif = taxonFiltre == taxon
            // Groupe sélectionné : pastille de la couleur du taxon. Non sélectionnés : icône CLAIRE
            // (blanche) sur fond transparent → discrets sur le bandeau translucide, le sélectionné ressort.
            btn.backgroundTintList = if (actif) android.content.res.ColorStateList.valueOf(couleur) else transparent
            btn.iconTint = white
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
                _binding?.compass?.setAzimuth(-deg)
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        sensorManager?.registerListener(sensorListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }

    private fun toggleFondCarte() {
        choisirFondCarte(requireContext(), fondCarte) { choisi ->
            fondCarte = choisi
            appliquerFond(binding.map, fondCarte, requireContext())
            enregistrerFondChoisi(requireContext(), fondCarte)
        }
    }

    private fun centrerSurPosition() {
        // Priorité à la position live (reçue via le GPS, mock RTK compris) ; on retombe
        // sur le cache système seulement si aucune position live n'est encore arrivée.
        val loc = derniereLoc ?: try {
            val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE)
                as LocationManager
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

    private fun largeurZoneKm(): Double {
        val b = _binding ?: return 0.0
        val bbox = b.map.boundingBox
        val centerLat = (bbox.latNorth + bbox.latSouth) / 2
        val cosLat = Math.cos(Math.toRadians(centerLat))
        return (bbox.lonEast - bbox.lonWest) * 111.0 * cosLat
    }

    private fun chargerObservations() {
        val b = _binding ?: return
        if (largeurZoneKm() > 100) {
            fetchJob?.cancel()
            observationsBrutes = emptyList()
            afficherMarkers(emptyList())
            b.tvCompteur.visibility = View.GONE
            b.tvNonConfigure.text = "Zoomez pour charger les observations (zone > 100 km)"
            b.tvNonConfigure.visibility = View.VISIBLE
            b.overlayChargement.visibility = View.GONE
            return
        }

        val bbox = b.map.boundingBox
        fetchJob?.cancel()
        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            _binding?.overlayChargement?.visibility = View.VISIBLE
            try {
                val obs = GeoNatureBrowse.recupererObsExplorer(
                    gnConfig,
                    minLon = bbox.lonWest, minLat = bbox.latSouth,
                    maxLon = bbox.lonEast, maxLat = bbox.latNorth
                )
                _binding?.let { b2 ->
                    observationsBrutes = obs
                    afficherMarkers(obs.filtrees())
                    b2.tvCompteur.text = "${obs.filtrees().size} obs. (12 mois)"
                    b2.tvCompteur.visibility = View.VISIBLE
                    b2.tvNonConfigure.visibility = View.GONE
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _binding?.let { b2 ->
                    b2.tvNonConfigure.text = "Erreur : ${e.message}"
                    b2.tvNonConfigure.visibility = View.VISIBLE
                }
            } finally {
                _binding?.overlayChargement?.visibility = View.GONE
            }
        }
    }

    private fun afficherMarkers(observations: List<ObsExplorer>) {
        val b = _binding ?: return
        // On retire les markers d'observations mais on préserve le marqueur "ma position".
        b.map.overlays.removeAll(
            b.map.overlays.filterIsInstance<Marker>().filter { it != positionMarker }.toSet()
        )

        // Grouper par coordonnées arrondies à 3 décimales (≈ 110m) comme iOS cluster
        val groupes = observations.groupBy { "%.3f,%.3f".format(it.latitude, it.longitude) }

        for ((_, groupe) in groupes) {
            val rep = groupe.first()
            val marker = Marker(b.map).apply {
                position = GeoPoint(rep.latitude, rep.longitude)
                icon = creerIconeMarqueur(rep.taxon, groupe.size)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                // Pas de callout natif : on gère l'affichage via le sheet
                setOnMarkerClickListener { _, _ ->
                    montrerListeObs(groupe)
                    true
                }
            }
            b.map.overlays.add(marker)
        }
        // Le marqueur "ma position" repasse au-dessus des observations.
        positionMarker?.let { if (b.map.overlays.remove(it)) b.map.overlays.add(it) }
        b.map.invalidate()
    }

    private fun creerIconeMarqueur(taxon: Taxon, count: Int): BitmapDrawable {
        iconeCache[Pair(taxon, count)]?.let { return it }

        val dp = resources.displayMetrics.density
        val taille = (42 * dp).toInt()
        val bitmap = Bitmap.createBitmap(taille, taille, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = taille / 2f

        val couleur = couleurParTaxon(taxon)

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
                Taxon.OISEAU    -> R.drawable.oiseaux
                Taxon.FONGE     -> R.drawable.champignons2
                Taxon.MAMMIFERE -> R.drawable.mammiferes2
                Taxon.REPTILE   -> R.drawable.reptiles2
                Taxon.BATRACIEN -> R.drawable.amphibiens
                Taxon.POISSON   -> R.drawable.poissons
                Taxon.INSECTE   -> R.drawable.insectes
                Taxon.MOLLUSQUE -> R.drawable.mollusques
                Taxon.INVERTEBRES -> R.drawable.araignees
                Taxon.PLANTE    -> R.drawable.fleurs
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
        demarrerLocalisation()
        val sensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null && sensorListener != null) {
            sensorManager?.registerListener(sensorListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        arreterLocalisation()
        sensorManager?.unregisterListener(sensorListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()
        debounceJob?.cancel()
        arreterLocalisation()
        locationManager = null
        locationListener = null
        // NE PAS faire `positionMarker.icon = null` ici : à ce stade la MapView est déjà
        // détachée (mMapViewRepository == null) et Marker.setIcon(null) déréférence le
        // repository → NPE. La carte étant détachée, plus aucun dessin n'a lieu, donc
        // recycler directement le bitmap est sans risque.
        positionMarker = null
        iconePositionCache?.bitmap?.recycle()
        iconePositionCache = null
        sensorManager?.unregisterListener(sensorListener)
        sensorManager = null
        sensorListener = null
        iconeCache.values.forEach { it.bitmap?.recycle() }
        iconeCache.clear()
        _binding = null
    }
}
