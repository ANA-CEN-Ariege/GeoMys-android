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

import fr.ariegenature.geomys.network.GeoNatureUpload
import org.junit.Assert.assertEquals
import org.junit.Test

/** Construction du GeoJSON envoyé au serveur OccTax (geometry du relevé) : Point / LineString
 *  / Polygon, avec fermeture automatique de l'anneau du polygone. Coordonnées en [lon, lat]. */
class ConstruireGeometrieTest {

    @Test
    fun point_par_defaut_quand_type_null_ou_vide() {
        val g = GeoNatureUpload.construireGeometrie(null, null, lat = 42.9, lon = 1.4)
        assertEquals("Point", g.getString("type"))
        val c = g.getJSONArray("coordinates")
        assertEquals(1.4, c.getDouble(0), 1e-9) // lon d'abord
        assertEquals(42.9, c.getDouble(1), 1e-9)
    }

    @Test
    fun type_point_explicite() {
        val g = GeoNatureUpload.construireGeometrie("Point", null, 42.9, 1.4)
        assertEquals("Point", g.getString("type"))
    }

    @Test
    fun linestring_reprend_les_coordonnees_fournies() {
        val coords = "[[1.0,42.0],[1.1,42.1],[1.2,42.2]]"
        val g = GeoNatureUpload.construireGeometrie("LineString", coords, 0.0, 0.0)
        assertEquals("LineString", g.getString("type"))
        val c = g.getJSONArray("coordinates")
        assertEquals(3, c.length())
        assertEquals(1.1, c.getJSONArray(1).getDouble(0), 1e-9)
    }

    @Test
    fun polygon_ferme_automatiquement_l_anneau() {
        // Anneau non fermé (dernier != premier) → le 1er point est ré-ajouté à la fin.
        val coords = "[[1.0,42.0],[1.5,42.0],[1.5,42.5]]"
        val g = GeoNatureUpload.construireGeometrie("Polygon", coords, 0.0, 0.0)
        assertEquals("Polygon", g.getString("type"))
        val anneau = g.getJSONArray("coordinates").getJSONArray(0)
        assertEquals(4, anneau.length())
        val premier = anneau.getJSONArray(0)
        val dernier = anneau.getJSONArray(anneau.length() - 1)
        assertEquals(premier.getDouble(0), dernier.getDouble(0), 1e-9)
        assertEquals(premier.getDouble(1), dernier.getDouble(1), 1e-9)
    }

    @Test
    fun polygon_deja_ferme_inchange() {
        val coords = "[[1.0,42.0],[1.5,42.0],[1.5,42.5],[1.0,42.0]]"
        val g = GeoNatureUpload.construireGeometrie("Polygon", coords, 0.0, 0.0)
        val anneau = g.getJSONArray("coordinates").getJSONArray(0)
        assertEquals(4, anneau.length()) // pas de point ajouté
    }

    @Test
    fun type_inconnu_retombe_sur_point() {
        val g = GeoNatureUpload.construireGeometrie("Circle", "[[1.0,2.0]]", 42.0, 1.0)
        assertEquals("Point", g.getString("type"))
    }
}
