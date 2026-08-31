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

package fr.ariegenature.geomys.util

import org.osmdroid.util.GeoPoint
import kotlin.math.abs

/**
 * Topologie PARTAGÉE des polygones OccHab aimantés (v1.3.14) : deux stations qui partagent un
 * sommet ou une arête (aimantage à la saisie) restent raccordées quand l'une est éditée —
 * l'insertion d'un sommet sur une arête commune et le déplacement d'un sommet partagé sont
 * répercutés sur l'anneau du voisin. Logique PURE sur des anneaux `[lon, lat]` (extraite de
 * OccHabCarteFragment pour être testable, audit 2026-08-27).
 */
object TopologiePolygone {

    private const val EPSILON = 1e-9

    /** Deux positions confondues (tolérance 1e-9°, ≈ 0,1 mm : égalité issue de l'aimantage,
     *  qui COPIE les coordonnées du sommet cible). */
    fun memePoint(p: GeoPoint, lat: Double, lon: Double): Boolean =
        abs(p.latitude - lat) < EPSILON && abs(p.longitude - lon) < EPSILON

    /** Milieu de l'arête [a]-[b] (position de la poignée « + »). */
    fun milieu(a: GeoPoint, b: GeoPoint): GeoPoint =
        GeoPoint((a.latitude + b.latitude) / 2, (a.longitude + b.longitude) / 2)

    /** Si [ring] (anneau `[lon,lat]`, fermeture implicite) possède l'arête {a,b} — dans un sens
     *  ou dans l'autre — y insère [insere] entre ses deux extrémités. true si inséré. */
    fun insererSurArete(ring: MutableList<DoubleArray>, a: GeoPoint, b: GeoPoint, insere: GeoPoint): Boolean {
        for (i in ring.indices) {
            val j = (i + 1) % ring.size
            val pi = ring[i]; val pj = ring[j]
            val sensDirect = memePoint(a, pi[1], pi[0]) && memePoint(b, pj[1], pj[0])
            val sensInverse = memePoint(b, pi[1], pi[0]) && memePoint(a, pj[1], pj[0])
            if (sensDirect || sensInverse) {
                ring.add(i + 1, doubleArrayOf(insere.longitude, insere.latitude))
                return true
            }
        }
        return false
    }

    /** Tout sommet de [ring] confondu avec [avant] (sommet partagé) est déplacé vers [apres].
     *  true si au moins un sommet a bougé. */
    fun deplacerSommetsConfondus(ring: MutableList<DoubleArray>, avant: GeoPoint, apres: GeoPoint): Boolean {
        var touche = false
        ring.forEach { c ->
            if (memePoint(avant, c[1], c[0])) {
                c[0] = apres.longitude; c[1] = apres.latitude
                touche = true
            }
        }
        return touche
    }

    /**
     * [p] est-il À L'INTÉRIEUR de l'anneau [anneau] (liste de sommets DISTINCTS, fermeture
     * implicite) ? Lancer de rayon standard (parité impaire des intersections), en degrés :
     * suffisant pour l'usage — avertir l'utilisateur qu'un sommet de TROU est sorti du contour
     * extérieur, ce que le serveur (PostGIS) refuserait. Un point exactement sur le bord peut
     * tomber d'un côté ou de l'autre : on ne s'en sert que pour un AVERTISSEMENT, jamais pour
     * bloquer une saisie. False si l'anneau a moins de 3 sommets.
     */
    fun pointDansAnneau(p: GeoPoint, anneau: List<GeoPoint>): Boolean {
        if (anneau.size < 3) return false
        var dedans = false
        var j = anneau.size - 1
        for (i in anneau.indices) {
            val xi = anneau[i].longitude; val yi = anneau[i].latitude
            val xj = anneau[j].longitude; val yj = anneau[j].latitude
            if ((yi > p.latitude) != (yj > p.latitude) &&
                p.longitude < (xj - xi) * (p.latitude - yi) / (yj - yi) + xi
            ) dedans = !dedans
            j = i
        }
        return dedans
    }
}
