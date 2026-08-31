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

/** Topologie partagée des polygones aimantés : insertion sur arête commune, déplacement de
 *  sommet partagé, tolérance d'égalité. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopologiePolygoneTest {

    private fun ring() = mutableListOf(
        doubleArrayOf(1.0, 42.0), doubleArrayOf(2.0, 42.0), doubleArrayOf(2.0, 43.0),
    )

    @Test
    fun insertion_sur_arete_commune_dans_les_deux_sens_fermeture_incluse() {
        val a = GeoPoint(42.0, 1.0); val b = GeoPoint(42.0, 2.0); val m = TopologiePolygone.milieu(a, b)
        val r1 = ring()
        assertTrue(TopologiePolygone.insererSurArete(r1, a, b, m))
        assertEquals(4, r1.size)
        assertEquals(1.5, r1[1][0], 1e-9); assertEquals(42.0, r1[1][1], 1e-9)
        // Sens inverse (b→a) : même arête.
        val r2 = ring()
        assertTrue(TopologiePolygone.insererSurArete(r2, b, a, m))
        assertEquals(4, r2.size)
        // Arête de fermeture (dernier → premier).
        val r3 = ring()
        val c = GeoPoint(43.0, 2.0)
        assertTrue(TopologiePolygone.insererSurArete(r3, c, a, TopologiePolygone.milieu(c, a)))
        assertEquals(4, r3.size)
        assertEquals("inséré après le dernier sommet", 42.5, r3[3][1], 1e-9)
        // Arête étrangère : rien.
        val r4 = ring()
        assertFalse(TopologiePolygone.insererSurArete(r4, GeoPoint(50.0, 5.0), a, m))
        assertEquals(3, r4.size)
    }

    @Test
    fun deplacement_d_un_sommet_partage() {
        val r = ring()
        assertTrue(TopologiePolygone.deplacerSommetsConfondus(r, GeoPoint(42.0, 2.0), GeoPoint(42.1, 2.1)))
        assertEquals(2.1, r[1][0], 1e-9); assertEquals(42.1, r[1][1], 1e-9)
        assertFalse("aucun sommet confondu", TopologiePolygone.deplacerSommetsConfondus(r, GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0)))
    }

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
