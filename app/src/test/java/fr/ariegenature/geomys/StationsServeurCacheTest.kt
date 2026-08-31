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
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.network.invaliderCachesSession
import fr.ariegenature.geomys.store.StationsServeurCache
import fr.ariegenature.geomys.store.viderCachesSynchronises
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Cache hors-ligne des stations serveur OccHab (StationsServeurCache) : rempli par la synchro
 *  (tous JDD) et au fil de l'eau par la carte (un JDD), lu par JDD en repli hors-ligne, purgé
 *  par « Vider le cache » et au changement d'identité serveur. Cf. [[occhab-module]]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StationsServeurCacheTest {

    @Before
    fun setup() {
        StationsServeurCache.init(ApplicationProvider.getApplicationContext())
        StationsServeurCache.vider()
    }

    private fun station(
        idServeur: Int,
        idJdd: Int,
        nom: String? = null,
        anaEval: String? = null,
        habitats: List<OccHabHabitat> = emptyList(),
    ) = OccHabStation(
        idStationServeur = idServeur,
        idDataset = idJdd,
        stationName = nom,
        anaEvalJson = anaEval,
        habitats = habitats,
        origineServeur = true,
        latitude = 42.9, longitude = 1.6,
    )

    // ── Lecture par JDD ──

    @Test
    fun lire_filtre_par_jeu_de_donnees() {
        StationsServeurCache.remplacerTout(listOf(station(1, 10), station(2, 10), station(3, 20)))
        assertEquals(listOf(1, 2), StationsServeurCache.lire(10).map { it.idStationServeur })
        assertEquals(listOf(3), StationsServeurCache.lire(20).map { it.idStationServeur })
        assertTrue(StationsServeurCache.lire(30).isEmpty())
    }

    @Test
    fun cache_jamais_ecrit_lit_vide_et_sans_date() {
        assertTrue(StationsServeurCache.lire(10).isEmpty())
        assertNull(StationsServeurCache.dateChargement)
        assertEquals(0, StationsServeurCache.count)
    }

    @Test
    fun count_total_tous_jdd_confondus() {
        // Compteur de l'écran Paramètres (boîte « Chargement des données »).
        StationsServeurCache.remplacerTout(listOf(station(1, 10), station(2, 10), station(3, 20)))
        assertEquals(3, StationsServeurCache.count)
        StationsServeurCache.vider()
        assertEquals(0, StationsServeurCache.count)
    }

    // ── Remplacements ──

    @Test
    fun remplacer_jdd_conserve_les_autres_jdd() {
        // Synchro : tous JDD. Puis la carte recharge le JDD 10 seul (une station retirée côté
        // serveur, une nouvelle) : le JDD 20 (non rechargé) doit survivre.
        StationsServeurCache.remplacerTout(listOf(station(1, 10), station(2, 10), station(3, 20)))
        StationsServeurCache.remplacerJdd(10, listOf(station(2, 10), station(4, 10)))
        assertEquals(listOf(2, 4), StationsServeurCache.lire(10).map { it.idStationServeur })
        assertEquals(listOf(3), StationsServeurCache.lire(20).map { it.idStationServeur })
    }

    @Test
    fun remplacer_tout_vide_ecrase_reellement() {
        // Un chargement RÉUSSI qui renvoie 0 station est un vrai état (stations supprimées côté
        // serveur) : le cache doit refléter le vide — l'appelant ne remplace jamais sur échec.
        StationsServeurCache.remplacerTout(listOf(station(1, 10)))
        StationsServeurCache.remplacerTout(emptyList())
        assertTrue(StationsServeurCache.lire(10).isEmpty())
        assertNotNull(StationsServeurCache.dateChargement) // le cache existe, il est juste vide
    }

    @Test
    fun remplacer_met_a_jour_la_date_de_chargement() {
        val avant = System.currentTimeMillis()
        StationsServeurCache.remplacerTout(listOf(station(1, 10)))
        val date = StationsServeurCache.dateChargement
        assertNotNull(date)
        assertTrue(date!! >= avant)
    }

    // ── Durabilité (round-trip disque : ce que la RÉÉDITION doit préserver survit) ──

    @Test
    fun round_trip_disque_preserve_les_champs_de_reedition() {
        val h = OccHabHabitat(cdHab = 1234, nomCite = "Forêts de Fagus", idHabitatServeur = 77)
        StationsServeurCache.remplacerTout(listOf(
            station(5, 10, nom = "Tourbière", anaEval = "{\"etat\":\"bon\"}", habitats = listOf(h)),
        ))
        // Relecture DEPUIS LE DISQUE (l'état mémoire est jeté, comme après un kill du process).
        StationsServeurCache.resetPourTests()
        val relue = StationsServeurCache.lire(10).single()
        assertEquals(5, relue.idStationServeur)
        assertEquals("Tourbière", relue.stationName)
        assertEquals("{\"etat\":\"bon\"}", relue.anaEvalJson)
        assertTrue(relue.origineServeur)
        assertEquals(1234, relue.habitats.single().cdHab)
        assertEquals(77, relue.habitats.single().idHabitatServeur)
        assertEquals("Forêts de Fagus", relue.habitats.single().nomCite)
    }

    @Test
    fun fichier_corrompu_lit_vide_sans_crash() {
        StationsServeurCache.remplacerTout(listOf(station(1, 10)))
        val f = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "stations_serveur_occhab_v1.json",
        )
        f.writeText("{ pas du json")
        StationsServeurCache.resetPourTests()
        assertTrue(StationsServeurCache.lire(10).isEmpty())
        assertNull(StationsServeurCache.dateChargement)
    }

    // ── Purges ──

    @Test
    fun vider_caches_synchronises_purge_les_stations() {
        StationsServeurCache.remplacerTout(listOf(station(1, 10)))
        viderCachesSynchronises()
        assertTrue(StationsServeurCache.lire(10).isEmpty())
        assertNull(StationsServeurCache.dateChargement)
    }

    @Test
    fun changement_didentite_serveur_purge_les_stations() {
        // URL/login/mdp changés : id_station et id_dataset de l'ancienne instance, stations d'un
        // autre compte — rien ne doit survivre (même logique que les nomenclatures).
        StationsServeurCache.remplacerTout(listOf(station(1, 10)))
        invaliderCachesSession()
        assertTrue(StationsServeurCache.lire(10).isEmpty())
    }
}
