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

import fr.ariegenature.geomys.network.MonitoringApi
import org.junit.Assert.assertEquals
import org.junit.Test

/** Extraction "best-effort" du nom d'un objet monitoring depuis ses propriétés à plat,
 *  avant que le schéma (nameField) ne soit disponible. */
class ExtraireNomHeuristiqueTest {

    @Test
    fun prefere_base_TYPE_name() {
        val props = mapOf("base_site_name" to "Forêt de Foix", "name" to "autre")
        assertEquals("Forêt de Foix", MonitoringApi.extraireNomHeuristique(props, "site", 5))
    }

    @Test
    fun TYPE_name_specifique_au_type() {
        val props = mapOf("station_name" to "Point Nord")
        assertEquals("Point Nord", MonitoringApi.extraireNomHeuristique(props, "station", 3))
    }

    @Test
    fun retombe_sur_name_puis_label() {
        assertEquals("Truc", MonitoringApi.extraireNomHeuristique(mapOf("name" to "Truc"), "x", 1))
        assertEquals("Bidule", MonitoringApi.extraireNomHeuristique(mapOf("label" to "Bidule"), "x", 1))
    }

    @Test
    fun sans_candidat_fallback_sur_diese_id() {
        assertEquals("#42", MonitoringApi.extraireNomHeuristique(emptyMap(), "site", 42))
    }

    @Test
    fun sans_candidat_et_id_invalide_fallback_tiret() {
        assertEquals("—", MonitoringApi.extraireNomHeuristique(emptyMap(), "site", 0))
    }

    @Test
    fun valeur_vide_ignoree_au_profit_du_candidat_suivant() {
        val props = mapOf("base_site_name" to "", "name" to "Replié")
        assertEquals("Replié", MonitoringApi.extraireNomHeuristique(props, "site", 9))
    }
}
