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

import fr.ariegenature.geonat.network.AdditionalFieldsApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Extraction de la valeur par défaut d'un champ additionnel depuis les formes variées
 *  renvoyées par GeoNature (clé directe, additional_attributes, field_values default==true). */
class ExtraireDefautTest {

    private fun defaut(json: String): String? =
        AdditionalFieldsApi.extraireDefautToutesClés(JSONObject(json), "champ", "TEXT")

    @Test
    fun cle_directe_string_et_number() {
        assertEquals("RAS", defaut("""{"default_value":"RAS"}"""))
        assertEquals("12", defaut("""{"value":12}"""))
        assertEquals("3.5", defaut("""{"default":3.5}"""))
    }

    @Test
    fun variantes_de_cles() {
        assertEquals("x", defaut("""{"value_default":"x"}"""))
        assertEquals("y", defaut("""{"defaultValue":"y"}"""))
    }

    @Test
    fun dans_additional_attributes() {
        assertEquals("z", defaut("""{"additional_attributes":{"default_value":"z"}}"""))
    }

    @Test
    fun field_values_avec_default_true() {
        val json = """{"field_values":[{"value":"a","label":"A"},{"value":"b","default":true}]}"""
        assertEquals("b", defaut(json))
    }

    @Test
    fun objet_value_ou_id_nomenclature() {
        assertEquals("42", defaut("""{"default_value":{"value":42}}"""))
        assertEquals("7", defaut("""{"default_value":{"id_nomenclature":7}}"""))
    }

    @Test
    fun tableau_prend_le_premier() {
        assertEquals("premier", defaut("""{"default_value":["premier","second"]}"""))
    }

    @Test
    fun null_litteral_et_absence_renvoient_null() {
        assertNull(defaut("""{"default_value":"null"}"""))
        assertNull(defaut("""{"default_value":""}"""))
        assertNull(defaut("""{"autre":"x"}"""))
    }
}
