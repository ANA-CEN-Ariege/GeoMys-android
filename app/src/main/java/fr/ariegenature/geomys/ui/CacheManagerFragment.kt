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

import android.app.AlertDialog
import android.app.ProgressDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentCacheManagerBinding
import fr.ariegenature.geomys.network.MonitoringApi
import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Cache Manager : carte plein écran + bouton « Charger les tuiles » qui télécharge tout
 *  ce qui est dans la BoundingBox visible, du zoom courant jusqu'au [ZOOM_MAX_DEFAUT]
 *  (= 17, niveau "sentiers et bâti"). Permet d'utiliser les fonds hors-ligne sur le
 *  terrain.
 *
 *  Politique : on plafonne la surface à [SURFACE_MAX_KM2] (200 km²) pour rester compatible
 *  avec les quotas serveurs (OSM tile policy, IGN clé gratuite) et limiter le stockage
 *  occupé sur l'appareil. Au-delà, le bouton « Charger » est désactivé et un avertissement
 *  rouge invite l'utilisateur à zoomer. Le téléchargement ne couvre QUE le fond
 *  actuellement actif — l'utilisateur fait une passe par fond souhaité. */
class CacheManagerFragment : Fragment() {
    private var _binding: FragmentCacheManagerBinding? = null
    private val binding get() = _binding!!
    private var fondCarte = FondCarte.OSM
    /** Overlays posés par la dernière sélection de protocole — gardés pour pouvoir les
     *  retirer proprement avant d'en afficher d'autres (changement de protocole). */
    private val overlaysProtocole = mutableListOf<Overlay>()
    private var locationOverlay: MyLocationNewOverlay? = null
    /** Protocoles accessibles à l'utilisateur courant (déjà filtrés CRUVED par
     *  [MonitoringApi.chargerModules]). Chargés une fois au démarrage : sert à décider si le
     *  bouton « Listes » s'affiche et à peupler le dialog sans re-fetch. */
    private var modulesAccessibles: List<fr.ariegenature.geomys.network.MonitoringModule> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentCacheManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Bouton « Supprimer » isolé en haut à droite — décalé sous la status bar.
        binding.btnViderCache.applyStatusBarMargin()
        // Le bandeau de navigation est en haut ; le titre est ancré dessous → seul le bandeau
        // porte la marge de status bar (sinon le titre serait décalé deux fois).
        binding.bandeauSaisie.root.applyStatusBarMargin()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "Maps Manager")
        // Le panel bas est ancré sur le bord de l'écran : il doit garder un padding bottom
        // suffisant pour ne pas être occulté par la nav bar gestuelle / 3-boutons.
        binding.panelBas.applyNavBarInset(includeIme = true)

        fondCarte = chargerFondCarte(requireContext(), fondCarte)
        binding.map.setTileSource(tileSourcePour(fondCarte))
        binding.map.setMultiTouchControls(true)
        binding.map.minZoomLevel = 2.0
        binding.map.maxZoomLevel = ZOOM_MAX_DEFAUT.toDouble()
        // Vue initiale provisoire : centre Ariège, en attendant le premier point GPS
        // (couverture de l'usage principal de l'app si la localisation est indisponible).
        binding.map.controller.setZoom(12.0)
        binding.map.controller.setCenter(GeoPoint(42.96, 1.43))

        // Position GPS du téléphone — pour que l'utilisateur sache où il se trouve par
        // rapport à la zone qu'il s'apprête à mettre en cache.
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), binding.map).apply {
            setPersonIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap())
            setPersonHotspot(10f, 10f)
            setDirectionArrow(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap(),
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap(),
            )
            // Dès le premier fix, recentrer la carte sur la position du téléphone à un zoom
            // adapté au repérage. runOnFirstFix s'exécute hors thread UI → on repasse sur
            // le main thread et on ne le fait qu'une fois (la vue peut avoir disparu).
            runOnFirstFix {
                val pos = myLocation ?: return@runOnFirstFix
                binding.map.post {
                    if (_binding == null) return@post
                    binding.map.controller.setZoom(15.0)
                    binding.map.controller.animateTo(pos)
                    majInfos()
                }
            }
        }
        binding.map.overlays.add(locationOverlay)

        binding.btnFondCarte.setOnClickListener {
            fondCarte = fondCarte.suivant()
            binding.map.setTileSource(tileSourcePour(fondCarte))
            binding.map.invalidate()
            enregistrerFondCarte(requireContext(), fondCarte)
            majInfos()
        }
        binding.btnCentrer.setOnClickListener { recentrerSurPosition() }
        binding.btnViderCache.setOnClickListener { demanderViderCache() }
        binding.btnProtocole.setOnClickListener { afficherChoixProtocole() }
        binding.btnCharger.setOnClickListener { lancerTelechargement() }

        chargerProtocolesAccessibles()

        // Met à jour les estimations à chaque mouvement de carte (zoom/pan). Pas de
        // throttling : l'estimation est purement locale (calcul de tuiles WebMercator,
        // négligeable). MapListener n'existe pas dans tous les builds osmdroid → on hooke
        // sur le scroll / zoom listener via les events Android.
        binding.map.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                majInfos(); return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                majInfos(); return false
            }
        })
        majInfos()
    }

    /** Recalcule les estimations affichées (surface, nb tuiles, poids) et met à jour
     *  l'état du bouton « Charger » selon le seuil [SURFACE_MAX_KM2]. */
    private fun majInfos() {
        if (_binding == null) return
        val bbox = binding.map.boundingBox
        val surface = surfaceKm2(bbox)
        val zoomMin = binding.map.zoomLevelDouble.toInt().coerceAtLeast(1)
        val zoomMax = ZOOM_MAX_DEFAUT
        val nbTuiles = nombreDeTuiles(bbox, zoomMin, zoomMax)
        val poidsMb = nbTuiles * poidsTuileKbMoyen(fondCarte) / 1024.0

        binding.tvSurface.text = "Surface : %.1f km² · zooms %d–%d".format(surface, zoomMin, zoomMax)
        binding.tvTuiles.text = "≈ %s tuiles · ~%.0f MB · fond : %s".format(
            "%,d".format(nbTuiles).replace(',', ' '),
            poidsMb,
            nomFond(fondCarte),
        )
        // Occupation cache disque : utile pour décider de vider avant d'enchaîner un autre
        // téléchargement, surtout au-delà de 90 % où la purge LRU auto va se déclencher
        // sous peu et risque d'évincer des tuiles qu'on vient juste de télécharger.
        val utilise = fr.ariegenature.geomys.store.MapTileCache.tailleActuelleBytes()
        val max = fr.ariegenature.geomys.store.MapTileCache.CACHE_MAX_BYTES
        val pct = if (max > 0) (utilise * 100.0 / max) else 0.0
        binding.tvCacheUtilise.text = "Cache : %d / %d MB (%.0f %%)".format(
            utilise / (1024 * 1024), max / (1024 * 1024), pct,
        )
        binding.tvCacheUtilise.setTextColor(
            if (pct >= 90.0) couleurErreur(requireContext())
            else couleurSecondaire(requireContext())
        )

        val depasse = surface > SURFACE_MAX_KM2
        binding.btnCharger.isEnabled = !depasse && nbTuiles > 0
        binding.tvAvertissement.visibility = if (depasse) View.VISIBLE else View.GONE
        if (depasse) {
            binding.tvAvertissement.text =
                "Zone trop large (max ${SURFACE_MAX_KM2.toInt()} km²) — zoomer pour réduire."
        }
    }

    private fun lancerTelechargement() {
        val bbox = binding.map.boundingBox
        val zoomMin = binding.map.zoomLevelDouble.toInt().coerceAtLeast(1)
        val zoomMax = ZOOM_MAX_DEFAUT
        val cacheManager = CacheManager(binding.map)

        @Suppress("DEPRECATION")
        val progress = ProgressDialog(requireContext()).apply {
            setTitle("Téléchargement des tuiles")
            setMessage("Préparation…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            setCancelable(false)
            show()
        }

        cacheManager.downloadAreaAsync(
            requireContext(), bbox, zoomMin, zoomMax,
            object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete() {
                    if (!isAdded) return
                    progress.dismiss()
                    majInfos()  // taille du cache a grandi
                    AlertDialog.Builder(requireContext())
                        .setTitle("Téléchargement terminé")
                        .setMessage("Les tuiles du fond ${nomFond(fondCarte)} sont " +
                            "disponibles hors-ligne pour la zone affichée.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                override fun onTaskFailed(errors: Int) {
                    if (!isAdded) return
                    progress.dismiss()
                    majInfos()
                    AlertDialog.Builder(requireContext())
                        .setTitle("Téléchargement incomplet")
                        .setMessage("$errors tuile(s) n'ont pas pu être récupérées " +
                            "(serveur, réseau ou quota). Les autres sont en cache.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                override fun updateProgress(p: Int, currentZoomLevel: Int, zMin: Int, zMax: Int) {
                    if (!isAdded) return
                    progress.progress = p
                    progress.setMessage("Zoom $currentZoomLevel · $p / ${progress.max}")
                }
                override fun downloadStarted() {
                    if (!isAdded) return
                    progress.setMessage("Téléchargement en cours…")
                }
                override fun setPossibleTilesInArea(total: Int) {
                    if (!isAdded) return
                    progress.max = total
                }
            },
        )
    }

    /** Recentre la carte sur la dernière position GPS connue du téléphone (zoom 15). Si
     *  aucun point n'est encore disponible (GPS en cours d'acquisition / permission refusée),
     *  on le signale plutôt que de bouger la carte au hasard. */
    private fun recentrerSurPosition() {
        val pos = locationOverlay?.myLocation
        if (pos == null) {
            android.widget.Toast.makeText(
                requireContext(),
                "Position GPS pas encore disponible",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        binding.map.controller.setZoom(15.0)
        binding.map.controller.animateTo(pos)
        majInfos()
    }

    /** Charge en tâche de fond les protocoles accessibles à l'utilisateur (filtrés CRUVED
     *  côté [MonitoringApi.chargerModules]). Le bouton « Listes » reste masqué tant qu'on n'a
     *  pas au moins un protocole — inutile de l'afficher si l'utilisateur n'a droit à aucun. */
    private fun chargerProtocolesAccessibles() {
        val config = GeoNatureConfig(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val modules = runCatching { MonitoringApi.chargerModules(config) }.getOrNull().orEmpty()
            if (_binding == null) return@launch
            modulesAccessibles = modules
            binding.btnProtocole.visibility = if (modules.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** Ouvre un dialog avec la liste des protocoles accessibles (déjà chargés au démarrage).
     *  À la sélection, on cadre la carte sur l'emprise des sites de ce protocole. */
    private fun afficherChoixProtocole() {
        val modules = modulesAccessibles
        if (modules.isEmpty()) return
        val labels = modules.map { "${it.moduleLabel} (${it.moduleCode})" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Cibler un protocole")
            .setItems(labels) { _, idx -> cadrerSurProtocole(modules[idx].moduleCode) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Calcule la BoundingBox englobant toutes les géométries des sites directs du protocole
     *  [moduleCode], puis recadre la carte dessus. Best-effort : on récupère la fiche de
     *  chaque site en parallèle (auth cachée 5 min côté GeoNatureAuth → 1 login pour N
     *  fetches). Les sites sans géométrie sont ignorés. */
    private fun cadrerSurProtocole(moduleCode: String) {
        val config = GeoNatureConfig(requireContext())
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(requireContext()).apply {
            setTitle("Recherche des sites…")
            setMessage("Module : $moduleCode")
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val resultat = withContext(Dispatchers.IO) { chargerSitesProtocole(config, moduleCode) }
            if (!isAdded) return@launch
            progress.dismiss()
            if (resultat.geometries.isEmpty()) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Aucun site avec géométrie pour ce protocole",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            // Remplace les overlays du protocole précédent par ceux du nouveau.
            retirerOverlaysProtocole()
            resultat.geometries.forEach { (nom, gj) -> ajouterOverlaysGeoJson(gj, nom) }
            binding.map.invalidate()
            // zoomToBoundingBox respecte les bordures écran. animate=true ajoute un fondu
            // visuel pour qu'on voie clairement le saut de zone, surtout si l'utilisateur
            // partait du centre Ariège.
            binding.map.post {
                resultat.bbox?.let { binding.map.zoomToBoundingBox(it, true, 60) }
                majInfos()
            }
        }
    }

    private data class ResultatProtocole(
        val bbox: BoundingBox?,
        /** GeoJSON brut de chaque site (avec le nom comme libellé). On garde le JSON brut
         *  pour ré-utiliser le même parseur que la BoundingBox plus bas. */
        val geometries: List<Pair<String, String>>,
    )

    private suspend fun chargerSitesProtocole(
        config: GeoNatureConfig,
        moduleCode: String,
    ): ResultatProtocole = coroutineScope {
        val enfants = runCatching { MonitoringApi.chargerEnfants(config, moduleCode) }
            .getOrNull().orEmpty()
        // Tous types confondus : on prend les enfants DIRECTS du module (= "plus haut rang"
        // de la hiérarchie monitoring). Pour un protocole STOM ça donne les sites_group ;
        // pour un protocole flat, directement les sites.
        val aFetch = enfants.flatMap { (type, items) -> items.map { type to it } }
        if (aFetch.isEmpty()) return@coroutineScope ResultatProtocole(null, emptyList())

        val geomsAvecNom: List<Pair<String, String>?> = aFetch.map { (type, e) ->
            async {
                val gj = e.geometrieGeoJson?.takeIf { it.isNotEmpty() }
                    ?: runCatching {
                        MonitoringApi.chargerObjet(config, moduleCode, type, e.id).geometrieGeoJson
                    }.getOrNull()
                if (gj.isNullOrEmpty()) null else e.nom to gj
            }
        }.awaitAll()

        val combo = BboxAccumulator()
        val valides = geomsAvecNom.filterNotNull()
        valides.forEach { (_, gj) ->
            runCatching { combo.consume(JSONObject(gj).opt("coordinates")) }
        }
        ResultatProtocole(combo.toBoundingBox(), valides)
    }

    private fun retirerOverlaysProtocole() {
        if (overlaysProtocole.isEmpty()) return
        binding.map.overlays.removeAll(overlaysProtocole)
        overlaysProtocole.clear()
    }

    /** Pose sur la carte les overlays correspondant à la géométrie GeoJSON donnée :
     *  Marker pour Point/MultiPoint, Polyline pour LineString/MultiLineString,
     *  Polygon pour Polygon/MultiPolygon. Style sobre (orange semi-transparent) pour ne pas
     *  parasiter la lecture du fond de carte. */
    private fun ajouterOverlaysGeoJson(geoJson: String, label: String) {
        val geo = runCatching { JSONObject(geoJson) }.getOrNull() ?: return
        when (geo.optString("type")) {
            "Point" -> posePoint(geo.optJSONArray("coordinates"), label)
            "MultiPoint" -> {
                val arr = geo.optJSONArray("coordinates") ?: return
                for (i in 0 until arr.length()) posePoint(arr.optJSONArray(i), label)
            }
            "LineString" -> poseLigne(geo.optJSONArray("coordinates"), label)
            "MultiLineString" -> {
                val arr = geo.optJSONArray("coordinates") ?: return
                for (i in 0 until arr.length()) poseLigne(arr.optJSONArray(i), label)
            }
            "Polygon" -> posePolygone(geo.optJSONArray("coordinates"), label)
            "MultiPolygon" -> {
                val arr = geo.optJSONArray("coordinates") ?: return
                for (i in 0 until arr.length()) posePolygone(arr.optJSONArray(i), label)
            }
        }
    }

    private fun posePoint(coords: JSONArray?, label: String) {
        if (coords == null || coords.length() < 2) return
        val pt = GeoPoint(coords.optDouble(1), coords.optDouble(0))
        val m = Marker(binding.map).apply {
            position = pt
            title = label
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            // Goutte ic_location_pin (même drawable que CarteGeometrieFragment) tintée
            // orange — cohérence visuelle avec les autres cartes de l'app.
            icon = androidx.core.content.ContextCompat
                .getDrawable(requireContext(), fr.ariegenature.geomys.R.drawable.ic_location_pin)
                ?.also { it.setTint(0xFFFF6F00.toInt()) }
        }
        binding.map.overlays.add(m)
        overlaysProtocole.add(m)
    }

    private fun poseLigne(coords: JSONArray?, label: String) {
        if (coords == null) return
        val pts = extrairePoints(coords)
        if (pts.size < 2) return
        val pl = Polyline().apply {
            setPoints(pts)
            outlinePaint.color = 0xFFFF6F00.toInt()
            outlinePaint.strokeWidth = 6f
            title = label
        }
        binding.map.overlays.add(pl)
        overlaysProtocole.add(pl)
    }

    private fun posePolygone(coords: JSONArray?, label: String) {
        if (coords == null) return
        val anneauExt = coords.optJSONArray(0) ?: return
        val pts = extrairePoints(anneauExt)
        if (pts.size < 3) return
        val poly = Polygon().apply {
            points = pts
            fillPaint.color = 0x55FF6F00
            outlinePaint.color = 0xFFFF6F00.toInt()
            outlinePaint.strokeWidth = 4f
            title = label
        }
        binding.map.overlays.add(poly)
        overlaysProtocole.add(poly)
    }

    private fun extrairePoints(arr: JSONArray): List<GeoPoint> {
        val out = mutableListOf<GeoPoint>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONArray(i) ?: continue
            if (c.length() >= 2) out.add(GeoPoint(c.optDouble(1), c.optDouble(0)))
        }
        return out
    }

    /** Petit accumulateur min/max lon/lat pour calculer la BoundingBox d'un ensemble de
     *  géométries GeoJSON (Point, LineString, Polygon, MultiPolygon, …) sans matérialiser
     *  les overlays. Récursif sur les nested arrays — robuste aux formes mixtes. */
    private class BboxAccumulator {
        var north = -90.0
        var south = 90.0
        var east = -180.0
        var west = 180.0
        var avu = false

        fun consume(coords: Any?) {
            if (coords !is JSONArray) return
            val first = coords.opt(0)
            // Heuristique pour distinguer [lon, lat] (=> 2 nombres) d'une liste de coords.
            if (first is Number && coords.length() >= 2 && coords.opt(1) is Number) {
                val lon = coords.optDouble(0)
                val lat = coords.optDouble(1)
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    north = max(north, lat); south = min(south, lat)
                    east = max(east, lon); west = min(west, lon)
                    avu = true
                }
                return
            }
            for (i in 0 until coords.length()) consume(coords.opt(i))
        }

        fun toBoundingBox(): BoundingBox? {
            if (!avu) return null
            return BoundingBox(north, east, south, west)
        }
    }

    private fun demanderViderCache() {
        AlertDialog.Builder(requireContext())
            .setTitle("Vider le cache des tuiles ?")
            .setMessage("Toutes les tuiles téléchargées (tous les fonds confondus) seront " +
                "supprimées. Tu devras les retélécharger pour un usage hors-ligne.")
            .setPositiveButton("Tout vider") { _, _ ->
                // SqlTileWriter.purgeCache vide la table SQLite osmdroid (`cache.db`) que
                // le tileProvider tient ouverte — la suppression filesystem seule ne
                // suffisait pas car le handle restait actif et le contenu réapparaissait.
                val ok = fr.ariegenature.geomys.store.MapTileCache.viderCache()
                binding.map.tileProvider.clearTileCache()
                binding.map.invalidate()
                majInfos()
                android.widget.Toast.makeText(
                    requireContext(), if (ok) "Cache vidé" else "Vidage partiel",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Surface approximative d'une BoundingBox en km², via la formule équirectangulaire.
     *  Précis à <1% pour des zones <500 km² aux latitudes tempérées — largement suffisant
     *  pour comparer à un seuil de 200 km². */
    private fun surfaceKm2(bbox: BoundingBox): Double {
        val latMid = (bbox.latNorth + bbox.latSouth) / 2.0
        val widthKm = (bbox.lonEast - bbox.lonWest) * 111.32 * cos(Math.toRadians(latMid))
        val heightKm = (bbox.latNorth - bbox.latSouth) * 110.57
        return max(0.0, widthKm * heightKm)
    }

    /** Nombre total de tuiles WebMercator dans [bbox] pour les zooms [zMin..zMax].
     *  Réplique le calcul d'osmdroid.CacheManager.possibleTilesInArea sans dépendre des
     *  internes (compatible avec les variations d'API). */
    private fun nombreDeTuiles(bbox: BoundingBox, zMin: Int, zMax: Int): Int {
        var total = 0
        for (z in zMin..zMax) {
            val n = 1 shl z  // 2^z
            val (xMin, yMax) = lonLatToTile(bbox.lonWest, bbox.latSouth, n)
            val (xMax, yMin) = lonLatToTile(bbox.lonEast, bbox.latNorth, n)
            val nx = max(0, xMax - xMin + 1)
            val ny = max(0, yMax - yMin + 1)
            total += nx * ny
        }
        return total
    }

    private fun lonLatToTile(lon: Double, lat: Double, n: Int): Pair<Int, Int> {
        val latRad = Math.toRadians(lat)
        val xT = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val yT = ((1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)
        return xT to yT
    }

    /** Poids moyen d'une tuile selon le fond, en KB. Valeurs empiriques observées :
     *  OSM/IGN-topo (PNG) ~25 KB, IGN ortho/scan25 (JPEG) ~50 KB. Sert uniquement à
     *  l'estimation affichée à l'utilisateur — pas critique. */
    private fun poidsTuileKbMoyen(fond: FondCarte): Int = when (fond) {
        FondCarte.OSM, FondCarte.TOPO, FondCarte.OPENTOPO -> 25
        FondCarte.SCAN25, FondCarte.ORTHO, FondCarte.ESRI -> 50
    }

    private fun nomFond(fond: FondCarte): String = when (fond) {
        FondCarte.OSM -> "OSM"
        FondCarte.OPENTOPO -> "OpenTopoMap"
        FondCarte.TOPO -> "IGN Topo"
        FondCarte.SCAN25 -> "IGN Scan25"
        FondCarte.ORTHO -> "IGN Ortho"
        FondCarte.ESRI -> "Esri Imagery"
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        locationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        locationOverlay?.disableMyLocation()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val SURFACE_MAX_KM2 = 200.0
        const val ZOOM_MAX_DEFAUT = 17
    }
}
