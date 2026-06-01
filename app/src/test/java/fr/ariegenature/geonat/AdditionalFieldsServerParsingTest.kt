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
import fr.ariegenature.geonat.network.WidgetType
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing du JSON brut /api/gn_commons/additional_fields — tolère les formes variables
 *  selon la version GeoNature (type_widget objet/string, field_values string/{value,label},
 *  objects[].code_object, datasets ids nus/objets). */
class AdditionalFieldsServerParsingTest {

    @Test
    fun type_widget_en_objet() {
        val arr = JSONArray(
            """
            [{"id_field":1,"field_name":"alt","field_label":"Altitude",
              "type_widget":{"widget_name":"number"},
              "objects":[{"code_object":"OCCTAX_RELEVE"}]}]
            """.trimIndent(),
        )
        val defs = AdditionalFieldsApi.parserChamps(arr)
        assertEquals(1, defs.size)
        assertEquals(WidgetType.NUMBER, defs[0].widget)
        assertEquals("alt", defs[0].fieldName)
        assertTrue("OCCTAX_RELEVE" in defs[0].objectsCode)
    }

    @Test
    fun type_widget_en_string() {
        val arr = JSONArray("""[{"id_field":2,"field_name":"note","type_widget":"textarea"}]""")
        assertEquals(WidgetType.TEXTAREA, AdditionalFieldsApi.parserChamps(arr)[0].widget)
    }

    @Test
    fun widget_inconnu_devient_INCONNU() {
        val arr = JSONArray("""[{"id_field":3,"field_name":"x","type_widget":"exotique"}]""")
        assertEquals(WidgetType.INCONNU, AdditionalFieldsApi.parserChamps(arr)[0].widget)
    }

    @Test
    fun field_values_string_et_objet() {
        val arr = JSONArray(
            """
            [{"id_field":4,"field_name":"meteo","type_widget":"select",
              "field_values":["soleil", {"value":"p","label":"Pluie"}]}]
            """.trimIndent(),
        )
        assertEquals(listOf("soleil", "Pluie"), AdditionalFieldsApi.parserChamps(arr)[0].fieldValues)
    }

    @Test
    fun datasets_ids_nus_ou_objets() {
        val arr = JSONArray(
            """
            [{"id_field":5,"field_name":"a","type_widget":"text","datasets":[10, {"id_dataset":20}]}]
            """.trimIndent(),
        )
        assertEquals(listOf(10, 20), AdditionalFieldsApi.parserChamps(arr)[0].datasetsIds)
    }

    @Test
    fun id_list_et_required() {
        val arr = JSONArray(
            """[{"id_field":6,"field_name":"b","type_widget":"text","id_list":100,"required":true}]""",
        )
        val d = AdditionalFieldsApi.parserChamps(arr)[0]
        assertEquals(100, d.idList)
        assertTrue(d.required)
    }

    @Test
    fun entrees_invalides_ignorees() {
        // id_field absent ou field_name vide → l'entrée est sautée.
        val arr = JSONArray(
            """
            [{"field_name":"sans_id","type_widget":"text"},
             {"id_field":7,"field_name":"","type_widget":"text"},
             {"id_field":8,"field_name":"ok","type_widget":"text"}]
            """.trimIndent(),
        )
        val defs = AdditionalFieldsApi.parserChamps(arr)
        assertEquals(1, defs.size)
        assertEquals("ok", defs[0].fieldName)
    }

    @Test
    fun label_defaut_sur_field_name_si_absent() {
        val arr = JSONArray("""[{"id_field":9,"field_name":"code_zh","type_widget":"text"}]""")
        assertEquals("code_zh", AdditionalFieldsApi.parserChamps(arr)[0].fieldLabel)
    }
}
