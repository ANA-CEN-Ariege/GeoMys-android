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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentOcchabCarteBinding
import fr.ariegenature.geomys.network.envoyerSaisieOccHabVersGeoNature
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
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
    /** true quand la géométrie d'édition courante vient d'une STATION EXISTANTE (réédition) et
     *  n'a pas encore été retouchée par un tap. En réédition, deux gestes distincts : le DRAG
     *  d'un sommet/du point MODIFIE la géométrie chargée ; un TAP sur la carte en démarre une
     *  NOUVELLE — l'ancienne est alors effacée (REMPLACEMENT, pas ajout : sans ce flag, le tap
     *  ajoutait un sommet à l'ancien anneau — bug terrain 2026-08-24). Consommé au premier tap. */
    private var geometrieChargee = false
    // Marker unique du point (mode Point), markers draggables des sommets (mode Polygone) et
    // overlay de forme (polygone). Séparés pour pouvoir repeindre la forme SANS recréer les
    // markers pendant un drag (cf. TraceFragment).
    private var markerPoint: Marker? = null
    private val markersSommets = mutableListOf<Marker>()
    private var overlayForme: Overlay? = null
    // Overlays en LECTURE SEULE des autres stations déjà posées dans la session (pins/polygones
    // rouges). Redessinés séparément des overlays d'édition ; jamais draggables.
    private val overlaysSession = mutableListOf<Overlay>()
    // Sommets des stations de session : cibles d'AIMANTAGE (snapping) d'un nouveau point/sommet.
    private val sommetsSession = mutableListOf<GeoPoint>()
    // true dès qu'un cadrage initial a été imposé (géométrie courante ou stations de session) :
    // empêche le premier fix GPS de recentrer ailleurs.
    private var cadrageInitialFait = false

    // Boussole : rotation de la carte selon l'orientation du téléphone. L'ÉTAT reste ici (persiste
    // à travers la recréation de vue) ; le listener/capteurs sont factorisés dans MapCompassController.
    private var carteSuitBoussole = false
    private var boussole: MapCompassController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _binding = FragmentOcchabCarteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.bandeauSaisie.root.applyStatusBarMargin()
        // Coche verte (haut-droite) sous la barre d'état, comme la coche d'Occtax.
        binding.btnRetour.applyStatusBarMargin()
        // Bandeau d'instructions sous la barre d'état, comme la Saisie multi-taxons.
        binding.tvInstructions.applyStatusBarMargin()
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
        // Bouton « i » : rouvre le formulaire COMPLET des détails de la station (obligatoires
        // + altitudes/surface/méthode/commentaire), pré-rempli — même rôle que le « i » des
        // détails communs de la saisie multi-taxons. « Annuler » referme simplement.
        binding.btnInfosObligatoires.setOnClickListener {
            ouvrirDialogDetailsOccHab(
                requireContext(), occhabViewModel,
                fr.ariegenature.geomys.store.GeoNatureConfig(requireContext()),
                jddObligatoire = false, jddSeul = false,
            ) {}
        }

        // DÉMARRAGE d'un relevé (session neuve, infos obligatoires jamais validées) : le
        // formulaire s'affiche DÈS LA CARTE (demande terrain 2026-08-26 — avant, il
        // n'apparaissait qu'au 1er écran habitat). Pré-rempli avec les valeurs du relevé
        // précédent ; « Annuler » ramène à l'accueil ; validé → on saisit la station puis
        // ses habitats.
        if (!occhabViewModel.jddDefini) binding.root.post {
            if (isAdded && !occhabViewModel.jddDefini) ouvrirDialogDetailsOccHab(
                requireContext(), occhabViewModel,
                fr.ariegenature.geomys.store.GeoNatureConfig(requireContext()),
                jddObligatoire = true, jddSeul = true,
                onAnnule = { allerAccueil() },
            ) {}
        }

        // Poubelle de la STATION SÉLECTIONNÉE (haut-droite, sous le bandeau) : suppression
        // avec confirmation, puis retour à l'état « aucune sélection ».
        binding.btnSupprimerStation.applyStatusBarMargin()
        binding.btnSupprimerStation.setOnClickListener {
            val st = occhabViewModel.station
            val nbHab = st.habitats.size
            AlertDialog.Builder(requireContext())
                .setTitle("Supprimer la station ?")
                .setMessage(
                    "Supprimer la station sélectionnée" +
                        (if (nbHab > 0) " et ses $nbHab habitat(s)" else "") +
                        " ? Cette action est définitive.")
                .setPositiveButton("Supprimer") { _, _ ->
                    fr.ariegenature.geomys.store.OccHabStore(requireContext())
                        .supprimerStation(occhabViewModel.saisieId, st.id)
                    deselectionnerStation()
                    Toast.makeText(requireContext(), "Station supprimée", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        binding.btnCentrer.setOnClickListener {
            val loc = locationOverlay?.myLocation
            if (loc != null) binding.map.controller.animateTo(loc)
            else Toast.makeText(requireContext(), "Acquisition GPS en cours…", Toast.LENGTH_SHORT).show()
        }

        // Boussole (toggle rotation carte), comme Occtax. Capteurs/listener factorisés.
        boussole = MapCompassController(requireContext(), binding.map, binding.compass) { carteSuitBoussole }
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

        // MapView capturée en val LOCALE : runOnFirstFix s'exécute sur un thread de fond —
        // évaluer `binding` (= _binding!!) là-bas crasherait si le 1er fix tombe dans la
        // fenêtre fix→onDestroyView (audit 2026-08-23 m-N2). Le runnable posté re-vérifie
        // _binding avant de toucher l'état du fragment.
        val carte = binding.map
        locationOverlay = creerLocationOverlayBleu(carte, requireContext()).apply {
            enableMyLocation()
            runOnFirstFix {
                val loc = myLocation ?: return@runOnFirstFix
                carte.post {
                    if (_binding == null) return@post
                    if (!cadrageInitialFait && pointChoisi == null && sommets.isEmpty()) {
                        carte.controller.setZoom(16.0)
                        carte.controller.setCenter(loc)
                    }
                }
            }
        }
        binding.map.overlays.add(locationOverlay)

        val ptsCourant = preremplirDepuisViewModel()
        val ptsSession = afficherStationsSession()
        val aCadrer = ptsCourant + ptsSession
        if (aCadrer.isNotEmpty()) {
            cadrerSur(aCadrer)
        } else {
            // Ni géométrie en cours, ni station de session : cadrage large Ariège (le 1er fix GPS
            // recentrera si disponible).
            binding.map.controller.setZoom(11.0)
            binding.map.controller.setCenter(GeoPoint(42.93, 1.40))
        }

        // Coche verte : terminer la saisie OccHab (même geste qu'en Occtax).
        binding.btnRetour.setOnClickListener { terminerSaisie() }

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
        // Des stations existent déjà dans la session et aucune géométrie n'est en cours :
        // le bandeau propose sélection OU nouvelle saisie (le texte de mode revient au 1er
        // geste — tap, bouton de mode, ou sélection d'une station).
        if (ptsSession.isNotEmpty() && pointChoisi == null && sommets.isEmpty()) {
            afficherInstructionSelection()
        }
    }

    /** Précharge la géométrie de la station COURANTE (édition / retour arrière) dans les overlays
     *  d'édition et renvoie ses sommets (liste vide si station vierge). Ne recadre pas : le cadrage
     *  est décidé par l'appelant sur l'union « géométrie courante + stations de session ». */
    private fun preremplirDepuisViewModel(): List<GeoPoint> {
        // REPART DE ZÉRO : sommets/pointChoisi sont des champs d'instance qui SURVIVENT à
        // onDestroyView. Sans ce nettoyage, chaque retour sur la carte (Valider → habitat →
        // Annuler/back) RE-ajoutait les sommets par-dessus les précédents → anneau doublé
        // invisible à l'écran (markers superposés) mais sérialisé en polygone auto-croisé à
        // l'envoi (audit 2026-08-23). Même discipline qu'editerStationExistante.
        pointChoisi = null
        sommets.clear()
        val s = occhabViewModel.station
        val pts = mutableListOf<GeoPoint>()
        when {
            s.geometryType == "Polygon" && !s.geometryCoordsJson.isNullOrEmpty() -> {
                try {
                    val arr = JSONArray(s.geometryCoordsJson)
                    for (i in 0 until arr.length()) {
                        val pt = arr.getJSONArray(i)
                        val gp = GeoPoint(pt.getDouble(1), pt.getDouble(0))
                        sommets.add(gp); pts.add(gp)
                    }
                    mode = Mode.POLYGONE
                } catch (_: Exception) {}
            }
            s.geometryType == "Point" && (s.latitude != 0.0 || s.longitude != 0.0) -> {
                pointChoisi = GeoPoint(s.latitude, s.longitude)
                mode = Mode.POINT
                pts.add(pointChoisi!!)
            }
        }
        // Géométrie issue d'une station déjà définie → le prochain tap sur la carte signifie
        // « redessiner » (remplacement), pas « ajouter un sommet » (cf. [geometrieChargee]).
        geometrieChargee = pts.isNotEmpty()
        if (pts.isNotEmpty()) redessiner()
        return pts
    }

    /** Dessine, en ROUGE, les AUTRES stations de la SAISIE courante (la station en cours d'édition
     *  est exclue) : pins pour les points, contour + cercles aux sommets pour les polygones. Ces
     *  stations sont CLIQUABLES pour être rééditées ([editerStationExistante]). Renvoie tous leurs
     *  points pour le cadrage de l'emprise et alimente les cibles d'aimantage. */
    private fun afficherStationsSession(): List<GeoPoint> {
        overlaysSession.forEach { binding.map.overlays.remove(it) }
        overlaysSession.clear()
        val autres = fr.ariegenature.geomys.store.OccHabStore(requireContext())
            .stationsDeSaisie(occhabViewModel.saisieId)
            .filter { it.id != occhabViewModel.station.id }
        val pts = mutableListOf<GeoPoint>()
        val rouge = 0xFFD32F2F.toInt() // même rouge que le pin ic_pin_drop.
        autres.forEach { st ->
            if (st.geometryType == "Polygon" && !st.geometryCoordsJson.isNullOrEmpty()) {
                try {
                    val arr = JSONArray(st.geometryCoordsJson)
                    val ring = mutableListOf<GeoPoint>()
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONArray(i)
                        val gp = GeoPoint(c.getDouble(1), c.getDouble(0))
                        ring.add(gp); pts.add(gp)
                    }
                    if (ring.size >= 2) {
                        val poly = Polygon(binding.map).apply {
                            points = ring
                            fillPaint.color = 0x33D32F2F
                            outlinePaint.color = rouge
                            outlinePaint.strokeWidth = 4f
                            setOnClickListener { _, _, _ -> editerStationExistante(st); true } // tap = éditer.
                        }
                        binding.map.overlays.add(poly)
                        overlaysSession.add(poly)
                        // Chaque sommet matérialisé par un petit cercle rouge (non interactif).
                        ring.forEach { sommet ->
                            val cm = Marker(binding.map).apply {
                                position = sommet
                                icon = cercleSommet(rouge)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                isDraggable = false
                                setInfoWindow(null)
                                setOnMarkerClickListener { _, _ -> false }
                            }
                            binding.map.overlays.add(cm)
                            overlaysSession.add(cm)
                        }
                    }
                } catch (_: Exception) {}
            } else if (st.latitude != 0.0 || st.longitude != 0.0) {
                val gp = GeoPoint(st.latitude, st.longitude)
                pts.add(gp)
                val m = Marker(binding.map).apply {
                    position = gp
                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_pin_drop) // rouge natif.
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    isDraggable = false
                    setInfoWindow(null)
                    setOnMarkerClickListener { _, _ -> editerStationExistante(st); true } // tap = éditer.
                }
                binding.map.overlays.add(m)
                overlaysSession.add(m)
            }
        }
        binding.map.invalidate()
        // Ces points (sommets de polygones + points des stations ponctuelles) servent de cibles
        // d'aimantage pour la saisie de la station suivante.
        sommetsSession.clear()
        sommetsSession.addAll(pts)
        return pts
    }

    /** Recharge une station EXISTANTE de la saisie pour l'éditer, EN PLACE (sans navigation) : sa
     *  géométrie devient l'objet en cours (draggable), les autres stations restent en lecture
     *  seule. « Valider » enchaînera sur la liste des habitats (la station en porte). */
    private fun editerStationExistante(st: fr.ariegenature.geomys.model.OccHabStation) {
        // Une station déjà envoyée n'est pas rééditable : ses modifications ne repartiraient jamais
        // (elle reste marquée envoyée), et sans id serveur fiable un ré-envoi la dupliquerait.
        if (st.envoyeGeoNature) {
            Toast.makeText(requireContext(), "Station déjà envoyée — non modifiable.", Toast.LENGTH_SHORT).show()
            return
        }
        occhabViewModel.reprendreStation(st)
        // Purge l'objet en cours d'édition précédent.
        pointChoisi = null
        sommets.clear()
        markerPoint?.let { binding.map.overlays.remove(it) }; markerPoint = null
        markersSommets.forEach { binding.map.overlays.remove(it) }; markersSommets.clear()
        overlayForme?.let { binding.map.overlays.remove(it) }; overlayForme = null
        cadrageInitialFait = false
        val ptsCourant = preremplirDepuisViewModel() // charge la géométrie de st (mode + redessine)
        val ptsSession = afficherStationsSession()   // redessine les autres (st exclue désormais)
        mettreEnEvidenceBoutonMode()
        majTexteInstructionsMode() // le bandeau repasse du texte « sélection » au texte du mode
        majBoutons()
        cadrerSur(ptsCourant + ptsSession)
        Toast.makeText(requireContext(),
            "Station chargée — déplacez les sommets, ou touchez la carte pour redessiner",
            Toast.LENGTH_LONG).show()
    }

    /** Aimante [p] sur le sommet de session le plus proche s'il est à moins de ~28 dp à l'écran
     *  (seuil constant quel que soit le zoom), sinon renvoie null. Renvoie une COPIE du sommet
     *  (jamais l'instance partagée avec l'overlay de session). */
    private fun snapVersSommet(p: GeoPoint): GeoPoint? {
        if (sommetsSession.isEmpty()) return null
        val proj = binding.map.projection ?: return null
        val pPix = proj.toPixels(p, null)
        val seuilPx = 28f * resources.displayMetrics.density
        var meilleur: GeoPoint? = null
        var meilleureDist = Double.MAX_VALUE
        for (s in sommetsSession) {
            val sPix = proj.toPixels(s, null)
            val d = Math.hypot((sPix.x - pPix.x).toDouble(), (sPix.y - pPix.y).toDouble())
            if (d <= seuilPx && d < meilleureDist) { meilleureDist = d; meilleur = s }
        }
        return meilleur?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /** Cadre la carte pour englober [points] (patron de [CarteGeometrieFragment.recadrer] : box
     *  artificielle si point unique, marge sinon). Différé au prochain layout (zoomToBoundingBox
     *  exige une carte déjà mesurée). Marque le cadrage comme imposé (le 1er fix GPS ne recentre
     *  plus). */
    private fun cadrerSur(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        cadrageInitialFait = true
        // Garde _binding DANS le runnable (modèle TraceFragment.centrerSurObservations) :
        // il ré-évalue `binding` à l'exécution — NPE si la vue meurt entre post et run.
        binding.map.post { if (_binding != null) binding.map.zoomerSur(points, offset = 0.004, scale = 1.8f) }
    }

    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
        // STATION SÉLECTIONNÉE : un tap hors de sa géométrie la DÉSÉLECTIONNE et démarre une
        // NOUVELLE station (demande terrain 2026-08-26 — la sélection ne permet que modifier
        // par drag, persisté au relâcher, ou supprimer via la poubelle ; plus de redessin).
        if (geometrieChargee) {
            occhabViewModel.nouvelleStation() // la station sélectionnée reste telle quelle dans le store
            pointChoisi = null
            sommets.clear()
            geometrieChargee = false
            afficherStationsSession() // l'ex-sélection redevient rouge et cliquable
        }
        // Aimantage : si le tap tombe près d'un sommet d'une station déjà posée, on réutilise
        // exactement ce sommet (pour raccorder proprement deux stations voisines).
        val cible = snapVersSommet(p) ?: p
        when (mode) {
            Mode.POINT -> pointChoisi = cible
            Mode.POLYGONE -> sommets.add(cible)
        }
        redessiner()
        majBoutons()
        majTexteInstructionsMode() // 1er tap = début de saisie → fin du texte « sélection »
        return true
    }

    override fun longPressHelper(p: GeoPoint): Boolean = false

    /** Texte d'instructions du MODE courant (même texte que la Saisie multi-taxons). Affiché au
     *  clic sur les boutons Point/Polygone, à la sélection d'une station à modifier, et au 1er
     *  tap de saisie — cf. [afficherInstructionSelection] pour l'état initial avec stations. */
    private fun majTexteInstructionsMode() {
        binding.tvInstructions.text = when {
            // Polygone SÉLECTIONNÉ (anneau chargé d'une station) : les gestes diffèrent de la
            // saisie — tap = repartir sur un nouveau tracé, drag d'un sommet = modifier.
            mode == Mode.POLYGONE && geometrieChargee ->
                "Touchez pour saisir un nouveau polygone · appui long sur un sommet pour le modifier"
            mode == Mode.POINT && geometrieChargee ->
                "Touchez pour saisir une nouvelle station · appui long sur le point pour le déplacer"
            mode == Mode.POINT ->
                "Touchez pour placer le point · appui long pour le déplacer"
            else ->
                "Touchez pour ajouter des sommets (≥ 3) · appui long pour déplacer"
        }
    }

    /** À l'arrivée sur la carte, si la session porte DÉJÀ des stations (rouges) et qu'aucune
     *  géométrie n'est en cours, le bandeau propose les deux gestes possibles au lieu du texte
     *  de mode — demande terrain 2026-08-25. */
    private fun afficherInstructionSelection() {
        binding.tvInstructions.text =
            "Sélectionnez une station pour la modifier, ou saisissez une nouvelle station"
    }

    private fun changerMode(m: Mode) {
        mode = m
        mettreEnEvidenceBoutonMode()
        majTexteInstructionsMode()
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
                    // Tap sur le pin d'une station-point SÉLECTIONNÉE → désélection (le drag
                    // reste le geste de modification). Hors sélection : tap consommé sans effet.
                    setOnMarkerClickListener { _, _ ->
                        if (geometrieChargee) deselectionnerStation()
                        true
                    }
                    setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                        override fun onMarkerDrag(m: Marker) { pointChoisi = m.position }
                        override fun onMarkerDragEnd(m: Marker) {
                            // Aimantage au relâcher : lâché près d'un sommet d'une autre station,
                            // le point se raccorde exactement dessus (comme au tap).
                            val cible = snapVersSommet(m.position) ?: m.position
                            pointChoisi = cible
                            m.position = cible
                            majBoutons()
                            persisterSelectionApresDrag()
                            binding.map.invalidate()
                        }
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
                        override fun onMarkerDragEnd(m: Marker) {
                            // Aimantage au relâcher : lâché près d'un sommet d'une AUTRE station,
                            // le sommet déplacé se raccorde exactement dessus (comme au tap). Ne
                            // s'aimante jamais aux autres sommets du même polygone (sommetsSession
                            // = uniquement les stations voisines).
                            val i = markersSommets.indexOf(m)
                            if (i in sommets.indices) {
                                val cible = snapVersSommet(m.position) ?: m.position
                                sommets[i] = cible
                                m.position = cible
                                redessinerForme()
                            }
                            majBoutons()
                            persisterSelectionApresDrag()
                        }
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
    /** Persiste IMMÉDIATEMENT la géométrie de la station SÉLECTIONNÉE après un drag :
     *  « Valider » est désactivé pendant la sélection — la modification s'enregistre donc au
     *  relâcher du sommet/point (surface recalculée, altitudes MNT best-effort). */
    private fun persisterSelectionApresDrag() {
        if (!geometrieChargee) return
        if (mode == Mode.POINT) {
            val pt = pointChoisi ?: return
            occhabViewModel.definirGeometrie("Point", pt.latitude, pt.longitude, null)
            occhabViewModel.definirSurface(null)
        } else {
            if (sommets.size < 3) return
            val coords = JSONArray()
            var sLat = 0.0; var sLon = 0.0
            sommets.forEach { pt ->
                coords.put(JSONArray().put(pt.longitude).put(pt.latitude))
                sLat += pt.latitude; sLon += pt.longitude
            }
            occhabViewModel.definirGeometrie(
                "Polygon", sLat / sommets.size, sLon / sommets.size, coords.toString())
            occhabViewModel.definirSurface(Math.round(airePolygoneM2(sommets)))
        }
        lancerRemplissageAltitudes()
        val store = fr.ariegenature.geomys.store.OccHabStore(requireContext())
        if (!store.upsertStation(occhabViewModel.saisieId, occhabViewModel.stationAEnregistrer())) {
            alerterEchecEcritureStore(requireContext(),
                "Libérez de l'espace (photos, cache de cartes) puis re-déplacez le sommet.")
        }
    }

    /** DÉSÉLECTIONNE la station en cours d'édition (re-tap sur sa géométrie — demande terrain
     *  2026-08-25) : la station retourne à l'affichage session (rouge), l'édition repart d'une
     *  station vierge de la même saisie, le bandeau repropose sélection ou nouvelle saisie. */
    private fun deselectionnerStation() {
        occhabViewModel.nouvelleStation() // station vierge, même saisieId — l'ex-sélection n'est plus exclue de l'affichage
        pointChoisi = null
        sommets.clear()
        geometrieChargee = false
        redessiner()               // purge les overlays d'édition (pin bleu / anneau / sommets)
        afficherStationsSession()  // la station redevient rouge et cliquable
        majBoutons()
        afficherInstructionSelection()
        binding.map.invalidate()
    }

    private fun redessinerForme() {
        overlayForme?.let { binding.map.overlays.remove(it) }
        overlayForme = null
        if (mode == Mode.POLYGONE && sommets.size >= 2) {
            val poly = Polygon(binding.map).apply {
                points = sommets.toList()
                fillPaint.color = 0x552196F3
                outlinePaint.color = 0xFF1976D2.toInt()
                outlinePaint.strokeWidth = 4f
                // Pas de bulle : le constructeur Polygon(map) attache une BasicInfoWindow par
                // défaut → re-taper la géométrie affichait un POPUP VIDE. Tap sur le corps du
                // polygone : géométrie CHARGÉE (station sélectionnée) → DÉSÉLECTION ; saisie en
                // cours d'un nouveau polygone → le tap traverse (ajout de sommet).
                infoWindow = null
                setOnClickListener { _, _, _ ->
                    if (geometrieChargee) { deselectionnerStation(); true } else false
                }
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

    /** Petit disque plein (liseré blanc) pour matérialiser un sommet d'une station déjà posée. */
    private fun cercleSommet(couleur: Int): android.graphics.drawable.Drawable {
        val d = (12 * resources.displayMetrics.density).toInt()
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(couleur)
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
            setSize(d, d)
        }
    }

    private fun majBoutons() {
        // « Valider » DÉSACTIVÉ tant qu'une station est SÉLECTIONNÉE (geometrieChargee) : la
        // sélection ne permet que modifier (drag, persisté au relâcher) ou supprimer (poubelle).
        // Il se réactive dès qu'on saisit une NOUVELLE géométrie.
        val geomOk = !geometrieChargee && ((mode == Mode.POINT && pointChoisi != null) ||
            (mode == Mode.POLYGONE && sommets.size >= 3))
        binding.btnValider.isEnabled = geomOk
        binding.btnValider.alpha = if (geomOk) 1f else 0.5f
        // Poubelle de la station sélectionnée : visible seulement pendant la sélection.
        binding.btnSupprimerStation.visibility =
            if (geometrieChargee) View.VISIBLE else View.GONE
        // « Annuler » (retirer le dernier sommet) : actif SEULEMENT pendant la saisie d'une
        // NOUVELLE géométrie — pas sur l'anneau chargé d'une station sélectionnée
        // (geometrieChargee), où les gestes sont drag = modifier / tap = redessiner.
        binding.btnAnnulerPoint.isEnabled =
            mode == Mode.POLYGONE && sommets.isNotEmpty() && !geometrieChargee
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
        // Surface AUTO de la station (parité web : patchGeoValue → getAreaSize, arrondie au m²)
        // — calcul LOCAL géodésique (hors-ligne), recalculée/écrasée à chaque validation de la
        // géométrie comme sur le web (une correction manuelle tient jusqu'au prochain redessin).
        // Point → pas de surface.
        occhabViewModel.definirSurface(
            if (mode == Mode.POLYGONE) Math.round(airePolygoneM2(sommets)) else null
        )
        // Altitudes MNT (parité web : patchGeoValue → getGeoInfo) : best-effort s'il y a du
        // réseau, silencieux sinon (champs saisissables à la main dans « Détails »).
        lancerRemplissageAltitudes()
        // Persistance AU FIL DE L'EAU dès « Valider » — nouvelle station COMME réédition
        // (décision terrain 2026-08-24, revenant sur la règle « pas de station sans
        // habitat ») : la géométrie validée est enregistrée telle quelle, ENVOYABLE au
        // serveur même sans habitat (l'upload accepte une liste d'habitats vide depuis
        // v1.3.7). Corrige aussi la perte du drag d'un sommet quand on revenait à « Mes
        // stations » sans repasser par « Terminer ».
        val store = fr.ariegenature.geomys.store.OccHabStore(requireContext())
        if (!store.upsertStation(occhabViewModel.saisieId, occhabViewModel.stationAEnregistrer())) {
            alerterEchecEcritureStore(requireContext(),
                "Libérez de l'espace (photos, cache de cartes) puis revalidez la géométrie.")
            return
        }
        // Nouvelle station (aucun habitat) → écran de création directement ; station rééditée
        // (habitats déjà présents) → liste des habitats.
        if (occhabViewModel.station.habitats.isEmpty())
            findNavController().naviguerSur(R.id.action_occhab_carte_to_habitat)
        else
            findNavController().naviguerSur(R.id.action_occhab_carte_to_liste)
    }

    /** Remplit les altitudes min/max de la station courante via le MNT du serveur
     *  (`POST /geo/info` — le même appel que le web au dessin d'une géométrie). BEST-EFFORT :
     *  hors-ligne ou en erreur, il ne se passe rien (les champs restent saisissables à la main).
     *  Lancé dans le scope du VIEWMODEL (partagé au niveau Activity) : la réponse arrive
     *  généralement APRÈS la navigation vers l'écran habitat — le scope de la vue serait annulé.
     *  À l'arrivée, ne touche le ViewModel que si la station courante est TOUJOURS celle du
     *  lancement, et reporte dans le store si la station y est déjà persistée. */
    private fun lancerRemplissageAltitudes() {
        val stationId = occhabViewModel.station.id
        val saisieId = occhabViewModel.saisieId
        val s = occhabViewModel.station
        val geometry = fr.ariegenature.geomys.network.GeoNatureUpload.construireGeometrie(
            s.geometryType, s.geometryCoordsJson, s.latitude, s.longitude)
        val appContext = requireContext().applicationContext
        occhabViewModel.viewModelScope.launch {
            val config = fr.ariegenature.geomys.store.GeoNatureConfig(appContext)
            val alts = fr.ariegenature.geomys.network.OccHabApi
                .altitudesPourGeometrie(config, geometry) ?: return@launch
            if (occhabViewModel.station.id == stationId) {
                occhabViewModel.definirAltitudes(alts.first, alts.second)
            }
            // Station déjà dans le store (réédition persistée à « Valider », ou 1er habitat
            // validé entre-temps) → reporter, sinon les altitudes partiront avec la prochaine
            // persistance de la station (habitat / Terminer / Détails).
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val store = fr.ariegenature.geomys.store.OccHabStore(appContext)
                store.stationsDeSaisie(saisieId).firstOrNull { it.id == stationId }?.let {
                    store.upsertStation(saisieId,
                        it.copy(altitudeMin = alts.first, altitudeMax = alts.second))
                }
            }
        }
    }

    /** Coche verte (haut-droite) — même rôle qu'en Occtax : TERMINER la saisie OccHab en cours.
     *  Les stations validées étant déjà enregistrées au fil de l'eau, « terminer » revient à
     *  quitter vers l'accueil ; on propose aussi l'envoi immédiat (comme la coche d'Occtax offre
     *  « enregistrer et envoyer »). L'éventuelle géométrie en cours NON validée est abandonnée,
     *  comme le réticule non validé d'Occtax. */
    private fun terminerSaisie() {
        val store = fr.ariegenature.geomys.store.OccHabStore(requireContext())
        val saisie = store.charger().firstOrNull { it.id == occhabViewModel.saisieId }
        val stations = saisie?.stations ?: emptyList()
        // Aucune station enregistrée dans cette saisie → rien à conserver, on quitte directement
        // (comme la coche d'Occtax quand la sortie est vide).
        if (saisie == null || stations.isEmpty()) { allerAccueil(); return }

        val gnConfig = fr.ariegenature.geomys.store.GeoNatureConfig(requireContext())
        // Envoi proposé seulement si utile ET autorisé (config OK, CRUVED C, au moins une station) —
        // même garde que le bouton d'envoi de « Mes stations ». Les habitats sont FACULTATIFS.
        val peutEnvoyer = gnConfig.estConfiguree && gnConfig.occhabPeutCreer &&
            !saisie.envoyeGeoNature && stations.isNotEmpty()

        val optQuitter = "Enregistrer et quitter"
        val optEnvoyer = "Enregistrer et envoyer sur GeoNature"
        val optContinuer = "Continuer la saisie"
        val options = mutableListOf(optQuitter)
        if (peutEnvoyer) options.add(optEnvoyer)
        options.add(optContinuer)

        AlertDialog.Builder(requireContext())
            .setTitle("Terminer la saisie OccHab")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    optQuitter -> allerAccueil()
                    optEnvoyer -> envoyerPuisQuitter(saisie, store, gnConfig)
                    // optContinuer : ne rien faire, rester sur la carte.
                }
            }.show()
    }

    /** Regagne l'accueil (les stations validées sont déjà persistées au fil de l'eau). */
    private fun allerAccueil() {
        findNavController().popBackStack(R.id.accueilFragment, false)
    }

    /** Envoie TOUTE la saisie vers GeoNature (envoi partiel sans perte géré par le wrapper), puis
     *  regagne l'accueil. Progression bloquante minimale le temps de l'appel. */
    private fun envoyerPuisQuitter(
        saisie: fr.ariegenature.geomys.model.OccHabSaisie,
        store: fr.ariegenature.geomys.store.OccHabStore,
        gnConfig: fr.ariegenature.geomys.store.GeoNatureConfig,
    ) {
        val densite = resources.displayMetrics.density
        val contenu = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val p = (20 * densite).toInt(); setPadding(p, p, p, p)
            addView(android.widget.ProgressBar(requireContext()))
            addView(android.widget.TextView(requireContext()).apply {
                text = "Envoi de la saisie vers GeoNature…"
                setPadding((16 * densite).toInt(), 0, 0, 0)
            })
        }
        val progres = AlertDialog.Builder(requireContext())
            .setView(contenu).setCancelable(false).create()
        progres.show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = envoyerSaisieOccHabVersGeoNature(saisie, store, gnConfig)
                if (!isAdded || _binding == null) return@launch
                AlertDialog.Builder(requireContext())
                    .setTitle(if (res.succes) "Envoi" else "Erreur d'envoi")
                    .setMessage(res.message)
                    .setPositiveButton("OK") { _, _ -> allerAccueil() }
                    .show()
            } finally {
                runCatching { progres.dismiss() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        locationOverlay?.enableMyLocation()
        boussole?.demarrer()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        locationOverlay?.disableMyLocation()
        boussole?.arreter()
    }

    override fun onDestroyView() { super.onDestroyView(); boussole = null; _binding = null }
}
