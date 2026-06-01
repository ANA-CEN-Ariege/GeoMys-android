/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat

import fr.ariegenature.geonat.network.MonitoringApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Résumé lisible de la géométrie GeoJSON renvoyée par le serveur (affiché sur les fiches). */
class FormatGeometrieTest {

    @Test
    fun null_ou_sans_type_renvoie_null() {
        assertNull(MonitoringApi.formatGeometrie(null))
        assertNull(MonitoringApi.formatGeometrie(JSONObject("{}")))
    }

    @Test
    fun point_affiche_les_hemispheres() {
        // [lon, lat] = [1.4, 42.9] → Nord / Est.
        val ne = MonitoringApi.formatGeometrie(JSONObject("""{"type":"Point","coordinates":[1.4,42.9]}"""))!!
        assertTrue(ne.contains("N"))
        assertTrue(ne.contains("E"))
        assertTrue(ne.contains("°"))
        // Sud / Ouest.
        val sw = MonitoringApi.formatGeometrie(JSONObject("""{"type":"Point","coordinates":[-1.4,-42.9]}"""))!!
        assertTrue(sw.contains("S"))
        assertTrue(sw.contains("W"))
    }

    @Test
    fun point_coordonnees_incompletes_retombe_sur_le_type() {
        assertEquals("Point", MonitoringApi.formatGeometrie(JSONObject("""{"type":"Point","coordinates":[1.0]}""")))
    }

    @Test
    fun polygon_compte_les_sommets() {
        val g = JSONObject("""{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}""")
        assertEquals("Polygone (4 sommets)", MonitoringApi.formatGeometrie(g))
    }

    @Test
    fun linestring_compte_les_points() {
        val g = JSONObject("""{"type":"LineString","coordinates":[[0,0],[1,1],[2,2]]}""")
        assertEquals("Ligne (3 points)", MonitoringApi.formatGeometrie(g))
    }

    @Test
    fun multipoint_et_multipolygon() {
        assertEquals("2 points", MonitoringApi.formatGeometrie(JSONObject("""{"type":"MultiPoint","coordinates":[[0,0],[1,1]]}""")))
        assertEquals("MultiPolygone", MonitoringApi.formatGeometrie(JSONObject("""{"type":"MultiPolygon","coordinates":[]}""")))
    }

    @Test
    fun type_inconnu_renvoie_le_type_brut() {
        assertEquals("GeometryCollection", MonitoringApi.formatGeometrie(JSONObject("""{"type":"GeometryCollection"}""")))
    }
}
