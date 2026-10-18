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

package fr.ariegenature.geomys

import fr.ariegenature.geomys.util.TopologiePolygone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Outils géométriques de la carte OccHab : milieu d'arête, appartenance d'un point à un
 *  anneau, tolérance d'égalité entre deux sommets (qui sert à l'aimantage).
 *
 *  La topologie PARTAGÉE entre polygones aimantés a été retirée le 2026-10-18 : un geste ne
 *  modifie plus que la station en cours d'édition, donc plus rien n'est répercuté chez un
 *  voisin — ni un déplacement de sommet, ni une insertion sur une arête commune. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopologiePolygoneTest {

    @Test
    fun meme_point_tolerance_fine() {
        assertTrue(TopologiePolygone.memePoint(GeoPoint(42.0, 1.0), 42.0 + 1e-10, 1.0))
        assertFalse(TopologiePolygone.memePoint(GeoPoint(42.0, 1.0), 42.0 + 1e-6, 1.0))
    }

    // ── Point dans l'anneau (avertissement « sommet de trou sorti du polygone ») ──

    /** Carré 0..10 (GeoPoint = latitude, longitude). */
    private fun carre() = listOf(
        GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0), GeoPoint(10.0, 10.0), GeoPoint(10.0, 0.0),
    )

    @Test
    fun point_dans_anneau_interieur_et_exterieur() {
        assertTrue(TopologiePolygone.pointDansAnneau(GeoPoint(5.0, 5.0), carre()))
        assertFalse(TopologiePolygone.pointDansAnneau(GeoPoint(5.0, 20.0), carre()))
        assertFalse(TopologiePolygone.pointDansAnneau(GeoPoint(-1.0, 5.0), carre()))
    }

    @Test
    fun point_dans_anneau_forme_concave() {
        // « L » : le creux ne doit PAS compter comme intérieur.
        val l = listOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0), GeoPoint(4.0, 10.0),
            GeoPoint(4.0, 4.0), GeoPoint(10.0, 4.0), GeoPoint(10.0, 0.0),
        )
        assertTrue(TopologiePolygone.pointDansAnneau(GeoPoint(2.0, 2.0), l))
        assertFalse("dans le creux du L", TopologiePolygone.pointDansAnneau(GeoPoint(8.0, 8.0), l))
    }

    @Test
    fun anneau_degenere_jamais_interieur() {
        assertFalse(TopologiePolygone.pointDansAnneau(GeoPoint(1.0, 1.0), emptyList()))
        assertFalse(TopologiePolygone.pointDansAnneau(
            GeoPoint(1.0, 1.0), listOf(GeoPoint(0.0, 0.0), GeoPoint(2.0, 2.0))))
    }
}
