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

import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentCarteGeometrieBinding
import fr.ariegenature.geomys.network.MonitoringApi
import fr.ariegenature.geomys.network.MonitoringSync
import fr.ariegenature.geomys.store.GeoNatureConfig
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
    private var fondCarte: FondChoisi = FondChoisi.EnLigne(FondCarte.TOPO)
    private var locationOverlay: MyLocationNewOverlay? = null
    /** Schéma du protocole, chargé une fois pour les deux chemins (objet / protocole). Sert à
     *  décider, au tap sur un overlay, si on peut proposer "Nouvelle saisie" (= y a-t-il un
     *  enfant de type saisie déclaré pour ce type d'objet ?). Null tant que pas chargé ou
     *  si le serveur ne renvoie pas de schéma. */
    private var schema: Map<String, MonitoringApi.MonitoringSchemaObjet>? = null

    /** Métadonnées portées par chaque overlay cliquable : ce qu'il faut pour ouvrir la fiche
     *  ou démarrer une saisie sur l'objet cliqué. */
    private data class CarteItem(val type: String, val id: Int, val label: String)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentCarteGeometrieBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvFil.applyStatusBarMargin()
        binding.btnFondCarte.applyNavBarMargin()
        binding.llZoom.applyNavBarMargin()
        // tvTitre est positionné sous le fil par garderMargeSousFil (fil.bottom inclut déjà
        // la marge de status bar) — pas d'applyStatusBarMargin ici, sinon double comptage.

        val moduleCode = arguments?.getString("moduleCode") ?: return navUp()
        val objectType = arguments?.getString("objectType") ?: return navUp()
        val id = arguments?.getInt("id", -1)?.takeIf { it > 0 } ?: return navUp()
        val titre = arguments?.getString("titre").orEmpty()

        // Fil d'Ariane "🏠 › Monitoring › Protocole [› Site]" reconstruit depuis l'argument
        // `fil` propagé par l'écran appelant. Dernier segment = écran courant → non cliquable.
        appliquerFilAriane(
            binding.tvFil, findNavController(), moduleCode,
            decoderFil(arguments?.getString("fil")), dernierCliquable = false,
        )
        // Le badge de titre suit la hauteur réelle du fil (descend si chemin multi-lignes).
        garderMargeSousFil(binding.tvFil, binding.tvTitre)

        binding.tvTitre.text = titre
        binding.tvTitre.visibility = if (titre.isEmpty()) View.GONE else View.VISIBLE

        fondCarte = chargerFondChoisi(requireContext())
        appliquerFond(binding.map, fondCarte, requireContext())
        binding.map.setMultiTouchControls(true)
        // Boutons de zoom osmdroid désactivés au profit de nos boutons +/- (cluster bas-gauche).
        binding.map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        binding.btnZoomIn.setOnClickListener { binding.map.controller.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.map.controller.zoomOut() }

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

        binding.btnFondCarte.setOnClickListener {
            choisirFondCarte(requireContext(), fondCarte) { choisi ->
                fondCarte = choisi
                appliquerFond(binding.map, fondCarte, requireContext())
                enregistrerFondChoisi(requireContext(), fondCarte)
            }
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
        // Schéma + objet en parallèle (le schéma est utilisé par le dialog d'actions au tap
        // sur un overlay : il décide si "Nouvelle saisie" est proposée pour un type donné).
        val schemaDeferred = coroutineScope {
            async { runCatching { MonitoringApi.chargerSchemaProtocole(config, moduleCode) }.getOrNull() }
        }
        val objet = try {
            MonitoringApi.chargerObjet(config, moduleCode, objectType, id)
        } catch (e: Exception) {
            if (!isAdded) return
            binding.progressCarte.visibility = View.GONE
            binding.tvErreur.text = fr.ariegenature.geomys.network.humaniserErreurReseau(e)
            binding.tvErreur.visibility = View.VISIBLE
            return
        }
        schema = schemaDeferred.await()
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
        // Triplet (type, enfant, GeoJSON) — on conserve type+id pour pouvoir attacher des
        // actions au tap (drill fiche / nouvelle saisie sur l'enfant cliqué).
        val enfantsAvecGeo: List<Triple<String, MonitoringApi.MonitoringEnfant, String?>> =
            if (aFetch.isEmpty()) emptyList() else coroutineScope {
                aFetch.map { (ctype, e) ->
                    async {
                        val geo = e.geometrieGeoJson?.takeIf { it.isNotEmpty() }
                            ?: runCatching { MonitoringApi.chargerObjet(config, moduleCode, ctype, e.id).geometrieGeoJson }.getOrNull()
                        Triple(ctype, e, geo)
                    }
                }.awaitAll()
            }

        if (!isAdded) return
        binding.progressCarte.visibility = View.GONE

        try {
            val tous = mutableListOf<GeoPoint>()
            // Géométrie principale : label = titre passé via Bundle (le nom de l'objet ouvert).
            // On attache l'item (type/id/label) à ses overlays pour que le tap propose les
            // mêmes actions que sur ses enfants (typiquement "Nouvelle saisie" si le schéma
            // l'autorise — utile quand on est sur la carte d'un site sans points enfants).
            val titrePrincipal = arguments?.getString("titre")?.takeIf { it.isNotEmpty() } ?: objectType
            val itemPrincipal = CarteItem(type = objectType, id = id, label = titrePrincipal)
            tous += rendre(JSONObject(geoStr), estEnfant = false, item = itemPrincipal)
            enfantsAvecGeo.forEach { (ctype, e, gj) ->
                if (gj.isNullOrEmpty()) return@forEach
                val itemEnfant = CarteItem(type = ctype, id = e.id, label = e.nom)
                try { tous += rendre(JSONObject(gj), estEnfant = true, item = itemEnfant) }
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
            binding.tvErreur.text = fr.ariegenature.geomys.network.humaniserErreurReseau(e)
            binding.tvErreur.visibility = View.VISIBLE
            return
        }
        schema = MonitoringApi.chargerSchemaProtocole(config, moduleCode)
        if (!isAdded) return

        val rootsSchema = schema?.get("module")?.childrenTypes.orEmpty()
        val typesAfficher = when {
            rootsSchema.isNotEmpty() -> rootsSchema.filter { it in enfantsParType.keys }
            schema != null -> {
                // Retire les types dont le parent est lui-même présent (les enfants
                // ressortiront via drill-down sur leur parent macro).
                val enfants = enfantsParType.keys.filter { type ->
                    val pt = schema?.get(type)?.parentType
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

        // On conserve type+id+nom de chaque site pour pouvoir attacher les actions de tap.
        val enfantsAvecGeo: List<Triple<String, MonitoringApi.MonitoringEnfant, String?>> = coroutineScope {
            aFetch.map { (ctype, e) ->
                async {
                    val geo = e.geometrieGeoJson?.takeIf { it.isNotEmpty() }
                        ?: runCatching { MonitoringApi.chargerObjet(config, moduleCode, ctype, e.id).geometrieGeoJson }.getOrNull()
                    Triple(ctype, e, geo)
                }
            }.awaitAll()
        }

        if (!isAdded) return
        binding.progressCarte.visibility = View.GONE

        val tous = mutableListOf<GeoPoint>()
        enfantsAvecGeo.forEach { (ctype, e, gj) ->
            if (gj.isNullOrEmpty()) return@forEach
            val item = CarteItem(type = ctype, id = e.id, label = e.nom)
            try { tous += rendre(JSONObject(gj), estEnfant = false, item = item) }
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
     *  [item] = type/id/label de l'objet sous-jacent. Quand non-null, le tap sur l'overlay
     *  ouvre un dialog d'actions (fiche / nouvelle saisie selon le schéma). Quand null,
     *  l'overlay est passif (pas de tap). */
    private fun rendre(geo: JSONObject, estEnfant: Boolean, item: CarteItem?): List<GeoPoint> {
        val type = geo.optString("type", "")
        val coords = geo.opt("coordinates")
        val points = mutableListOf<GeoPoint>()
        when (type) {
            "Point" -> {
                val arr = coords as? JSONArray ?: return points
                val pt = lonLatToGeoPoint(arr) ?: return points
                points += pt
                ajouterMarker(pt, estEnfant, item)
            }
            "LineString" -> {
                val arr = coords as? JSONArray ?: return points
                val pts = extrairePoints(arr)
                if (pts.isEmpty()) return points
                points += pts
                ajouterPolyline(pts, estEnfant, item)
            }
            "MultiPoint" -> {
                val arr = coords as? JSONArray ?: return points
                val pts = extrairePoints(arr)
                points += pts
                pts.forEach { ajouterMarker(it, estEnfant, item) }
            }
            "Polygon" -> {
                val arr = coords as? JSONArray ?: return points
                extraireAnneaux(arr).forEach { ring ->
                    points += ring
                    ajouterPolygone(ring, estEnfant, item)
                }
            }
            "MultiPolygon" -> {
                val arr = coords as? JSONArray ?: return points
                for (i in 0 until arr.length()) {
                    val poly = arr.optJSONArray(i) ?: continue
                    extraireAnneaux(poly).forEach { ring ->
                        points += ring
                        ajouterPolygone(ring, estEnfant, item)
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

    private fun ajouterMarker(pt: GeoPoint, estEnfant: Boolean, item: CarteItem?) {
        val drawableRes = if (estEnfant) R.drawable.ic_location_pin else R.drawable.ic_location_pin
        val marker = Marker(binding.map).apply {
            position = pt
            icon = ContextCompat.getDrawable(requireContext(), drawableRes)?.also {
                // Couleur orange pour les enfants (points d'écoute), rouge pour le parent.
                it.setTint(if (estEnfant) 0xFFFF9800.toInt() else 0xFFD32F2F.toInt())
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            if (item != null && item.label.isNotEmpty()) title = item.label
            // Au tap, on substitue notre dialog d'actions à l'InfoWindow par défaut (qui se
            // contente d'afficher le titre). Si pas d'item rattaché, on garde le comportement
            // osmdroid standard (InfoWindow simple).
            if (item != null) setOnMarkerClickListener { _, _ ->
                montrerActions(item); true
            }
        }
        binding.map.overlays.add(marker)
    }

    private fun ajouterPolyline(pts: List<GeoPoint>, estEnfant: Boolean, item: CarteItem?) {
        val pl = Polyline(binding.map).apply {
            setPoints(pts)
            outlinePaint.color = if (estEnfant) 0xCCFF9800.toInt() else 0xCC2196F3.toInt()
            outlinePaint.strokeWidth = if (estEnfant) 3f else 5f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            if (item != null && item.label.isNotEmpty()) {
                title = item.label
                setOnClickListener { _, _, _ -> montrerActions(item); true }
            }
        }
        binding.map.overlays.add(pl)
    }

    private fun ajouterPolygone(ring: List<GeoPoint>, estEnfant: Boolean, item: CarteItem?) {
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
            if (item != null && item.label.isNotEmpty()) {
                title = item.label
                setOnClickListener { _, _, _ -> montrerActions(item); true }
            }
        }
        binding.map.overlays.add(poly)
    }

    /** Ouvre un AlertDialog Material proposant les actions disponibles sur [item] : drill vers
     *  la fiche, et/ou démarrage d'une nouvelle saisie (si le schéma déclare un enfant de type
     *  saisie). Quand aucune action n'est pertinente (objet sans `childrenTypes` saisie + déjà
     *  sur l'écran courant), on se contente d'un Toast avec le label. */
    private fun montrerActions(item: CarteItem) {
        val moduleCode = arguments?.getString("moduleCode") ?: return
        val parentObjectType = arguments?.getString("objectType")
        val parentId = arguments?.getInt("id", -1) ?: -1
        val cestLObjetCourant = item.type == parentObjectType && item.id == parentId

        // Type d'enfant à créer côté "+ Nouvelle saisie" : on lit le schéma du type cliqué et
        // on cherche un childrenType qui matche l'heuristique [MonitoringSync.estTypeSaisie].
        // Cohérent avec ce que font SuiviDetailFragment / FicheObjetFragment, donc même
        // comportement quel que soit le chemin d'entrée (liste vs carte).
        val schemaType = schema?.get(item.type)
        val typeSaisieEnfant = schemaType?.childrenTypes?.firstOrNull(MonitoringSync::estTypeSaisie)

        // Fil d'Ariane à propager au tap. Le carteFil contient le chemin qui a mené à cette
        // carte ; on l'étend du segment de l'item cliqué — sauf si cet item EST l'objet
        // courant (dans ce cas le segment final est déjà dans carteFil).
        val filCible = filPourItem(item, cestLObjetCourant)

        // Construction des actions disponibles. La fiche n'est pas re-proposée si on y est
        // déjà (item == objet courant de la carte) — éviterait une boucle visuelle inutile.
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (!cestLObjetCourant) {
            actions.add("Voir la fiche" to { naviguerVersFiche(moduleCode, item, filCible) })
        }
        if (typeSaisieEnfant != null) {
            actions.add(MonitoringApi.libelleNouveau(moduleCode, typeSaisieEnfant) to {
                naviguerVersNouvelleSaisie(moduleCode, item, typeSaisieEnfant, filCible)
            })
        }

        if (actions.isEmpty()) {
            // Pas d'action proposable (objet courant + pas de saisie déclarée pour ce type) :
            // on conserve un retour visuel minimal pour confirmer que le tap a été pris.
            android.widget.Toast.makeText(requireContext(), item.label, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(item.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second.invoke() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Construit le fil d'Ariane cible quand l'utilisateur tape sur [item].
     *
     *  - Cas A : l'item EST l'objet courant de la carte → on réutilise le carteFil reçu de
     *    l'appelant (il contient déjà ce segment en bout).
     *  - Cas B : l'item est un autre objet (enfant de l'objet courant, ou site macro listé
     *    sur la carte d'un protocole) → on étend le carteFil avec le segment de l'item.
     *  - Cas dégénéré : carteFil vide (compat anciennes versions sans propagation) → fallback
     *    sur un fil minimal "Suivis › Protocole [› Objet]". */
    private fun filPourItem(item: CarteItem, cestLObjetCourant: Boolean): List<FilSegment> {
        val carteFil = decoderFil(arguments?.getString("fil"))
        if (carteFil.isNotEmpty()) {
            return if (cestLObjetCourant) carteFil
            else carteFil + FilSegment(item.type, item.id, item.label)
        }
        // Fallback (carte ouverte par une ancienne version sans propagation du fil).
        val moduleCode = arguments?.getString("moduleCode").orEmpty()
        val labelModule = MonitoringApi.labelModuleEnCache(moduleCode) ?: moduleCode
        val racine = filRacineSuivis(labelModule)
        return if (cestLObjetCourant) racine else racine + FilSegment(item.type, item.id, item.label)
    }

    /** Navigation vers la fiche de [item] avec le fil [filCible] (= chemin réel dans la
     *  hiérarchie monitoring jusqu'à cet objet). */
    private fun naviguerVersFiche(moduleCode: String, item: CarteItem, filCible: List<FilSegment>) {
        findNavController().naviguerSur(
            R.id.action_carte_to_fiche,
            bundleOf(
                "moduleCode" to moduleCode,
                "objectType" to item.type,
                "id" to item.id,
                "fil" to encoderFil(filCible),
            ),
        )
    }

    /** Navigation vers le formulaire de nouvelle saisie, [item] devenant le parent et
     *  [childObjectType] le type d'objet à créer (visite / observation / …). */
    private fun naviguerVersNouvelleSaisie(
        moduleCode: String,
        item: CarteItem,
        childObjectType: String,
        filCible: List<FilSegment>,
    ) {
        findNavController().naviguerSur(
            R.id.action_carte_to_nouvelle_visite,
            bundleOf(
                "moduleCode" to moduleCode,
                "parentObjectType" to item.type,
                "parentId" to item.id,
                "titreSite" to item.label,
                "childObjectType" to childObjectType,
                "fil" to encoderFil(filCible),
            ),
        )
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
