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
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.network.OccHabEnvoiResult
import fr.ariegenature.geomys.network.envoyerSaisieOccHabVersGeoNature
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OccHabStore
import fr.ariegenature.geomys.store.StationsServeurCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UNE STATION ENVOYÉE ENTRE DANS LE CACHE DES STATIONS SERVEUR (constat terrain 2026-10-17).
 *
 * Le cache n'était alimenté que par la synchro et par le chargement réseau de la carte. Deux
 * conséquences, toutes deux constatées sur le terrain : une station tout juste créée puis envoyée
 * n'y figurait pas (invisible et non aimantable tant qu'on n'avait pas rechargé), et une station
 * MODIFIÉE puis envoyée y restait dans sa version PRÉCÉDENTE — rouvrir OccHab réaffichait
 * l'ancienne géométrie, celle du cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StationServeurCacheApresEnvoiTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: OccHabStore
    private lateinit var config: GeoNatureConfig

    @Before
    fun setup() {
        OccHabStore.reinitialiserCacheMemoire()
        ctx.getSharedPreferences("occhab_store", Context.MODE_PRIVATE).edit().clear().commit()
        store = OccHabStore(ctx)
        config = GeoNatureConfig(ctx)
        StationsServeurCache.init(ctx)
        StationsServeurCache.vider()
    }

    private fun station(id: String, lat: Double, idServeur: Int? = null) = OccHabStation(
        id = id, latitude = lat, longitude = 1.40, idDataset = 627, idStationServeur = idServeur,
        habitats = listOf(OccHabHabitat(cdHab = 1, habitatLabel = "Prairie")),
    )

    private fun saisie() = store.charger().first { it.id == "sa" }
    private fun enCache(idServeur: Int) = StationsServeurCache.lire(627).firstOrNull { it.idStationServeur == idServeur }

    @Test
    fun une_station_creee_puis_envoyee_entre_dans_le_cache() = runBlocking {
        store.upsertStation("sa", station("A", lat = 42.90))
        val res = envoyerSaisieOccHabVersGeoNature(saisie(), store, config) { _, _ ->
            OccHabEnvoiResult(idStationServeur = 1234, nbHabitats = 1)
        }
        assertTrue("envoi réussi : ${res.message}", res.succes)

        val mise = enCache(1234)
        assertNotNull("la station créée doit figurer parmi les stations serveur", mise)
        assertEquals(42.90, mise!!.latitude, 0.0001)
        assertTrue("elle doit avoir la forme d'une station serveur", mise.origineServeur)
        assertTrue(mise.envoyeGeoNature)
    }

    /** LE cas signalé : on modifie une station existante, on l'envoie, on rouvre OccHab. */
    @Test
    fun une_station_modifiee_puis_envoyee_remplace_sa_version_en_cache() = runBlocking {
        // État initial du cache : la station 900 telle qu'elle est sur le serveur.
        StationsServeurCache.remplacerTout(listOf(
            station("serveur-900", lat = 42.90, idServeur = 900).copy(origineServeur = true, envoyeGeoNature = true),
            station("serveur-901", lat = 43.50, idServeur = 901).copy(origineServeur = true, envoyeGeoNature = true),
        ))
        // L'utilisateur la reprend, déplace sa géométrie, et envoie.
        store.upsertStation("sa", station("A", lat = 42.99, idServeur = 900))
        val res = envoyerSaisieOccHabVersGeoNature(saisie(), store, config) { _, _ ->
            OccHabEnvoiResult(idStationServeur = 900, nbHabitats = 1)
        }
        assertTrue("envoi réussi : ${res.message}", res.succes)

        assertEquals(
            "le cache doit porter la version ENVOYÉE, pas la précédente",
            42.99, enCache(900)!!.latitude, 0.0001,
        )
        assertEquals(
            "une seule entrée pour cette station serveur",
            1, StationsServeurCache.lire(627).count { it.idStationServeur == 900 },
        )
        assertEquals(
            "les autres stations du cache sont conservées",
            43.50, enCache(901)!!.latitude, 0.0001,
        )
    }

    /** La date du cache situe l'instantané du serveur DANS SON ENSEMBLE : une station envoyée ne
     *  dit rien des autres, elle ne doit donc pas faire passer tout le cache pour frais. */
    @Test
    fun un_envoi_ne_rafraichit_pas_la_date_du_cache() = runBlocking {
        StationsServeurCache.remplacerTout(listOf(
            station("serveur-900", lat = 42.90, idServeur = 900).copy(origineServeur = true)))
        val dateAvant = StationsServeurCache.dateChargement
        store.upsertStation("sa", station("A", lat = 42.99, idServeur = 900))
        envoyerSaisieOccHabVersGeoNature(saisie(), store, config) { _, _ ->
            OccHabEnvoiResult(idStationServeur = 900, nbHabitats = 1)
        }
        assertEquals("la date du dernier chargement serveur reste la même",
            dateAvant, StationsServeurCache.dateChargement)
    }

    /** CONTRE-ÉPREUVE : un envoi qui échoue ne doit rien écrire dans le cache. */
    @Test
    fun un_envoi_en_echec_ne_touche_pas_le_cache() = runBlocking {
        StationsServeurCache.remplacerTout(listOf(
            station("serveur-900", lat = 42.90, idServeur = 900).copy(origineServeur = true)))
        store.upsertStation("sa", station("A", lat = 42.99, idServeur = 900))
        envoyerSaisieOccHabVersGeoNature(saisie(), store, config) { _, _ ->
            throw fr.ariegenature.geomys.network.GNErreur.EnvoiEchoue(400, "refus")
        }
        assertEquals("le cache garde la version du serveur",
            42.90, enCache(900)!!.latitude, 0.0001)
    }

    // ── Une station appartient à UNE saisie ─────────────────────────────────

    /**
     * La carte affiche les stations de TOUTES les saisies : on peut donc reprendre une station
     * née dans une autre. Elle doit rester dans la sienne — sinon « Mes stations » gagnait un
     * relevé de plus à chaque modification (constat terrain 2026-10-18 : créer une station, la
     * resélectionner, valider sans rien changer ⇒ deux relevés).
     */
    @Test
    fun modifier_une_station_depuis_une_autre_saisie_ne_la_duplique_pas() {
        store.upsertStation("saisie-1", station("A", lat = 42.90))
        // Nouvelle session (on est ressorti d'OccHab et revenu) : la saisie courante a changé.
        store.upsertStation("saisie-2", station("A", lat = 42.95))

        val saisies = store.charger().filter { s -> s.stations.any { it.id == "A" } }
        assertEquals("la station ne doit exister que dans UNE saisie", 1, saisies.size)
        assertEquals("et c'est celle où elle est née", "saisie-1", saisies.first().id)
        assertEquals(
            "avec la modification appliquée",
            42.95, saisies.first().stations.first { it.id == "A" }.latitude, 0.0001,
        )
    }

    /** CONTRE-ÉPREUVE : deux stations DIFFÉRENTES dans deux saisies restent bien séparées. */
    @Test
    fun deux_stations_distinctes_vivent_dans_leurs_saisies_respectives() {
        store.upsertStation("saisie-1", station("A", lat = 42.90))
        store.upsertStation("saisie-2", station("B", lat = 43.10))
        assertEquals(1, store.stationsDeSaisie("saisie-1").size)
        assertEquals(1, store.stationsDeSaisie("saisie-2").size)
    }
}
