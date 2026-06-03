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

import fr.ariegenature.geomys.network.MonitoringSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heuristique de nommage qui décide si un object_type est une « saisie » (visite, obs…)
 *  — pilote l'affichage des boutons « + Nouveau X » et le pré-chargement offline. */
class EstTypeSaisieTest {

    @Test
    fun types_de_saisie_reconnus() {
        listOf(
            "visite", "visites", "visit", "visits",
            "observation", "observations", "obs",
            "occurrence", "occurrences",
            "releve", "releves", "relevé", "relevés",
            "denombrement", "dénombrements",
        ).forEach { assertTrue("'$it' devrait être une saisie", MonitoringSync.estTypeSaisie(it)) }
    }

    @Test
    fun insensible_a_la_casse_et_aux_espaces() {
        assertTrue(MonitoringSync.estTypeSaisie("  VISITE "))
        assertTrue(MonitoringSync.estTypeSaisie("Observation"))
    }

    @Test
    fun types_non_saisie_rejetes() {
        listOf("site", "sites_group", "station", "module", "", "point_ecoute")
            .forEach { assertFalse("'$it' ne devrait pas être une saisie", MonitoringSync.estTypeSaisie(it)) }
    }
}
