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

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import fr.ariegenature.geomys.model.OccHabSaisie
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.network.OccHabApi
import fr.ariegenature.geomys.network.humaniserErreurReseau
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OccHabStore
import fr.ariegenature.geomys.util.GeoJsonCoords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stations DU SERVEUR sur la carte OccHab (switch du formulaire de démarrage) : chargement pour
 * le compte ET le jeu de données de la saisie, rendu VIOLET (importable) / ORANGE pointillé
 * (déjà reprise dans une autre saisie) / rien (copie dans la saisie courante ou en édition),
 * import au tap (sans persistance tant que rien n'est modifié), bascule vers l'autre saisie.
 * Extrait de OccHabCarteFragment (audit 2026-08-27) ; le fragment reste maître de l'édition
 * via [Hote].
 */
class StationsServeurOverlay(private val hote: Hote) {

    /** Ce dont l'overlay a besoin du fragment carte (vue, ViewModel, gestes d'édition). */
    interface Hote {
        val contexte: Context
        val carte: MapView
        val vm: OccHabViewModel
        val portee: CoroutineScope
        /** false dès que la vue du fragment est détruite (après une suspension). */
        val vueVivante: Boolean
        /** Aucune géométrie en cours ni station sélectionnée (bandeau « sélection »). */
        val aucuneGeometrieEnCours: Boolean
        /** Tracé en cours NON validé (perdu à une bascule de saisie). */
        val traceEnCoursNonValide: Boolean
        fun cercleSommet(couleur: Int): Drawable
        fun editerStationExistante(st: OccHabStation)
        fun afficherStationsSession(): List<GeoPoint>
        fun cadrerSur(points: List<GeoPoint>)
        fun afficherInstructionSelection()
    }

    private val stations = mutableListOf<OccHabStation>()
    private val overlays = mutableListOf<Overlay>()
    /** Sommets dessinés (violet + orange) = cibles d'aimantage pour la saisie. */
    val sommets = mutableListOf<GeoPoint>()
    // Copies LOCALES non envoyées de stations serveur (index par idStationServeur), relues à
    // chaque rendu — cf. OccHabStore.copiesLocalesNonEnvoyees (invariant « une seule copie
    // locale à envoyer par station serveur »).
    private var copiesLocales: Map<Int, Pair<OccHabSaisie, OccHabStation>> = emptyMap()
    /** Stations dessinées en ORANGE au dernier rendu (copie en attente dans une AUTRE saisie). */
    var nbReprisesAilleurs = 0
        private set
    // id_station des stations de la SAISIE COURANTE (envoyées ou non) au dernier rendu : jamais
    // redessinées en violet (elles sont rouges/bleues), sinon un ré-import dupliquait localement.
    private var idsSaisieCourante: Set<Int> = emptySet()
    // JDD pour lequel [stations] a été chargée (invariant : compte ET JDD de la saisie).
    private var jddCharge: Int? = null

    /** Des stations serveur sont chargées (affichage demandé et serveur joignable). */
    val chargees: Boolean get() = stations.isNotEmpty()

    /** Lâche les overlays (MapView détruite) ; les DONNÉES survivent et sont redessinées par
     *  [afficher] à la recréation de la vue. */
    fun liberer() { overlays.clear() }

    /** Charge les stations de l'utilisateur DÉJÀ sur le serveur GeoNature et les affiche — leurs
     *  sommets deviennent des cibles d'aimantage — puis cadre la carte sur leur emprise (+ celles
     *  de la session). Best-effort : hors-ligne/erreur → toast humanisé, la saisie continue.
     *  SEULEMENT les stations du compte (filtre client dans chargerStations) ET du JEU DE DONNÉES
     *  de la saisie (demande terrain 2026-08-27) : filtre serveur `id_dataset` + filtre client. */
    fun charger() {
        val ctx = hote.contexte
        val appContext = ctx.applicationContext
        val idJdd = hote.vm.details.idDataset
        if (idJdd == null || idJdd <= 0) {
            Toast.makeText(ctx, "Choisissez d'abord un jeu de données", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, "Chargement de vos stations GeoNature (ce jeu de données)…", Toast.LENGTH_SHORT).show()
        hote.portee.launch {
            val chargees = try {
                OccHabApi.chargerStations(GeoNatureConfig(appContext), idDataset = idJdd)
                    .filter { it.idDataset == idJdd }
            } catch (e: Exception) {
                if (hote.vueVivante) Toast.makeText(hote.contexte, humaniserErreurReseau(e), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!hote.vueVivante) return@launch
            stations.clear()
            stations.addAll(chargees)
            jddCharge = idJdd
            val pts = afficher()
            if (chargees.isNotEmpty()) {
                // Les stations qui ont déjà une copie DANS CETTE SAISIE ne sont pas redessinées
                // (elles sont déjà rouges) : le toast l'explique.
                val nbIci = chargees.count { st -> st.idStationServeur?.let { it in idsSaisieCourante } == true }
                val detail = buildList {
                    if (nbIci > 0) add("$nbIci déjà dans cette saisie")
                    if (nbReprisesAilleurs > 0) add("$nbReprisesAilleurs reprise(s) dans une autre saisie")
                }
                Toast.makeText(hote.contexte,
                    "${chargees.size} station(s) du serveur" +
                        (if (detail.isEmpty()) " affichée(s)" else " (dont ${detail.joinToString(", ")})"),
                    Toast.LENGTH_LONG).show()
                if (hote.aucuneGeometrieEnCours) hote.afficherInstructionSelection()
                hote.cadrerSur(pts + hote.afficherStationsSession())
            } else {
                Toast.makeText(hote.contexte,
                    "Aucune station sur le serveur pour ce compte et ce jeu de données", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Le JDD de la saisie a pu changer (« i », bascule de saisie) : l'affichage doit rester celui
     *  du compte ET du JDD courant (invariant — sinon une station importée repartait en mise à jour
     *  avec un autre `id_dataset`). JDD inchangé → simple re-rendu (filtres violet/orange) ;
     *  changé → on efface, et on recharge si la saisie demande l'affichage serveur. */
    fun resynchroniserAuJdd() {
        if (stations.isEmpty() && jddCharge == null) return // jamais chargées
        val jdd = hote.vm.details.idDataset
        if (jdd == jddCharge) { afficher(); return }
        stations.clear()
        jddCharge = null
        afficher() // efface violettes / oranges
        if (hote.vm.details.chargerStationsServeur) charger()
    }

    /** (Re)dessine les stations serveur et alimente les cibles d'aimantage.
     *  Invariant « une seule copie locale non envoyée par station serveur » :
     *  - station SANS copie locale en attente → VIOLET, un tap propose l'IMPORT ;
     *  - copie (envoyée ou non) dans la SAISIE COURANTE, ou station en cours d'édition → PAS
     *    redessinée ici : elle est déjà rouge (ou bleue) via la session, et se modifie là ;
     *  - copie en attente dans une AUTRE saisie → ORANGE pointillé, un tap propose d'OUVRIR cette
     *    saisie — jamais une 2ᵉ copie.
     *  Renvoie tous les points dessinés (pour le cadrage). */
    fun afficher(): List<GeoPoint> {
        val carte = hote.carte
        overlays.forEach { carte.overlays.remove(it) }
        overlays.clear()
        sommets.clear()
        val store = OccHabStore(hote.contexte)
        copiesLocales = store.copiesLocalesNonEnvoyees()
        idsSaisieCourante = store.stationsDeSaisie(hote.vm.saisieId).mapNotNull { it.idStationServeur }.toSet()
        nbReprisesAilleurs = 0
        val violet = 0xFF8E24AA.toInt() // contour COLORÉ : le gris se perdait sur certains fonds
        val orange = couleurAvertissement()
        val pts = mutableListOf<GeoPoint>()
        stations.forEach { st ->
            val copie = st.idStationServeur?.let { copiesLocales[it] }
            val enEdition = st.idStationServeur != null && st.idStationServeur == hote.vm.station.idStationServeur
            val dansSaisieCourante = st.idStationServeur?.let { it in idsSaisieCourante } == true
            when {
                enEdition || dansSaisieCourante -> Unit
                copie == null -> pts.addAll(dessiner(st, violet, 0x268E24AA, pointille = false) { proposerImport(st) })
                copie.first.id == hote.vm.saisieId -> Unit // déjà rouge/bleue (session)
                else -> {
                    nbReprisesAilleurs++
                    pts.addAll(dessiner(st, orange, 0x26E65100, pointille = true) {
                        proposerOuvrirCopieLocale(copie.first, copie.second)
                    })
                }
            }
        }
        carte.invalidate()
        return pts
    }

    /** Dessine UNE station (contour + remplissage pour un polygone, cercle pour un point), non
     *  draggable, avec [onTap] au clic ; ses sommets rejoignent les cibles d'aimantage. */
    private fun dessiner(
        st: OccHabStation, couleur: Int, remplissage: Int, pointille: Boolean, onTap: () -> Unit,
    ): List<GeoPoint> {
        val carte = hote.carte
        val pts = mutableListOf<GeoPoint>()
        if (st.geometryType == "Polygon" && !st.geometryCoordsJson.isNullOrEmpty()) {
            val ring = GeoJsonCoords.parse(st.geometryCoordsJson)
            pts.addAll(ring)
            if (ring.size >= 2) {
                val poly = Polygon(carte).apply {
                    points = ring
                    fillPaint.color = remplissage
                    outlinePaint.color = couleur
                    outlinePaint.strokeWidth = 4f
                    if (pointille) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
                    infoWindow = null
                    setOnClickListener { _, _, _ -> onTap(); true }
                }
                carte.overlays.add(poly)
                overlays.add(poly)
                sommets.addAll(ring)
            }
        } else if (st.latitude != 0.0 || st.longitude != 0.0) {
            val gp = GeoPoint(st.latitude, st.longitude)
            pts.add(gp)
            val m = Marker(carte).apply {
                position = gp
                icon = hote.cercleSommet(couleur)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                isDraggable = false
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> onTap(); true }
            }
            carte.overlays.add(m)
            overlays.add(m)
            sommets.add(gp)
        }
        return pts
    }

    /** Une copie locale NON ENVOYÉE de cette station existe dans une AUTRE saisie de « Mes
     *  stations » : jamais de 2ᵉ copie — on propose d'OUVRIR cette saisie et d'y poursuivre. */
    fun proposerOuvrirCopieLocale(saisie: OccHabSaisie, copie: OccHabStation) {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(saisie.date))
        val traceEnCours = hote.traceEnCoursNonValide
        AlertDialog.Builder(hote.contexte)
            .setTitle("Station déjà en cours de modification")
            .setMessage(
                (copie.stationName?.let { "« $it »\n\n" } ?: "") +
                    "Cette station est déjà reprise dans la saisie du $date (Mes stations), pas " +
                    "encore envoyée. Pour ne pas créer deux versions concurrentes, poursuivez vos " +
                    "modifications dans cette saisie." +
                    (if (traceEnCours) "\n\nLe tracé en cours (non validé) sera abandonné." else ""))
            .setPositiveButton("Ouvrir cette saisie") { _, _ -> basculerVersSaisie(saisie, copie) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Bascule la carte sur une AUTRE saisie et y ouvre [station] en édition — même résultat que
     *  Mes stations → carte → tap sur la station. Les stations validées de la saisie quittée sont
     *  persistées au fil de l'eau : seul un tracé non validé disparaît. */
    private fun basculerVersSaisie(saisie: OccHabSaisie, station: OccHabStation) {
        val store = OccHabStore(hote.contexte)
        // Version FRAÎCHE de la station (le store a pu bouger depuis le dessin).
        val aOuvrir = store.stationsDeSaisie(saisie.id).firstOrNull { it.id == station.id } ?: station
        hote.vm.reprendreSaisie(saisie)
        hote.editerStationExistante(aOuvrir) // purge l'édition en cours, redessine la session ouverte
        resynchroniserAuJdd() // re-filtre violet/orange — ou recharge/efface si le JDD diffère
    }

    /** Tap sur une station VIOLETTE : confirmation avant IMPORT dans la saisie courante — la
     *  station devient éditable comme une station de session et repartira en MISE À JOUR. */
    private fun proposerImport(st: OccHabStation) {
        // Filet : le store a pu changer depuis le dessin. Une copie non envoyée existe déjà →
        // on l'ouvre, jamais de 2ᵉ copie.
        val copie = st.idStationServeur?.let { OccHabStore(hote.contexte).copieLocaleNonEnvoyee(it) }
        if (copie != null) {
            if (copie.first.id == hote.vm.saisieId) hote.editerStationExistante(copie.second)
            else proposerOuvrirCopieLocale(copie.first, copie.second)
            return
        }
        val nbHab = st.habitats.size
        AlertDialog.Builder(hote.contexte)
            .setTitle("Modifier cette station du serveur ?")
            .setMessage(
                (st.stationName?.let { "« $it »\n\n" } ?: "") +
                    "La station" + (if (nbHab > 0) " et ses $nbHab habitat(s)" else "") +
                    " sera ouverte dans la saisie en cours. Elle n'entrera dans Mes stations " +
                    "qu'une fois modifiée, puis repartira à GeoNature en MISE À JOUR à l'envoi " +
                    "(pas de doublon).")
            .setPositiveButton("Modifier") { _, _ -> importer(st) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** IMPORTE une station serveur dans la saisie courante : ouverte en édition avec
     *  envoyeGeoNature=false en CONSERVANT idStationServeur/uuidStation/origineServeur (envoi en
     *  update). RIEN n'est écrit dans « Mes stations » : l'empreinte d'origine est figée et la
     *  station n'y entrera qu'à la première modification réelle. Détails PAR STATION : elle garde
     *  SES observateurs/commentaire/nomenclatures ; seules ses DATES prennent celles de la session
     *  (une reprise sur le terrain est une nouvelle observation — décision 2026-08-27). */
    private fun importer(st: OccHabStation) {
        val defauts = hote.vm.defautsSession
        hote.editerStationExistante(st.copy(
            envoyeGeoNature = false, envoiIncertain = false,
            dateMin = defauts.dateMin ?: st.dateMin, dateMax = defauts.dateMax ?: st.dateMax,
        ))
        hote.vm.figerEmpreinteOrigine()
        afficher() // son tracé violet disparaît : c'est la station en cours d'édition
    }
}
