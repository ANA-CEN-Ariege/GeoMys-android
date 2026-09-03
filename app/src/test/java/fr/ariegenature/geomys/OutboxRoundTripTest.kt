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
import java.lang.reflect.Modifier

/**
 * ROUND-TRIP DISQUE de l'outbox monitoring : une saisie relue après redémarrage doit être
 * IDENTIQUE à celle qui a été écrite, champ par champ.
 *
 * Ce test existe à cause d'un bug terrain (2026-09-03) : `OutboxMonitoring.normaliser()`
 * reconstruit chaque entrée en listant ses champs un par un, et `champsManquants` y avait été
 * oublié. La saisie restait correcte tant qu'elle vivait dans le cache mémoire, puis perdait
 * le champ à la première relecture du disque — une visite « à compléter » redevenait complète
 * et pouvait partir telle quelle au serveur. Le contrôle se fait PAR RÉFLEXION : tout champ
 * ajouté au modèle et oublié dans `normaliser` fait échouer ce test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxRoundTripTest {

    @Before
    fun setup() {
        OutboxMonitoring.init(ApplicationProvider.getApplicationContext())
        OutboxMonitoring.vider()
    }

    /** Saisie dont CHAQUE champ porte une valeur non triviale (différente du défaut) : un
     *  champ perdu au round-trip devient donc forcément détectable. */
    private fun saisieComplete() = SaisieEnAttente(
        uuid = "u-1",
        moduleCode = "chronoventaire_ana",
        objectType = "visit",
        parentObjectType = "site",
        parentIdServeur = 555,
        parentUuidLocal = "u-parent",
        parentIdField = "id_base_site",
        nomsChampsSchema = listOf("time_start", "time_end"),
        champsTexteLibre = listOf("comments"),
        valeursJson = """{"time_start":"08:00"}""",
        dateLocale = 1_700_000_000_000L,
        etat = SaisieEnAttente.Etat.ERROR,
        messageErreur = "boum",
        idServeur = 42,
        objetCree = true,
        dejaTentee = true,
        uuidPayload = "uuid-sinp",
        uuidFieldName = "uuid_base_visit",
        mediaPathLocal = "file:///legacy.jpg",
        mediaPathsLocal = listOf("file:///a.jpg", "file:///b.jpg"),
        mediaSchemaDotTable = "gn_monitoring.t_base_visits",
        champsManquants = listOf("Heure de fin", "Température à la fin du relevé"),
    )

    /** Force une relecture DEPUIS LE DISQUE (ce que fait un redémarrage de l'appli). */
    private fun relireDepuisLeDisque() {
        OutboxMonitoring.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun tous_les_champs_survivent_a_un_redemarrage() {
        val ecrite = saisieComplete()
        assertTrue(OutboxMonitoring.ajouter(ecrite))
        relireDepuisLeDisque()
        val relue = OutboxMonitoring.tout().single { it.uuid == ecrite.uuid }

        val champs = SaisieEnAttente::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.name.contains('$') }
        assertTrue("le modèle doit exposer des champs", champs.isNotEmpty())
        for (champ in champs) {
            champ.isAccessible = true
            assertEquals(
                "champ « ${champ.name} » PERDU à la relecture du disque — l'ajouter à " +
                    "OutboxMonitoring.normaliser()",
                champ.get(ecrite), champ.get(relue),
            )
        }
    }

    @Test
    fun une_visite_a_completer_le_reste_apres_redemarrage() {
        // Le cas terrain exact : sans le correctif, la visite repartait « complète » et
        // pouvait être envoyée avec ses champs obligatoires vides.
        OutboxMonitoring.ajouter(saisieComplete())
        relireDepuisLeDisque()
        val relue = OutboxMonitoring.tout().single { it.uuid == "u-1" }
        assertTrue("la visite doit rester « à compléter »", relue.aCompleter)
        assertEquals(2, relue.manquants().size)
    }

    @Test
    fun saisie_minimale_relue_sans_perte_ni_champ_fantome() {
        // Saisie aux valeurs par défaut : elle doit rester COMPLÈTE (pas de faux « à compléter »).
        val minimale = SaisieEnAttente(
            uuid = "u-2", moduleCode = "stom", objectType = "visit", valeursJson = "{}",
        )
        OutboxMonitoring.ajouter(minimale)
        relireDepuisLeDisque()
        val relue = OutboxMonitoring.tout().single { it.uuid == "u-2" }
        assertTrue(relue.manquants().isEmpty())
        assertTrue(!relue.aCompleter)
        assertEquals(SaisieEnAttente.Etat.PENDING, relue.etat)
    }
}
