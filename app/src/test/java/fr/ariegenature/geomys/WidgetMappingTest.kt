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

import fr.ariegenature.geomys.monitoring.form.ViewType
import fr.ariegenature.geomys.monitoring.form.mapperViewType
import fr.ariegenature.geomys.network.MonitoringApi.MonitoringPropertySchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mapping du `type_widget` renvoyé par /config/<code> vers le ViewType du form renderer.
 *  C'est le cœur de l'interprétation du schéma serveur monitoring. */
class WidgetMappingTest {

    private fun prop(
        widget: String,
        nom: String = "champ",
        multiple: Boolean = false,
        typeUtil: String? = null,
        apiUrl: String? = null,
    ) = MonitoringPropertySchema(
        nom = nom,
        typeWidget = widget,
        label = nom,
        obligatoire = false,
        typeUtil = typeUtil,
        apiUrl = apiUrl,
        multiple = multiple,
    )

    @Test
    fun widgets_scalaires_simples() {
        assertEquals(ViewType.TEXT, mapperViewType(prop("text")))
        assertEquals(ViewType.TEXT, mapperViewType(prop("string")))
        assertEquals(ViewType.TEXTAREA, mapperViewType(prop("textarea")))
        assertEquals(ViewType.NUMBER, mapperViewType(prop("integer")))
        assertEquals(ViewType.NUMBER, mapperViewType(prop("float")))
        assertEquals(ViewType.DATE, mapperViewType(prop("date")))
        assertEquals(ViewType.TIME, mapperViewType(prop("time")))
        assertEquals(ViewType.DATETIME, mapperViewType(prop("datetime")))
        assertEquals(ViewType.DATETIME, mapperViewType(prop("timestamp")))
        assertEquals(ViewType.CHECKBOX, mapperViewType(prop("bool_checkbox")))
    }

    @Test
    fun insensible_a_la_casse() {
        assertEquals(ViewType.TEXT, mapperViewType(prop("TEXT")))
        assertEquals(ViewType.DATE, mapperViewType(prop("Date")))
    }

    @Test
    fun select_et_radio_single_vs_multiple() {
        assertEquals(ViewType.SELECT, mapperViewType(prop("select")))
        assertEquals(ViewType.SELECT_MULTIPLE, mapperViewType(prop("select", multiple = true)))
        assertEquals(ViewType.RADIO, mapperViewType(prop("radio")))
        // radio multiple n'a pas de sens → multi-sélection à cases
        assertEquals(ViewType.SELECT_MULTIPLE, mapperViewType(prop("radio", multiple = true)))
    }

    @Test
    fun datalists_unifiees_sur_select() {
        assertEquals(ViewType.SELECT, mapperViewType(prop("datalist")))
        assertEquals(ViewType.SELECT, mapperViewType(prop("nomenclature")))
        assertEquals(ViewType.SELECT, mapperViewType(prop("observers")))
        assertEquals(ViewType.SELECT_MULTIPLE, mapperViewType(prop("dataset", multiple = true)))
    }

    @Test
    fun champ_taxon_detecte_par_plusieurs_signaux() {
        assertEquals(ViewType.TAXON, mapperViewType(prop("taxonomy")))
        // nom == cd_nom force TAXON même sur un widget datalist
        assertEquals(ViewType.TAXON, mapperViewType(prop("datalist", nom = "cd_nom")))
        // type_util == taxonomy
        assertEquals(ViewType.TAXON, mapperViewType(prop("text", typeUtil = "taxonomy")))
        // api allnamebylist/<id>
        assertEquals(
            ViewType.TAXON,
            mapperViewType(prop("datalist", apiUrl = "taxref/allnamebylist/100")),
        )
    }

    @Test
    fun medias_vers_media() {
        assertEquals(ViewType.MEDIA, mapperViewType(prop("medias")))
    }

    @Test
    fun html_ignore() {
        assertNull(mapperViewType(prop("html")))
    }

    @Test
    fun widget_inconnu_degrade_en_text() {
        assertEquals(ViewType.TEXT, mapperViewType(prop("widget_exotique_inconnu")))
    }
}
