package fr.ariegenature.geonat.ui

import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentCarteGeometrieBinding
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** Affiche la géométrie d'un objet monitoring (Point, LineString, Polygon, MultiPolygon)
 *  sur une carte plein écran. Pas dépendant du type d'objet : on rend ce qui arrive. */
class CarteGeometrieFragment : Fragment() {
    private var _binding: FragmentCarteGeometrieBinding? = null
    private val binding get() = _binding!!
    private var fondCarte = FondCarte.TOPO
    private var locationOverlay: MyLocationNewOverlay? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentCarteGeometrieBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRetour.applyStatusBarMargin()
        binding.btnFondCarte.applyStatusBarMargin()
        binding.tvTitre.applyStatusBarMargin()

        val moduleCode = arguments?.getString("moduleCode") ?: return navUp()
        val objectType = arguments?.getString("objectType") ?: return navUp()
        val id = arguments?.getInt("id", -1)?.takeIf { it > 0 } ?: return navUp()
        val titre = arguments?.getString("titre").orEmpty()

        binding.tvTitre.text = titre
        binding.tvTitre.visibility = if (titre.isEmpty()) View.GONE else View.VISIBLE

        binding.map.setTileSource(tileSourcePour(fondCarte))
        binding.map.setMultiTouchControls(true)

        // Position GPS du téléphone : même look bleu que les autres cartes de l'app
        // (TraceFragment, SaisieRapideFragment). N'auto-centre PAS sur la position courante
        // — on garde le centrage sur la géom de l'objet (cf. recadrer plus bas).
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), binding.map).apply {
            setPersonIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap())
            setPersonHotspot(10f, 10f)
            setDirectionArrow(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap(),
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_gps_blue_dot)?.toBitmap(),
            )
        }
        binding.map.overlays.add(locationOverlay)

        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }
        binding.btnFondCarte.setOnClickListener {
            fondCarte = fondCarte.suivant()
            binding.map.setTileSource(tileSourcePour(fondCarte))
            binding.map.invalidate()
        }

        chargerEtAfficher(moduleCode, objectType, id)
    }

    private fun navUp() { findNavController().navigateUp() }

    private fun chargerEtAfficher(moduleCode: String, objectType: String, id: Int) {
        binding.progressCarte.visibility = View.VISIBLE
        binding.tvErreur.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val config = GeoNatureConfig(requireContext())
            if (objectType == "module") chargerProtocole(config, moduleCode)
            else chargerObjet(config, moduleCode, objectType, id)
        }
    }

    /** Carte d'un objet (site, sites_group, visite, …) : sa géométrie + celle de ses enfants. */
    private suspend fun chargerObjet(config: GeoNatureConfig, moduleCode: String, objectType: String, id: Int) {
        val objet = try {
            MonitoringApi.chargerObjet(config, moduleCode, objectType, id)
        } catch (e: Exception) {
            if (!isAdded) return
            binding.progressCarte.visibility = View.GONE
            binding.tvErreur.text = fr.ariegenature.geonat.network.humaniserErreurReseau(e)
            binding.tvErreur.visibility = View.VISIBLE
            return
        }
        if (!isAdded) return
        val geoStr = objet.geometrieGeoJson
        if (geoStr.isNullOrEmpty()) {
            binding.progressCarte.visibility = View.GONE
            binding.tvErreur.text = "Pas de géométrie pour cet objet."
            binding.tvErreur.visibility = View.VISIBLE
            return
        }

        // L'API ne renvoie pas la `geometry` des enfants à depth=1 — il faut fetcher chaque
        // enfant individuellement. Auth est cachée 5min côté GeoNatureAuth → seul le 1er login
        // déclenche la requête /auth/login, le reste réutilise le token. Les échecs par enfant
        // sont avalés (best-effort) pour ne pas planter la carte entière sur un 403 isolé.
        val aFetch: List<Pair<String, MonitoringApi.MonitoringEnfant>> =
            objet.enfants.flatMap { (ctype, items) -> items.map { ctype to it } }
        val enfantsAvecGeo: List<Pair<String, String?>> = if (aFetch.isEmpty()) emptyList() else coroutineScope {
            aFetch.map { (ctype, e) ->
                async {
                    val geo = e.geometrieGeoJson?.takeIf { it.isNotEmpty() }
                        ?: runCatching { MonitoringApi.chargerObjet(config, moduleCode, ctype, e.id).geometrieGeoJson }.getOrNull()
                    e.nom to geo
                }
            }.awaitAll()
        }

        if (!isAdded) return
        binding.progressCarte.visibility = View.GONE

        try {
            val tous = mutableListOf<GeoPoint>()
            // Géométrie principale : label = titre passé via Bundle (le nom de l'objet ouvert).
            val titrePrincipal = arguments?.getString("titre")?.takeIf { it.isNotEmpty() }
            tous += rendre(JSONObject(geoStr), estEnfant = false, label = titrePrincipal)
            enfantsAvecGeo.forEach { (nom, gj) ->
                if (gj.isNullOrEmpty()) return@forEach
                try { tous += rendre(JSONObject(gj), estEnfant = true, label = nom) }
                catch (_: Exception) { /* enfant illisible, on l'ignore */ }
            }
            terminer(tous)
        } catch (e: Exception) {
            binding.tvErreur.text = "Géométrie illisible : ${e.message}"
            binding.tvErreur.visibility = View.VISIBLE
        }
    }

    /** Carte d'un protocole entier : tous les sites macro avec leur géométrie. Identifie les
     *  types macro via le schéma (children de "module"), avec fallback purement structurel
     *  qui masque les types dont le parent est lui-même dans la liste. */
    private suspend fun chargerProtocole(config: GeoNatureConfig, moduleCode: String) {
        val enfantsParType = try {
            MonitoringApi.chargerEnfants(config, moduleCode)
        } catch (e: Exception) {
            if (!isAdded) return
            binding.progressCarte.visibility = View.GONE
            binding.tvErreur.text = fr.ariegenature.geonat.network.humaniserErreurReseau(e)
            binding.tvErreur.visibility = View.VISIBLE
            return
        }
        val schema = MonitoringApi.chargerSchemaProtocole(config, moduleCode)
        if (!isAdded) return

        val rootsSchema = schema?.get("module")?.childrenTypes.orEmpty()
        val typesAfficher = when {
            rootsSchema.isNotEmpty() -> rootsSchema.filter { it in enfantsParType.keys }
            schema != null -> {
                // Retire les types dont le parent est lui-même présent (les enfants
                // ressortiront via drill-down sur leur parent macro).
                val enfants = enfantsParType.keys.filter { type ->
                    val pt = schema[type]?.parentType
                    pt != null && pt != "module" && pt in enfantsParType.keys
                }.toSet()
                enfantsParType.keys.filter { it !in enfants }
            }
            else -> enfantsParType.keys.toList()
        }

        val aFetch: List<Pair<String, MonitoringApi.MonitoringEnfant>> =
            typesAfficher.flatMap { ctype -> enfantsParType[ctype].orEmpty().map { ctype to it } }
        if (aFetch.isEmpty()) {
            binding.progressCarte.visibility = View.GONE
            binding.tvErreur.text = "Aucun site avec géométrie pour ce protocole."
            binding.tvErreur.visibility = View.VISIBLE
            return
        }

        val enfantsAvecGeo: List<Pair<String, String?>> = coroutineScope {
            aFetch.map { (ctype, e) ->
                async {
                    val geo = e.geometrieGeoJson?.takeIf { it.isNotEmpty() }
                        ?: runCatching { MonitoringApi.chargerObjet(config, moduleCode, ctype, e.id).geometrieGeoJson }.getOrNull()
                    e.nom to geo
                }
            }.awaitAll()
        }

        if (!isAdded) return
        binding.progressCarte.visibility = View.GONE

        val tous = mutableListOf<GeoPoint>()
        enfantsAvecGeo.forEach { (nom, gj) ->
            if (gj.isNullOrEmpty()) return@forEach
            try { tous += rendre(JSONObject(gj), estEnfant = false, label = nom) }
            catch (_: Exception) { /* géo illisible, on l'ignore */ }
        }
        terminer(tous)
    }

    private fun terminer(tous: List<GeoPoint>) {
        if (tous.isEmpty()) {
            binding.tvErreur.text = "Aucune géométrie à afficher."
            binding.tvErreur.visibility = View.VISIBLE
        } else {
            binding.map.post { recadrer(tous) }
        }
    }

    /** Ajoute les overlays correspondant à une géométrie GeoJSON sur la carte et renvoie tous
     *  les GeoPoint qu'elle couvre (pour le recadrage global). [estEnfant] = style plus discret.
     *  [label] = nom à afficher au tap sur le marker/polygone (titre InfoWindow / Toast). */
    private fun rendre(geo: JSONObject, estEnfant: Boolean, label: String?): List<GeoPoint> {
        val type = geo.optString("type", "")
        val coords = geo.opt("coordinates")
        val points = mutableListOf<GeoPoint>()
        when (type) {
            "Point" -> {
                val arr = coords as? JSONArray ?: return points
                val pt = lonLatToGeoPoint(arr) ?: return points
                points += pt
                ajouterMarker(pt, estEnfant, label)
            }
            "LineString" -> {
                val arr = coords as? JSONArray ?: return points
                val pts = extrairePoints(arr)
                if (pts.isEmpty()) return points
                points += pts
                ajouterPolyline(pts, estEnfant, label)
            }
            "MultiPoint" -> {
                val arr = coords as? JSONArray ?: return points
                val pts = extrairePoints(arr)
                points += pts
                pts.forEach { ajouterMarker(it, estEnfant, label) }
            }
            "Polygon" -> {
                val arr = coords as? JSONArray ?: return points
                extraireAnneaux(arr).forEach { ring ->
                    points += ring
                    ajouterPolygone(ring, estEnfant, label)
                }
            }
            "MultiPolygon" -> {
                val arr = coords as? JSONArray ?: return points
                for (i in 0 until arr.length()) {
                    val poly = arr.optJSONArray(i) ?: continue
                    extraireAnneaux(poly).forEach { ring ->
                        points += ring
                        ajouterPolygone(ring, estEnfant, label)
                    }
                }
            }
        }
        return points
    }

    private fun lonLatToGeoPoint(arr: JSONArray): GeoPoint? {
        if (arr.length() < 2) return null
        val lon = arr.optDouble(0, Double.NaN)
        val lat = arr.optDouble(1, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return GeoPoint(lat, lon)
    }

    private fun extrairePoints(arr: JSONArray): List<GeoPoint> {
        val list = mutableListOf<GeoPoint>()
        for (i in 0 until arr.length()) {
            val coord = arr.optJSONArray(i) ?: continue
            lonLatToGeoPoint(coord)?.let { list += it }
        }
        return list
    }

    private fun extraireAnneaux(polyArr: JSONArray): List<List<GeoPoint>> {
        val anneaux = mutableListOf<List<GeoPoint>>()
        for (i in 0 until polyArr.length()) {
            val ring = polyArr.optJSONArray(i) ?: continue
            val pts = extrairePoints(ring)
            if (pts.isNotEmpty()) anneaux += pts
        }
        return anneaux
    }

    private fun ajouterMarker(pt: GeoPoint, estEnfant: Boolean, label: String?) {
        val drawableRes = if (estEnfant) R.drawable.ic_location_pin else R.drawable.ic_location_pin
        val marker = Marker(binding.map).apply {
            position = pt
            icon = ContextCompat.getDrawable(requireContext(), drawableRes)?.also {
                // Couleur orange pour les enfants (points d'écoute), rouge pour le parent.
                it.setTint(if (estEnfant) 0xFFFF9800.toInt() else 0xFFD32F2F.toInt())
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            if (!label.isNullOrEmpty()) {
                title = label
                // osmdroid affiche un InfoWindow par défaut au tap sur le marker, avec title/snippet.
            }
        }
        binding.map.overlays.add(marker)
    }

    private fun ajouterPolyline(pts: List<GeoPoint>, estEnfant: Boolean, label: String?) {
        val pl = Polyline(binding.map).apply {
            setPoints(pts)
            outlinePaint.color = if (estEnfant) 0xCCFF9800.toInt() else 0xCC2196F3.toInt()
            outlinePaint.strokeWidth = if (estEnfant) 3f else 5f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            if (!label.isNullOrEmpty()) {
                title = label
                setOnClickListener { _, _, _ ->
                    android.widget.Toast.makeText(requireContext(), label, android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
        binding.map.overlays.add(pl)
    }

    private fun ajouterPolygone(ring: List<GeoPoint>, estEnfant: Boolean, label: String?) {
        val poly = Polygon(binding.map).apply {
            points = ring
            if (estEnfant) {
                fillPaint.color = 0x44FF9800.toInt()
                outlinePaint.color = 0xFFE65100.toInt()
                outlinePaint.strokeWidth = 2f
            } else {
                fillPaint.color = 0x552196F3.toInt()
                outlinePaint.color = 0xFF1976D2.toInt()
                outlinePaint.strokeWidth = 3f
            }
            if (!label.isNullOrEmpty()) {
                title = label
                setOnClickListener { _, _, _ ->
                    android.widget.Toast.makeText(requireContext(), label, android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
        binding.map.overlays.add(poly)
    }

    private fun recadrer(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        // On utilise zoomToBoundingBox dans tous les cas (même 1 point) car c'est la seule
        // méthode qui force fiablement un layout/refresh avec le bon centre+zoom dès le
        // 1er rendu. setCenter+setZoom directs peuvent être ignorés si la MapView n'a pas
        // encore été mesurée au moment du post.
        val box = if (points.size == 1) {
            // Box artificielle autour du point unique : ~440 m de côté → zoom ~15-16.
            val pt = points[0]
            val offset = 0.002  // ≈ 220 m N-S, 165 m E-W aux latitudes tempérées
            BoundingBox(
                pt.latitude + offset, pt.longitude + offset,
                pt.latitude - offset, pt.longitude - offset,
            )
        } else BoundingBox.fromGeoPoints(points)
        val degenere = (box.latNorth - box.latSouth) < 0.0001 && (box.lonEast - box.lonWest) < 0.0001
        if (degenere) {
            val pt = points[0]
            val offset = 0.002
            val fallback = BoundingBox(
                pt.latitude + offset, pt.longitude + offset,
                pt.latitude - offset, pt.longitude - offset,
            )
            binding.map.zoomToBoundingBox(fallback, false)
        } else {
            binding.map.zoomToBoundingBox(box.increaseByScale(1.3f), false)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        // Démarre la souscription au GPS uniquement quand on est à l'écran (coupe quand on
        // sort pour libérer le récepteur).
        locationOverlay?.enableMyLocation()
    }
    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        locationOverlay?.disableMyLocation()
    }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
