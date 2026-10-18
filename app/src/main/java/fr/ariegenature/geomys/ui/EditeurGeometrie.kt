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

import android.widget.Toast
import androidx.core.content.ContextCompat
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.util.TopologiePolygone
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon

/** Un point unique, déplaçable, ou un anneau de sommets : les deux façons de poser une station. */
enum class ModeGeometrie { POINT, POLYGONE }

/**
 * La géométrie EN COURS D'ÉDITION sur la carte OccHab : son état ([mode], [pointChoisi],
 * [sommets], [trous]) et son rendu en bleu (pin draggable, anneau, sommets, poignées « + »).
 * Extrait d'`OccHabCarteFragment` (audit OccHab 2026-10-18, O1), après [StationsServeurOverlay]
 * et [StationsSessionOverlay] qui dessinent, eux, ce qui n'est PAS en cours d'édition.
 *
 * L'état est volontairement PUBLIC et mutable : le fragment le lit et l'écrit directement (tap
 * sur la carte, pré-remplissage, validation, annulation), exactement comme quand ces champs
 * vivaient chez lui. Ce qui a déménagé ici, c'est le RENDU — une dizaine d'overlays osmdroid à
 * créer, retirer et recréer dans le bon ordre, qui n'intéressait que le dessin.
 *
 * Tout ce qui dépasse le dessin reste chez l'[Hote] : aimantage, historique d'annulation,
 * persistance et boutons.
 */
class EditeurGeometrie(private val hote: Hote) {

    interface Hote {
        val contexte: android.content.Context
        val carte: MapView
        /** true quand la géométrie éditée vient d'une station EXISTANTE (sélectionnée pour
         *  modification) : les poignées « + » apparaissent et le corps du polygone avale les
         *  taps. Pendant le tracé d'une NOUVELLE géométrie, les deux sont inactifs. */
        val geometrieChargee: Boolean
        /** Sommet d'une station voisine assez proche de [p] pour s'y raccorder, ou null. */
        fun snap(p: GeoPoint): GeoPoint?
        fun memoriserAvantOperation()
        fun majBoutons()
        fun persisterApresDrag()
    }

    var mode = ModeGeometrie.POINT
    var pointChoisi: GeoPoint? = null
    val sommets = mutableListOf<GeoPoint>()

    /** Anneaux INTÉRIEURS (trous) de la géométrie en cours, ÉDITABLES sommet par sommet
     *  (demande terrain 2026-08-31) : mêmes gestes que l'anneau extérieur — drag d'un sommet,
     *  poignée « + » pour en insérer un — MÊMES COULEURS que l'anneau extérieur (demande
     *  terrain 2026-08-31 : un code couleur distinct n'apportait rien). Les trous viennent du
     *  serveur (stations dessinées sous QGIS) : l'appli n'en CRÉE pas, et un tracé REDESSINÉ
     *  repart plein (liste vidée en même temps que [sommets]). */
    val trous = mutableListOf<MutableList<GeoPoint>>()

    // Marker unique du point (mode Point), markers draggables des sommets (mode Polygone) et
    // overlay de forme (polygone). Séparés pour pouvoir repeindre la forme SANS recréer les
    // markers pendant un drag (cf. TraceFragment).
    private var markerPoint: Marker? = null
    private val markersSommets = mutableListOf<Marker>()

    /** Markers draggables des sommets de TROUS, à plat ; [reperesTrous] donne, au même index,
     *  le couple (n° d'anneau dans [trous], n° de sommet dans cet anneau). */
    private val markersTrous = mutableListOf<Marker>()
    private val reperesTrous = mutableListOf<Pair<Int, Int>>()

    /** Poignées « + » au MILIEU de chaque arête du polygone SÉLECTIONNÉ (fermeture incluse) :
     *  taper une poignée INSÈRE un sommet sur cette arête — mécanisme des candidats de QField
     *  (QfVertexModel.createCandidates) : l'insertion se fait toujours sur l'arête visée,
     *  jamais en fin d'anneau → aucun polygone auto-croisé possible. */
    private val markersPoignees = mutableListOf<Marker>()
    private var overlayForme: Overlay? = null
    private var poigneeDrawable: android.graphics.drawable.Drawable? = null

    fun redessiner() {
        retirerOverlays()

        if (mode == ModeGeometrie.POINT) {
            pointChoisi?.let { pt ->
                markerPoint = markerDraggable(pt).apply {
                    // Tap sur le pin : consommé SANS effet. Déplacer le point se fait par appui
                    // long (drag), et l'on sort de l'édition par « Annuler » — un tap ne doit
                    // rien défaire (demande utilisateur 2026-10-18).
                    setOnMarkerClickListener { _, _ -> true }
                    setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                        override fun onMarkerDrag(m: Marker) { pointChoisi = m.position }
                        override fun onMarkerDragEnd(m: Marker) {
                            // Aimantage au relâcher : lâché près d'un sommet d'une autre station,
                            // le point se raccorde exactement dessus (comme au tap).
                            val cible = hote.snap(m.position) ?: m.position
                            pointChoisi = cible
                            m.position = cible
                            hote.majBoutons()
                            hote.persisterApresDrag()
                            hote.carte.invalidate()
                        }
                        override fun onMarkerDragStart(m: Marker) { hote.memoriserAvantOperation() }
                    })
                }
                hote.carte.overlays.add(markerPoint)
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
                            // s'aimante jamais aux autres sommets du même polygone (l'hôte ne
                            // propose que les stations voisines).
                            //
                            // Le voisin, lui, NE BOUGE PAS — même si les deux sommets avaient été
                            // confondus par un aimantage (demande utilisateur 2026-10-18, qui
                            // révise la topologie partagée du 2026-08-30 comme « Annuler » l'a été
                            // la veille) : un geste ne modifie QUE la station en cours d'édition.
                            // L'aimantage sert à POSER un sommet exactement sur un autre, il ne
                            // les marie pas.
                            val i = markersSommets.indexOf(m)
                            if (i in sommets.indices) {
                                val cible = hote.snap(m.position) ?: m.position
                                sommets[i] = cible
                                m.position = cible
                                redessinerForme()
                            }
                            hote.majBoutons()
                            hote.persisterApresDrag()
                            if (hote.geometrieChargee) redessiner() // replace les poignées sur les arêtes déplacées
                        }
                        override fun onMarkerDragStart(m: Marker) { hote.memoriserAvantOperation() }
                    })
                }
                hote.carte.overlays.add(marker)
                markersSommets.add(marker)
            }
            // Poignées « + » d'AJOUT DE SOMMET (station sélectionnée seulement) : une par
            // arête, au milieu, fermeture incluse — mécanisme QField (candidats de segment).
            if (hote.geometrieChargee && sommets.size >= 3) {
                for (i in sommets.indices) {
                    val a = sommets[i]
                    val b = sommets[(i + 1) % sommets.size]
                    val milieu = TopologiePolygone.milieu(a, b)
                    val indexArete = i
                    val poignee = Marker(hote.carte).apply {
                        position = milieu
                        icon = poigneeAjout()
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        isDraggable = false
                        setInfoWindow(null)
                        setOnMarkerClickListener { _, _ -> insererSommetSurArete(indexArete); true }
                    }
                    hote.carte.overlays.add(poignee)
                    markersPoignees.add(poignee)
                }
            }
            // SOMMETS DES TROUS (anneaux intérieurs) — mêmes gestes ET mêmes couleurs que l'extérieur.
            dessinerSommetsTrous()
        }
        hote.carte.invalidate()
    }

    /**
     * Sommets des TROUS (anneaux intérieurs) : un marker draggable par sommet et, sur une
     * station sélectionnée, une poignée « + » au milieu de chaque arête — mêmes icônes et
     * mêmes couleurs que l'anneau extérieur. Mêmes gestes que l'extérieur (demande terrain
     * 2026-08-31) ; deux différences ASSUMÉES : un sommet de trou ne s'AIMANTE pas aux stations
     * voisines et ne PROPAGE rien (la topologie partagée concerne les contours extérieurs), et
     * on AVERTIT s'il sort du polygone (géométrie que PostGIS refuserait).
     */
    private fun dessinerSommetsTrous() {
        trous.forEachIndexed { r, anneau ->
            anneau.forEachIndexed { i, pt ->
                val marker = markerDraggable(pt).apply {
                    title = "Trou ${r + 1} · sommet ${i + 1}"
                    setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                        override fun onMarkerDragStart(m: Marker) { hote.memoriserAvantOperation() }
                        override fun onMarkerDrag(m: Marker) {
                            // Live update : l'anneau suit le doigt, la forme est repeinte sans
                            // recréer les markers (sinon le drag en cours saute).
                            repereDeTrou(m)?.let { (ra, ia) ->
                                trous[ra][ia] = m.position; redessinerForme()
                            }
                        }
                        override fun onMarkerDragEnd(m: Marker) {
                            repereDeTrou(m)?.let { (ra, ia) ->
                                trous[ra][ia] = m.position
                                redessinerForme()
                                avertirSiSommetTrouHorsPolygone(m.position)
                            }
                            hote.majBoutons()
                            hote.persisterApresDrag()
                            if (hote.geometrieChargee) redessiner() // replace les poignées
                        }
                    })
                }
                hote.carte.overlays.add(marker)
                markersTrous.add(marker)
                reperesTrous.add(r to i)
            }
        }
        if (!hote.geometrieChargee) return
        trous.forEachIndexed { r, anneau ->
            if (anneau.size < 3) return@forEachIndexed
            for (i in anneau.indices) {
                val indexAnneau = r
                val indexArete = i
                val milieu = TopologiePolygone.milieu(anneau[i], anneau[(i + 1) % anneau.size])
                val poignee = Marker(hote.carte).apply {
                    position = milieu
                    icon = poigneeAjout()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    isDraggable = false
                    setInfoWindow(null)
                    setOnMarkerClickListener { _, _ -> insererSommetTrou(indexAnneau, indexArete); true }
                }
                hote.carte.overlays.add(poignee)
                markersPoignees.add(poignee)
            }
        }
    }

    /** (n° d'anneau, n° de sommet) du sommet de trou porté par [m] — null si le marker n'est
     *  plus référencé (vue recréée, anneau modifié entre-temps). */
    private fun repereDeTrou(m: Marker): Pair<Int, Int>? {
        val k = markersTrous.indexOf(m)
        if (k !in reperesTrous.indices) return null
        val (r, i) = reperesTrous[k]
        return if (r in trous.indices && i in trous[r].indices) r to i else null
    }

    /** Insère un sommet au milieu d'une arête d'un TROU (poignée « + »). Aucune
     *  propagation topologique : les trous ne sont pas partagés entre stations. */
    private fun insererSommetTrou(anneau: Int, indexArete: Int) {
        if (anneau !in trous.indices) return
        val ring = trous[anneau]
        if (indexArete !in ring.indices) return
        hote.memoriserAvantOperation()
        ring.add(indexArete + 1, TopologiePolygone.milieu(ring[indexArete], ring[(indexArete + 1) % ring.size]))
        redessiner()
        hote.majBoutons()
        hote.persisterApresDrag()
    }

    /** Avertit (sans bloquer) quand un sommet de trou sort de l'anneau extérieur : la géométrie
     *  devient invalide au sens PostGIS et le serveur peut refuser la mise à jour. */
    private fun avertirSiSommetTrouHorsPolygone(p: GeoPoint) {
        if (sommets.size < 3 || TopologiePolygone.pointDansAnneau(p, sommets)) return
        Toast.makeText(hote.contexte,
            "Ce sommet du trou est sorti du polygone — GeoNature peut refuser cette géométrie.",
            Toast.LENGTH_LONG).show()
    }

    /** Insère un sommet au MILIEU de l'arête [indexArete] → [indexArete]+1 (fermeture incluse)
     *  du polygone en cours, et le persiste. Un polygone VOISIN qui partagerait cette arête
     *  n'est PAS touché : aucun geste ne modifie une autre station (demande utilisateur
     *  2026-10-18). */
    private fun insererSommetSurArete(indexArete: Int) {
        if (indexArete !in sommets.indices) return
        hote.memoriserAvantOperation()
        val milieu = TopologiePolygone.milieu(sommets[indexArete], sommets[(indexArete + 1) % sommets.size])
        sommets.add(indexArete + 1, milieu)
        redessiner()
        hote.majBoutons()
        hote.persisterApresDrag()
    }

    /** Retire de la carte le rendu courant (forme + markers), sans toucher à l'état. */
    private fun retirerOverlays() {
        overlayForme?.let { hote.carte.overlays.remove(it) }
        overlayForme = null
        markersSommets.forEach { hote.carte.overlays.remove(it) }
        markersSommets.clear()
        markersTrous.forEach { hote.carte.overlays.remove(it) }
        markersTrous.clear()
        reperesTrous.clear()
        markersPoignees.forEach { hote.carte.overlays.remove(it) }
        markersPoignees.clear()
        markerPoint?.let { hote.carte.overlays.remove(it) }
        markerPoint = null
    }

    /** Abandonne la géométrie en cours : état vidé ET rendu retiré de la carte. Le retrait est
     *  indispensable ici — on passe à l'édition d'une AUTRE station, et une station SANS
     *  géométrie ne déclenche aucun redessin : les markers de la précédente resteraient à
     *  l'écran. */
    fun effacer() {
        pointChoisi = null
        sommets.clear()
        trous.clear()
        retirerOverlays()
    }

    fun redessinerForme() {
        overlayForme?.let { hote.carte.overlays.remove(it) }
        overlayForme = null
        if (mode == ModeGeometrie.POLYGONE && sommets.size >= 2) {
            val poly = Polygon(hote.carte).apply {
                points = sommets.toList()
                // Trous en creux, dessinés depuis l'état d'ÉDITION (ils suivent le doigt).
                trous.filter { it.size >= 3 }.takeIf { it.isNotEmpty() }
                    ?.let { holes = it.map { anneau -> anneau.toList() } }
                fillPaint.color = 0x552196F3
                outlinePaint.color = 0xFF1976D2.toInt()
                outlinePaint.strokeWidth = 4f
                // Pas de bulle : le constructeur Polygon(map) attache une BasicInfoWindow par
                // défaut → re-taper la géométrie affichait un POPUP VIDE.
                //
                // Tap sur le corps du polygone : CONSOMMÉ SANS EFFET quand une station est en
                // édition (demande utilisateur 2026-10-18 — seuls l'ajout d'un sommet par une
                // poignée +, le déplacement par appui long et « Annuler » agissent ; il
                // désélectionnait auparavant, si bien qu'un doigt posé dans la forme abandonnait
                // l'édition). Pendant le tracé d'un NOUVEAU polygone, le tap doit en revanche
                // traverser pour ajouter un sommet à l'intérieur de la forme déjà esquissée.
                infoWindow = null
                setOnClickListener { _, _, _ -> hote.geometrieChargee }
            }
            hote.carte.overlays.add(poly)
            overlayForme = poly
        }
        hote.carte.invalidate()
    }

    /** Marker de sommet/point : goutte ic_pin_drop, ancrée en bas au centre, DRAGGABLE (appui
     *  long pour repositionner) — même look/comportement que la Saisie multi-taxons. */
    private fun markerDraggable(pt: GeoPoint): Marker = Marker(hote.carte).apply {
        position = pt
        icon = ContextCompat.getDrawable(hote.contexte, R.drawable.ic_pin_drop)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        isDraggable = true
        setInfoWindow(null)
    }

    /** Poignée « + » d'ajout de sommet — une seule instance, partagée par toutes les poignées
     *  (cf. IconesEditionCarte.poigneeAjout pour le détail du rendu). */
    private fun poigneeAjout(): android.graphics.drawable.Drawable =
        poigneeDrawable ?: IconesEditionCarte.poigneeAjout(hote.contexte).also { poigneeDrawable = it }

    /** Vue détruite : on lâche markers, forme et icône, qui référencent la MapView disparue.
     *  Les DONNÉES ([mode], [pointChoisi], [sommets], [trous]) survivent — le fragment les
     *  redessine sur la nouvelle carte dans onViewCreated. */
    fun liberer() {
        markersSommets.clear(); markersTrous.clear(); reperesTrous.clear(); markersPoignees.clear()
        overlayForme = null; markerPoint = null; poigneeDrawable = null
    }
}
