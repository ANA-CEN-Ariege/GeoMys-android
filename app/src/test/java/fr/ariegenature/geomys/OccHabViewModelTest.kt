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

import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.ui.OccHabDetailsSession
import fr.ariegenature.geomys.ui.OccHabViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OccHabViewModel — reprise d'une station : une station D'ORIGINE SERVEUR fait adopter ses
 * détails (JDD, observateurs, commentaire, nomenclatures) à la session SAUF les dates (décision
 * terrain 2026-08-27) ; empreinte d'origine figée sur ce qui partirait ; correction manuelle des
 * altitudes / surface = modification réelle malgré leur exclusion de l'empreinte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OccHabViewModelTest {

    private fun vm() = OccHabViewModel().apply {
        demarrerSession(OccHabDetailsSession(
            idDataset = 12, nomDataset = "JDD 12",
            observateursIds = listOf(1), observateursNoms = listOf("MOI"),
            dateMin = 1_000L, dateMax = 2_000L,
            idNomCalculSurface = 5, idNomObjetGeographique = 6, comment = "session",
        ))
    }

    private fun stationServeur() = OccHabStation(
        idStationServeur = 42, origineServeur = true,
        geometryType = "Point", latitude = 42.9, longitude = 1.4,
        idDataset = 12, observateursIds = listOf(7, 8), observateursNoms = listOf("DUPONT jean", "MARTIN paul"),
        observateursTxt = "DUPONT jean", dateMin = 500L, dateMax = 600L,
        idNomCalculSurface = 22, idNomObjetGeographique = 23, comment = "Pelouse pâturée",
        altitudeMin = 800, altitudeMax = 850, surface = 1234L,
    )

    @Test
    fun station_serveur_reprise_la_session_adopte_ses_details_sauf_les_dates() {
        val vm = vm()
        vm.reprendreStation(stationServeur())
        val d = vm.details
        assertEquals(listOf(7, 8), d.observateursIds)
        assertEquals(listOf("DUPONT jean", "MARTIN paul"), d.observateursNoms)
        assertEquals("DUPONT jean", d.observateursTxt)
        assertEquals("Pelouse pâturée", d.comment)
        assertEquals(22, d.idNomCalculSurface)
        assertEquals(23, d.idNomObjetGeographique)
        assertEquals("JDD 12", d.nomDataset)
        assertEquals("dates = celles de la session", 1_000L, d.dateMin)
        assertEquals(2_000L, d.dateMax)
        // Ce qui partirait au serveur : détails de la station + dates de session.
        val s = vm.stationAEnregistrer()
        assertEquals(listOf(7, 8), s.observateursIds)
        assertEquals("Pelouse pâturée", s.comment)
        assertEquals(1_000L, s.dateMin)
        assertEquals(2_000L, s.dateMax)
    }

    @Test
    fun station_locale_reprise_ne_touche_pas_aux_details_de_session() {
        val vm = vm()
        vm.reprendreStation(stationServeur().copy(origineServeur = false, idStationServeur = null))
        assertEquals(listOf(1), vm.details.observateursIds)
        assertEquals("session", vm.details.comment)
        assertEquals(5, vm.details.idNomCalculSurface)
    }

    @Test
    fun empreinte_origine_figee_sur_ce_qui_partirait_puis_levee_par_une_correction_manuelle() {
        val vm = vm()
        vm.reprendreStation(stationServeur())
        vm.figerEmpreinteOrigine()
        assertEquals(vm.stationAEnregistrer().empreinteContenu(), vm.station.empreinteOrigine)
        val avant = vm.deriveesManuelles()
        vm.forcerPersistanceSiDeriveesChangees(avant)
        assertNotNull("rien n'a changé → empreinte conservée", vm.station.empreinteOrigine)
        vm.definirAltitudes(800, 900)
        vm.forcerPersistanceSiDeriveesChangees(avant)
        assertNull("altitude corrigée à la main = modification réelle", vm.station.empreinteOrigine)
    }
}
