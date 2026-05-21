package fr.ariegenature.geonat.ui

import android.app.AlertDialog
import android.app.ProgressDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.databinding.FragmentCacheManagerBinding
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.store.GeoNatureConfig
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentCacheManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRetour.applyStatusBarMargin()
        // Le conteneur vertical à droite porte les 3 boutons d'action — un seul appel
        // d'inset (au niveau du parent) suffit pour les décaler tous sous la status bar.
        binding.conteneurActions.applyStatusBarMargin()
        binding.tvTitre.applyStatusBarMargin()
        // Le panel bas est ancré sur le bord de l'écran : il doit garder un padding bottom
        // suffisant pour ne pas être occulté par la nav bar gestuelle / 3-boutons.
        binding.panelBas.applyNavBarInset(includeIme = true)

        binding.map.setTileSource(tileSourcePour(fondCarte))
        binding.map.setMultiTouchControls(true)
        binding.map.minZoomLevel = 2.0
        binding.map.maxZoomLevel = ZOOM_MAX_DEFAUT.toDouble()
        // Vue initiale : centre Ariège (couverture de l'usage principal de l'app).
        binding.map.controller.setZoom(12.0)
        binding.map.controller.setCenter(GeoPoint(42.96, 1.43))

        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }
        binding.btnFondCarte.setOnClickListener {
            fondCarte = fondCarte.suivant()
            binding.map.setTileSource(tileSourcePour(fondCarte))
            binding.map.invalidate()
            majInfos()
        }
        binding.btnViderCache.setOnClickListener { demanderViderCache() }
        binding.btnProtocole.setOnClickListener { afficherChoixProtocole() }
        binding.btnCharger.setOnClickListener { lancerTelechargement() }

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
        val utilise = fr.ariegenature.geonat.store.MapTileCache.tailleActuelleBytes()
        val max = fr.ariegenature.geonat.store.MapTileCache.CACHE_MAX_BYTES
        val pct = if (max > 0) (utilise * 100.0 / max) else 0.0
        binding.tvCacheUtilise.text = "Cache : %d / %d MB (%.0f %%)".format(
            utilise / (1024 * 1024), max / (1024 * 1024), pct,
        )
        binding.tvCacheUtilise.setTextColor(if (pct >= 90.0) 0xFFC62828.toInt() else 0xFF666666.toInt())

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

    /** Ouvre un dialog avec la liste des modules monitoring. À la sélection, on lance le
     *  cadrage de la carte sur l'emprise des sites de ce protocole. La liste vient du même
     *  endpoint que l'écran « Suivis » → réutilise le cache local quand le serveur est
     *  injoignable. */
    private fun afficherChoixProtocole() {
        val config = GeoNatureConfig(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val modules = runCatching { MonitoringApi.chargerModules(config) }.getOrNull().orEmpty()
            if (!isAdded) return@launch
            if (modules.isEmpty()) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Aucun protocole disponible (cache vide ou serveur injoignable)",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val labels = modules.map { "${it.moduleLabel} (${it.moduleCode})" }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("Cibler un protocole")
                .setItems(labels) { _, idx -> cadrerSurProtocole(modules[idx].moduleCode) }
                .setNegativeButton("Annuler", null)
                .show()
        }
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
                .getDrawable(requireContext(), fr.ariegenature.geonat.R.drawable.ic_location_pin)
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
                val ok = fr.ariegenature.geonat.store.MapTileCache.viderCache()
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
        FondCarte.OSM, FondCarte.TOPO -> 25
        FondCarte.SCAN25, FondCarte.ORTHO -> 50
    }

    private fun nomFond(fond: FondCarte): String = when (fond) {
        FondCarte.OSM -> "OSM"
        FondCarte.TOPO -> "IGN Topo"
        FondCarte.SCAN25 -> "IGN Scan25"
        FondCarte.ORTHO -> "IGN Ortho"
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
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
