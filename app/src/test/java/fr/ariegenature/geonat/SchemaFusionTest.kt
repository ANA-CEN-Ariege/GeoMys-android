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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fusion des blocs `generic` + `specific` d'un object_type du schéma /config/<code>
 *  (un attribut `specific` surcharge le `generic` champ par champ). */
class SchemaFusionTest {

    // ── fusionnerChamp (merge shallow) ─────────────────────────────────────────
    @Test
    fun fusion_union_des_cles() {
        val g = JSONObject("""{"type_widget":"text","required":false}""")
        val s = JSONObject("""{"label":"Spécifique"}""")
        val out = MonitoringApi.fusionnerChamp(g, s)
        assertEquals("text", out.getString("type_widget"))
        assertEquals("Spécifique", out.getString("label"))
        assertFalse(out.getBoolean("required"))
    }

    @Test
    fun fusion_specific_surcharge_generic() {
        val g = JSONObject("""{"type_widget":"text","required":false}""")
        val s = JSONObject("""{"required":true}""")
        val out = MonitoringApi.fusionnerChamp(g, s)
        assertTrue(out.getBoolean("required")) // specific gagne
        assertEquals("text", out.getString("type_widget"))
    }

    @Test
    fun fusion_avec_blocs_nuls() {
        val g = JSONObject("""{"type_widget":"date"}""")
        assertEquals("date", MonitoringApi.fusionnerChamp(g, null).getString("type_widget"))
        assertEquals("date", MonitoringApi.fusionnerChamp(null, g).getString("type_widget"))
        assertEquals(0, MonitoringApi.fusionnerChamp(null, null).length())
    }

    // ── parserPropertiesFusionnees ──────────────────────────────────────────────
    @Test
    fun fusionne_generic_et_specific_par_nom() {
        val schema = JSONObject(
            """
            {
              "generic":  { "comment": { "type_widget": "textarea", "label": "Remarque" } },
              "specific": { "comment": { "required": true } }
            }
            """.trimIndent(),
        )
        val props = MonitoringApi.parserPropertiesFusionnees(schema)
        val comment = props.getValue("comment")
        assertEquals("textarea", comment.typeWidget) // vient du generic
        assertEquals("Remarque", comment.label)
        assertTrue(comment.obligatoire)               // surchargé par specific
        assertTrue(comment.enSpecific)                // présent dans specific → marqué
    }

    @Test
    fun champ_uniquement_generic_non_marque_specific() {
        val schema = JSONObject("""{"generic":{"a":{"type_widget":"text"}},"specific":{}}""")
        val a = MonitoringApi.parserPropertiesFusionnees(schema).getValue("a")
        assertFalse(a.enSpecific)
    }

    @Test
    fun champ_specific_sans_widget_infere_via_type_util() {
        // Cas réel : un protocole ne déclare en specific que `type_util: date` sans type_widget.
        val schema = JSONObject("""{"generic":{},"specific":{"date_obs":{"type_util":"date"}}}""")
        val p = MonitoringApi.parserPropertiesFusionnees(schema).getValue("date_obs")
        assertEquals("date", p.typeWidget)
        assertTrue(p.enSpecific)
    }

    @Test
    fun repli_sur_bloc_properties_quand_ni_generic_ni_specific() {
        val schema = JSONObject("""{"properties":{"nom":{"type_widget":"text","label":"Nom"}}}""")
        val props = MonitoringApi.parserPropertiesFusionnees(schema)
        assertEquals("Nom", props.getValue("nom").label)
    }

    @Test
    fun schema_vide_renvoie_map_vide() {
        assertTrue(MonitoringApi.parserPropertiesFusionnees(JSONObject("{}")).isEmpty())
    }
}
