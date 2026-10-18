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
import android.graphics.drawable.Drawable
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.store.OccHabStore
import fr.ariegenature.geomys.util.GeoJsonCoords
import androidx.core.content.ContextCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon

/**
 * Stations LOCALES dessinées en rouge sur la carte OccHab : celles qui restent à envoyer, toutes
 * saisies confondues. Extrait d'`OccHabCarteFragment` (audit OccHab 2026-10-18, O1), sur le même
 * modèle que [StationsServeurOverlay].
 *
 * Ce qui N'EST PAS dessiné ici : la station en cours d'édition (les overlays d'édition s'en
 * chargent, en bleu) et les copies locales DÉJÀ ENVOYÉES — si la station existe encore sur
 * GeoNature, le cache serveur la porte dans sa version serveur ; si elle n'y est plus, l'afficher
 * serait un fantôme (constat terrain 2026-10-18).
 *
 * Les points produits servent de cibles d'aimantage au dessin ([sommets]).
 */
class StationsSessionOverlay(private val hote: Hote) {

    interface Hote {
        val contexte: Context
        val carte: MapView
        val vm: OccHabViewModel
        val store: OccHabStore
        /** true quand une station est en cours d'édition : on ne bascule alors pas d'une station
         *  à l'autre d'un tap (on sort d'abord par « Annuler »). */
        val editionEnCours: Boolean
        fun cercleSommet(couleur: Int): Drawable
        fun editerStationExistante(st: OccHabStation)
    }

    private val overlays = mutableListOf<Overlay>()

    /** Sommets des polygones + points des stations ponctuelles — cibles d'aimantage. */
    val sommets = mutableListOf<GeoPoint>()

    /** Empreinte du dernier rendu et points qu'il avait produits. Reconstruire ces overlays coûte
     *  cher (un Polygon ou un Marker par station, plus leurs listeners) et [afficher] est appelée
     *  jusqu'à neuf fois par geste, en cascade — le plus souvent pour un rendu IDENTIQUE
     *  (audit OccHab 2026-10-18, O2). */
    private var empreinte: String? = null
    private var pointsRendus: List<GeoPoint> = emptyList()

    /** [toutesLesSaisies] = false : seulement la saisie courante (chemin d'édition historique). */
    fun afficher(toutesLesSaisies: Boolean = true): List<GeoPoint> {
        val autres = (if (toutesLesSaisies)
                hote.store.charger().flatMap { it.stations }.filterNot { it.envoyeGeoNature }
            else hote.store.stationsDeSaisie(hote.vm.saisieId))
            .filter { it.id != hote.vm.station.id }

        // Rien n'a changé depuis le dernier rendu : on garde les overlays en place. L'empreinte
        // porte sur ce qui est DESSINÉ — identité, géométrie et trous de chaque station — plus
        // la station exclue (celle en cours d'édition), qui change le contenu de la couche.
        val nouvelle = autres.joinToString("|") {
            "${it.id}:${it.geometryType}:${it.geometryCoordsJson?.length}:${it.latitude},${it.longitude}" +
                ":${it.geometryTrousJson?.length}"
        } + "#${hote.vm.station.id}"
        if (nouvelle == empreinte && overlays.isNotEmpty()) return pointsRendus
        empreinte = nouvelle

        overlays.forEach { hote.carte.overlays.remove(it) }
        overlays.clear()
        val pts = mutableListOf<GeoPoint>()
        val rouge = 0xFFD32F2F.toInt() // même rouge que le pin ic_pin_drop.
        autres.forEach { st ->
            if (st.geometryType == "Polygon" && !st.geometryCoordsJson.isNullOrEmpty()) {
                try {
                    val ring = GeoJsonCoords.parse(st.geometryCoordsJson)
                    pts.addAll(ring)
                    if (ring.size >= 2) {
                        val poly = Polygon(hote.carte).apply {
                            points = ring
                            // Trous (polygone dessiné sous QGIS) : dessinés en creux.
                            GeoJsonCoords.parseAnneaux(st.geometryTrousJson)
                                .takeIf { it.isNotEmpty() }?.let { holes = it }
                            fillPaint.color = 0x33D32F2F
                            outlinePaint.color = rouge
                            outlinePaint.strokeWidth = 4f
                            setOnClickListener { _, _, _ ->
                                if (!hote.editionEnCours) hote.editerStationExistante(st)
                                true
                            }
                        }
                        hote.carte.overlays.add(poly)
                        overlays.add(poly)
                        // Chaque sommet matérialisé par un petit cercle rouge (non interactif).
                        ring.forEach { sommet ->
                            val cm = Marker(hote.carte).apply {
                                position = sommet
                                icon = hote.cercleSommet(rouge)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                isDraggable = false
                                setInfoWindow(null)
                                setOnMarkerClickListener { _, _ -> false }
                            }
                            hote.carte.overlays.add(cm)
                            overlays.add(cm)
                        }
                    }
                } catch (_: Exception) {}
            } else if (st.latitude != 0.0 || st.longitude != 0.0) {
                val gp = GeoPoint(st.latitude, st.longitude)
                pts.add(gp)
                val m = Marker(hote.carte).apply {
                    position = gp
                    icon = ContextCompat.getDrawable(hote.contexte, R.drawable.ic_pin_drop) // rouge natif.
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    isDraggable = false
                    setInfoWindow(null)
                    setOnMarkerClickListener { _, _ ->
                        if (!hote.editionEnCours) hote.editerStationExistante(st)
                        true
                    }
                }
                hote.carte.overlays.add(m)
                overlays.add(m)
            }
        }
        hote.carte.invalidate()
        sommets.clear()
        sommets.addAll(pts)
        pointsRendus = pts
        return pts
    }

    /** Vue détruite : les overlays référencent une MapView morte, on les lâche et on oblige le
     *  prochain affichage à tout reconstruire. */
    fun liberer() {
        overlays.clear()
        empreinte = null
    }
}
