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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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

    // ── Garde sur le SITE D'APPEL ──

    /** Remonte depuis le répertoire de travail des tests jusqu'à trouver le fichier, et rend son
     *  CODE seul : les lignes de commentaire sont retirées, sinon un commentaire qui cite la
     *  fonction proscrite (« PAS viderCachesSynchronises() : … », justement écrit au-dessus de
     *  l'appel pour expliquer la règle) ferait échouer la garde. */
    private fun lireCodeSource(chemin: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val f = File(dir, chemin)
            if (f.exists()) {
                return f.readLines()
                    .filterNot { val l = it.trim(); l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") }
                    .joinToString("\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("Source introuvable : $chemin")
    }

    @Test
    fun syncrunner_appelle_la_purge_restreinte_et_jamais_la_purge_totale() {
        // Les deux tests ci-dessus prouvent que les DEUX fonctions se comportent correctement — pas
        // que SyncRunner appelle la bonne. Or c'est précisément le site d'appel qui portait le
        // défaut : la régression consistait à appeler `viderCachesSynchronises()` là où il fallait
        // la purge restreinte, et elle repasserait sans qu'aucun test ne tombe.
        //
        // `SyncRunner.executer()` n'est pas testable unitairement (réseau, coroutines, phases A et
        // B entrelacées) : on garde donc le site d'appel par une lecture de la source. C'est
        // grossier, mais cela ferme exactement le trou, et le message dit quoi faire.
        val src = lireCodeSource("app/src/main/java/fr/ariegenature/geomys/network/SyncRunner.kt")
        assertTrue(
            "SyncRunner doit purger via viderCachesReecritsEnPhaseB() — la purge restreinte aux " +
                "caches que la phase B réécrit (audit 2026-09-14, R1-C2).",
            src.contains("viderCachesReecritsEnPhaseB()"),
        )
        assertFalse(
            "SyncRunner ne doit JAMAIS appeler viderCachesSynchronises() : cette purge efface aussi " +
                "les deux caches HABREF et les stations serveur, que la phase A vient d'écrire et que " +
                "la phase B ne réécrit pas — après un rechargement annoncé réussi, plus aucun habitat " +
                "proposé hors ligne, donc saisie OccHab impossible (audit 2026-09-14, R1-C2).",
            src.contains("viderCachesSynchronises()"),
        )
    }
}
