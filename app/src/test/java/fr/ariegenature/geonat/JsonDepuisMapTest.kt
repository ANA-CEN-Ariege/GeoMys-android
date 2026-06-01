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

import fr.ariegenature.geonat.network.GeoNatureUpload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typage des champs additionnels (Map<field, String>) vers un JSONObject envoyé au serveur :
 *  "true"/"false" → booléen, entier → int, décimal → double, sinon string ; vides ignorés. */
class JsonDepuisMapTest {

    @Test
    fun booleens() {
        val j = GeoNatureUpload.jsonDepuisMap(mapOf("ok" to "true", "ko" to "FALSE"))
        assertTrue(j.get("ok") is Boolean)
        assertTrue(j.getBoolean("ok"))
        assertFalse(j.getBoolean("ko"))
    }

    @Test
    fun entier_et_decimal() {
        val j = GeoNatureUpload.jsonDepuisMap(mapOf("n" to "42", "x" to "3.14"))
        assertEquals(42, j.getInt("n"))
        assertEquals(3.14, j.getDouble("x"), 1e-9)
        assertTrue(j.get("n") is Int)
    }

    @Test
    fun texte_reste_string() {
        val j = GeoNatureUpload.jsonDepuisMap(mapOf("note" to "RAS"))
        assertEquals("RAS", j.getString("note"))
    }

    @Test
    fun valeurs_vides_ignorees() {
        val j = GeoNatureUpload.jsonDepuisMap(mapOf("a" to "", "b" to "ok"))
        assertFalse(j.has("a"))
        assertTrue(j.has("b"))
    }
}
