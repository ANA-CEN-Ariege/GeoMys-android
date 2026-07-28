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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.store.OutboxMonitoring
import fr.ariegenature.geomys.store.SaisieEnAttente
import fr.ariegenature.geomys.store.SortieStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * Correctif du lost-update mutualisé par [fr.ariegenature.geomys.store.JsonCollectionStore] :
 * les mutations viennent du thread UI (saisie au fil de l'eau) ET de Dispatchers.IO (chemin
 * d'envoi). Sans verrou autour du lire-modifier-écrire, deux écrivains croisés s'écrasent une
 * entrée. On lance N écrivains en parallèle, chacun ajoutant M entrées uniques, et on vérifie
 * qu'AUCUNE n'est perdue — sur le backend prefs (SortieStore, qui n'avait pas de verrou avant)
 * ET sur le backend fichier (OutboxMonitoring).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreConcurrenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nbEcrivains = 8
    private val parEcrivain = 60

    @Before
    fun setup() {
        SortieStore.reinitialiserCacheMemoire()
        context.getSharedPreferences("sorties_store", Context.MODE_PRIVATE).edit().clear().commit()
        OutboxMonitoring.init(context)
        OutboxMonitoring.vider()
    }

    /** Lance [nbEcrivains] threads démarrant ensemble (barrière), chacun exécutant [action]
     *  [parEcrivain] fois, et attend la fin. */
    private fun enParallele(action: (ecrivain: Int, i: Int) -> Unit) {
        val depart = CountDownLatch(1)
        val fin = CountDownLatch(nbEcrivains)
        (0 until nbEcrivains).forEach { e ->
            Thread {
                depart.await()
                for (i in 0 until parEcrivain) action(e, i)
                fin.countDown()
            }.start()
        }
        depart.countDown() // top départ commun → contention maximale
        fin.await()
    }

    @Test
    fun sortie_store_ajouts_concurrents_ne_perdent_aucune_entree() {
        val store = SortieStore(context)
        enParallele { e, i -> store.ajouter(Sortie(id = "s-$e-$i")) }

        val toutes = store.charger()
        assertEquals("aucune sortie perdue", nbEcrivains * parEcrivain, toutes.size)
        assertEquals("aucun doublon / id écrasé", nbEcrivains * parEcrivain, toutes.map { it.id }.toSet().size)
    }

    @Test
    fun outbox_monitoring_ajouts_concurrents_ne_perdent_aucune_entree() {
        enParallele { e, i ->
            OutboxMonitoring.ajouter(
                SaisieEnAttente(uuid = "u-$e-$i", moduleCode = "STOC", objectType = "visite", valeursJson = "{}")
            )
        }

        val toutes = OutboxMonitoring.tout()
        assertEquals("aucune saisie perdue", nbEcrivains * parEcrivain, toutes.size)
        assertEquals("aucun doublon / uuid écrasé", nbEcrivains * parEcrivain, toutes.map { it.uuid }.toSet().size)
    }
}
