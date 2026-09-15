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
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.network.HabitatSuggestion
import fr.ariegenature.geomys.store.HabitatCache
import fr.ariegenature.geomys.store.HabitatCacheOccHab
import fr.ariegenature.geomys.store.StationsServeurCache
import fr.ariegenature.geomys.store.viderCachesReecritsEnPhaseB
import fr.ariegenature.geomys.store.viderCachesSynchronises
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Purge du « rechargement requis après mise à jour » (SyncRunner).
 *
 * RÉGRESSION COUVERTE (audit 2026-09-14, R1-C2) : la purge était faite APRÈS la phase A, qui venait
 * précisément de réécrire les deux caches HABREF et les stations serveur — et la phase B ne les
 * réécrit jamais. Après un rechargement pourtant annoncé RÉUSSI, l'utilisateur se retrouvait sans
 * aucun habitat proposé hors ligne : saisie OccHab impossible, l'habitat y étant obligatoire.
 *
 * L'invariant tient en une phrase : **SyncRunner ne purge que ce que la phase B réécrit derrière
 * elle** ; le bouton « Vider le cache » de Paramètres, lui, enlève tout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PurgeRechargementApresMajTest {

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        HabitatCache.init(ctx)
        HabitatCacheOccHab.init(ctx)
        StationsServeurCache.init(ctx)
        viderCachesSynchronises()
        StationsServeurCache.resetPourTests()
    }

    private fun remplirLesCachesDeLaPhaseA() {
        HabitatCache.remplacerTout(listOf(HabitatSuggestion(cdHab = 1, libelle = "Hêtraie")))
        HabitatCacheOccHab.remplacerTout(listOf(HabitatSuggestion(cdHab = 2, libelle = "Pelouse")))
        StationsServeurCache.remplacerTout(
            listOf(OccHabStation(idStationServeur = 1, idDataset = 10, origineServeur = true))
        )
        assertEquals(1, HabitatCache.count)
        assertEquals(1, HabitatCacheOccHab.count)
        assertEquals(1, StationsServeurCache.count)
    }

    @Test
    fun purge_de_synchro_epargne_les_caches_ecrits_par_la_phase_A() {
        remplirLesCachesDeLaPhaseA()
        viderCachesReecritsEnPhaseB()
        assertEquals("HABREF Occtax effacé juste après avoir été rechargé", 1, HabitatCache.count)
        assertEquals("HABREF OccHab effacé juste après avoir été rechargé", 1, HabitatCacheOccHab.count)
        StationsServeurCache.resetPourTests()
        assertEquals("stations serveur effacées juste après avoir été rechargées", 1, StationsServeurCache.count)
    }

    @Test
    fun vider_le_cache_de_parametres_enleve_bien_tout() {
        // Contre-épreuve : le bouton « Vider le cache » garde son contrat complet.
        remplirLesCachesDeLaPhaseA()
        viderCachesSynchronises()
        assertEquals(0, HabitatCache.count)
        assertEquals(0, HabitatCacheOccHab.count)
        StationsServeurCache.resetPourTests()
        assertEquals(0, StationsServeurCache.count)
    }
}
