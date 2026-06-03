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
import fr.ariegenature.geomys.store.OutboxMonitoring
import fr.ariegenature.geomys.store.SaisieEnAttente
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** File d'attente locale des saisies monitoring : compteur « en attente » (pastille rouge),
 *  arbre parent→enfants et suppression en cascade. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxMonitoringTest {

    @Before
    fun setup() {
        OutboxMonitoring.init(ApplicationProvider.getApplicationContext())
        OutboxMonitoring.vider() // remet à zéro l'état singleton entre les tests
    }

    private fun saisie(
        uuid: String,
        parent: String? = null,
        etat: SaisieEnAttente.Etat = SaisieEnAttente.Etat.PENDING,
    ) = SaisieEnAttente(
        uuid = uuid, moduleCode = "stom", objectType = "visit",
        parentUuidLocal = parent, valeursJson = "{}", etat = etat,
    )

    @Test
    fun count_en_attente_compte_pending_et_error_pas_sent() {
        OutboxMonitoring.ajouter(saisie("a", etat = SaisieEnAttente.Etat.PENDING))
        OutboxMonitoring.ajouter(saisie("b", etat = SaisieEnAttente.Etat.ERROR))
        OutboxMonitoring.ajouter(saisie("c", etat = SaisieEnAttente.Etat.SENT))
        assertEquals(2, OutboxMonitoring.countEnAttente())
        assertEquals(3, OutboxMonitoring.tout().size)
    }

    @Test
    fun mettre_a_jour_change_l_etat() {
        OutboxMonitoring.ajouter(saisie("a"))
        OutboxMonitoring.mettreAJour("a") { it.copy(etat = SaisieEnAttente.Etat.SENT) }
        assertEquals(0, OutboxMonitoring.countEnAttente())
    }

    @Test
    fun descendants_parcourt_l_arbre_en_profondeur() {
        // a → b → c (petit-enfant), a → d
        OutboxMonitoring.ajouter(saisie("a"))
        OutboxMonitoring.ajouter(saisie("b", parent = "a"))
        OutboxMonitoring.ajouter(saisie("c", parent = "b"))
        OutboxMonitoring.ajouter(saisie("d", parent = "a"))
        OutboxMonitoring.ajouter(saisie("autre"))
        val desc = OutboxMonitoring.descendants("a").toSet()
        assertEquals(setOf("b", "c", "d"), desc)
    }

    @Test
    fun supprimer_cascade_retire_l_objet_et_ses_descendants() {
        OutboxMonitoring.ajouter(saisie("a"))
        OutboxMonitoring.ajouter(saisie("b", parent = "a"))
        OutboxMonitoring.ajouter(saisie("c", parent = "b"))
        OutboxMonitoring.ajouter(saisie("seul"))
        val n = OutboxMonitoring.supprimerCascade("a")
        assertEquals(3, n) // a + b + c
        assertEquals(listOf("seul"), OutboxMonitoring.tout().map { it.uuid })
    }

    @Test
    fun purger_sent_ne_garde_que_les_non_envoyees() {
        OutboxMonitoring.ajouter(saisie("a", etat = SaisieEnAttente.Etat.SENT))
        OutboxMonitoring.ajouter(saisie("b", etat = SaisieEnAttente.Etat.PENDING))
        OutboxMonitoring.purgerSent()
        assertEquals(listOf("b"), OutboxMonitoring.tout().map { it.uuid })
    }

    @Test
    fun supprimer_une_seule_entree() {
        OutboxMonitoring.ajouter(saisie("a"))
        OutboxMonitoring.ajouter(saisie("b"))
        OutboxMonitoring.supprimer("a")
        assertTrue(OutboxMonitoring.tout().none { it.uuid == "a" })
    }
}
