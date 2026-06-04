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

import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.ui.TraceViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [TraceViewModel.remplacerObservationsDuReleve] : sémantique d'upsert. Régression du bug
 *  terrain « espèce en double sur un point » : une obs MONO-taxon (releveId=null) éditée via
 *  l'écran multi-taxons ressort avec un releveId de session — la purge par seul releveId ne
 *  retrouvait pas l'originale, qui survivait à côté de la version éditée (même id d'obs). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = GeoMysApplication::class)
class TraceViewModelReleveTest {

    private fun vm() = TraceViewModel(ApplicationProvider.getApplicationContext())

    private fun obs(id: String, releveId: String? = null, medias: List<String> = emptyList()) =
        Observation(
            id = id, espece = "Merle", cdNom = 4001, latitude = 42.9, longitude = 1.4,
            nombre = 1, releveId = releveId, date = 1_700_000_000_000L,
            mediaUrisCounting0 = medias,
        )

    @Test
    fun editer_une_obs_mono_taxon_la_remplace_au_lieu_de_la_dupliquer() {
        val vm = vm()
        // Obs mono-taxon d'origine : releveId = null (chaque obs = son propre relevé).
        vm.ajouterObservation(obs("o1"))
        // Édition (ajout d'une photo) : l'écran multi-taxons ré-émet l'obs avec le MÊME id
        // mais le releveId de sa session d'édition.
        vm.remplacerObservationsDuReleve(
            "session-uuid",
            listOf(obs("o1", releveId = "session-uuid", medias = listOf("file:///p.jpg"))),
        )
        val obsFinales = vm.observations.value.orEmpty()
        assertEquals("l'obs doit être REMPLACÉE, pas dupliquée", 1, obsFinales.size)
        assertEquals(listOf("file:///p.jpg"), obsFinales.single().mediaUrisCounting0)
    }

    @Test
    fun remplacer_un_releve_multi_taxons_conserve_les_autres_releves() {
        val vm = vm()
        vm.ajouterObservation(obs("a", releveId = "R1"))
        vm.ajouterObservation(obs("b", releveId = "R1"))
        vm.ajouterObservation(obs("autre", releveId = "R2"))
        // Le lot R1 est ré-émis avec une espèce retirée (b supprimée) et une ajoutée.
        vm.remplacerObservationsDuReleve("R1", listOf(obs("a", releveId = "R1"), obs("c", releveId = "R1")))
        val ids = vm.observations.value.orEmpty().map { it.id }.toSet()
        assertEquals(setOf("a", "c", "autre"), ids)
    }
}
