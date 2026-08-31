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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OccHabViewModel — détails PAR STATION (décision terrain 2026-08-27) : [OccHabViewModel.details]
 * est le tampon d'édition de la station sélectionnée (chargé à la reprise, réinjecté par
 * stationAEnregistrer), les nouvelles stations partent des DÉFAUTS de session (formulaire de
 * démarrage) ; empreinte d'origine figée sur ce qui partirait ; correction manuelle des
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
    fun station_reprise_le_tampon_reflete_SES_details_dates_comprises() {
        val vm = vm()
        vm.reprendreStation(stationServeur())
        val d = vm.details
        assertEquals(listOf(7, 8), d.observateursIds)
        assertEquals("DUPONT jean", d.observateursTxt)
        assertEquals("Pelouse pâturée", d.comment)
        assertEquals(22, d.idNomCalculSurface)
        assertEquals(23, d.idNomObjetGeographique)
        assertEquals("JDD 12", d.nomDataset)
        assertEquals("ses propres dates (celles de session ne sont posées qu'à l'IMPORT, carte)", 500L, d.dateMin)
        assertEquals(600L, d.dateMax)
        // Ce qui partirait = la station elle-même.
        val s = vm.stationAEnregistrer()
        assertEquals(listOf(7, 8), s.observateursIds)
        assertEquals("Pelouse pâturée", s.comment)
        assertEquals(500L, s.dateMin)
    }

    @Test
    fun modifier_une_station_ne_touche_ni_les_defauts_ni_les_autres_stations() {
        val vm = vm()
        vm.reprendreStation(stationServeur().copy(origineServeur = false, idStationServeur = null))
        vm.majDetails { it.comment = "revu" ; it.observateursIds = listOf(9) }
        assertEquals("revu", vm.stationAEnregistrer().comment)
        // Nouvelle station → défauts de session intacts.
        vm.nouvelleStation()
        assertEquals("session", vm.details.comment)
        assertEquals(listOf(1), vm.details.observateursIds)
        assertEquals(1_000L, vm.details.dateMin)
        assertEquals("session", vm.stationAEnregistrer().comment)
    }

    @Test
    fun formulaire_de_demarrage_redefinit_les_defauts_des_nouvelles_stations() {
        val vm = vm()
        vm.majDetails(demarrage = true) { it.observateursIds = listOf(3); it.idDataset = 15 }
        assertEquals(listOf(3), vm.defautsSession.observateursIds)
        assertEquals(15, vm.defautsSession.idDataset)
        vm.nouvelleStation()
        assertEquals(listOf(3), vm.details.observateursIds)
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

    // ── Switch « stations serveur » : coché par défaut, persistance en négatif (2026-08-31) ──

    @Test
    fun stations_serveur_affichees_par_defaut() {
        assertTrue(OccHabDetailsSession().chargerStationsServeur)
        // La vue positive écrit bien le champ persisté.
        val d = OccHabDetailsSession().apply { chargerStationsServeur = false }
        assertTrue(d.masquerStationsServeur)
        assertFalse(d.chargerStationsServeur)
    }

    @Test
    fun ancien_json_sans_le_champ_masquer_donne_affiche() {
        // Relevé précédent enregistré AVANT le champ (ou avec l'ancien `chargerStationsServeur`,
        // ignoré depuis) : le switch doit repartir COCHÉ — c'est tout l'intérêt du négatif.
        val ancien = "{\"idDataset\":12,\"chargerStationsServeur\":false}"
        val relu = com.google.gson.Gson().fromJson(ancien, OccHabDetailsSession::class.java)
        assertTrue(relu.chargerStationsServeur)
        // Décochage explicite mémorisé APRÈS la migration : respecté.
        val decoche = com.google.gson.Gson()
            .fromJson("{\"masquerStationsServeur\":true}", OccHabDetailsSession::class.java)
        assertFalse(decoche.chargerStationsServeur)
    }

    @Test
    fun reprise_de_saisie_herite_du_choix_memorise() {
        val saisie = fr.ariegenature.geomys.model.OccHabSaisie(stations = listOf(stationServeur()))
        val vmAffiche = OccHabViewModel().apply { reprendreSaisie(saisie) }
        assertTrue("défaut : affichées", vmAffiche.details.chargerStationsServeur)
        val vmMasque = OccHabViewModel().apply { reprendreSaisie(saisie, masquerStationsServeur = true) }
        assertFalse("décochage mémorisé respecté", vmMasque.details.chargerStationsServeur)
        assertFalse("les nouvelles stations de la session aussi",
            vmMasque.defautsSession.chargerStationsServeur)
    }
}
