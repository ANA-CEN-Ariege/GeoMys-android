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

import fr.ariegenature.geonat.gpx.genererGPX
import fr.ariegenature.geonat.gpx.importerGPX
import fr.ariegenature.geonat.model.Observation
import fr.ariegenature.geonat.model.PointTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Export GPX + relecture. Garantit l'intégrité des données d'une sortie exportée puis
 *  réimportée (espèce, position, effectif, notes, parcours). */
class GpxUtilsTest {

    // Timestamp aligné sur la seconde (le format GPX tronque les millisecondes).
    private val t0 = 1_700_000_000_000L

    private fun obs(espece: String, lat: Double, lon: Double, nombre: Int = 1, notes: String = "") =
        Observation(espece = espece, latitude = lat, longitude = lon, nombre = nombre, notes = notes, date = t0)

    @Test
    fun genererGPX_produit_un_entete_et_les_waypoints() {
        val gpx = genererGPX(listOf(obs("Merle noir", 42.9, 1.4)), emptyList())
        assertTrue(gpx.contains("<?xml"))
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.contains("""<wpt lat="42.9" lon="1.4">"""))
        assertTrue(gpx.contains("<name>Merle noir</name>"))
    }

    @Test
    fun genererGPX_echappe_les_caracteres_xml_du_nom() {
        val gpx = genererGPX(listOf(obs("Pie & <corbeau>", 43.0, 1.0)), emptyList())
        assertTrue(gpx.contains("Pie &amp; &lt;corbeau&gt;"))
        // Le nom brut non échappé ne doit pas apparaître.
        assertTrue(!gpx.contains("Pie & <corbeau>"))
    }

    @Test
    fun roundtrip_preserve_observations_et_parcours() {
        val observations = listOf(
            obs("Buse variable", 42.95, 1.43, nombre = 3, notes = "en vol"),
            obs("Rougegorge", 42.96, 1.45),
        )
        val parcours = listOf(PointTrace(42.95, 1.43), PointTrace(42.96, 1.45))

        val gpx = genererGPX(observations, parcours)
        val sortie = importerGPX(gpx.toByteArray())!!

        assertEquals(2, sortie.observations.size)
        assertEquals(2, sortie.pointsParcours.size)

        val buse = sortie.observations.first { it.espece == "Buse variable" }
        assertEquals(42.95, buse.latitude, 1e-9)
        assertEquals(1.43, buse.longitude, 1e-9)
        assertEquals(3, buse.nombre)
        assertEquals("en vol", buse.notes)
        assertEquals(t0, buse.date)

        assertEquals(42.96, sortie.pointsParcours[1].latitude, 1e-9)
    }

    @Test
    fun importerGPX_donnees_invalides_renvoie_null() {
        assertNull(importerGPX("pas du gpx".toByteArray()))
    }

    @Test
    fun importerGPX_gpx_vide_sans_point_renvoie_null() {
        val gpx = genererGPX(emptyList(), emptyList())
        assertNull(importerGPX(gpx.toByteArray()))
    }
}
