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
import fr.ariegenature.geomys.network.GeoNatureAuth
import fr.ariegenature.geomys.network.MonitoringApi
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.MonitoringCache
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chargement des options d'un champ `datalist` SANS RÉSEAU — le chemin de l'écran « Nouvelle
 * visite » et de la complétion d'une visite.
 *
 * RÉGRESSION COUVERTE (audit 2026-09-14, R7-C2) : depuis le lot B3 (audit 2026-08-27),
 * `GeoNatureAuth.loginAvecCookies` LÈVE l'IOException hors-ligne au lieu de renvoyer null — `null`
 * y est désormais réservé au refus d'authentification. `chargerOptionsDatalist` ne traitait que le
 * `null` : l'exception traversait `enrichirAvecOptions` (un `awaitAll` d'`async`) puis remontait
 * jusqu'au `lifecycleScope.launch` de NouvelleVisiteFragment, sans aucun try/catch sur le chemin.
 * L'application PLANTAIT à l'ouverture d'une visite hors réseau — et donc aussi à la complétion
 * d'une visite enregistrée incomplète la veille. Le module Monitoring était inutilisable sur le
 * terrain, ce qui est précisément l'usage de cette application.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatalistHorsLigneTest {

    private lateinit var config: GeoNatureConfig
    private val chemin = "monitorings/list/GENERIC/observer?id_list=3"

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        MonitoringCache.init(ctx)
        MonitoringCache.vider()
        GeoNatureAuth.invaliderCache()
        // Serveur démarré puis ARRÊTÉ : l'URL pointe sur un port fermé, donc une ConnectException
        // (une IOException) exactement comme un téléphone hors couverture.
        val serveurEteint = MockWebServer().apply { start(); shutdown() }
        config = GeoNatureConfig(ctx).apply {
            urlServeur = serveurEteint.url("/").toString().trimEnd('/')
            login = "alice"; motDePasse = "pwd"
        }
    }

    private fun propriete() = MonitoringApi.MonitoringPropertySchema(
        nom = "observers",
        typeWidget = "datalist",
        label = "Observateurs",
        obligatoire = false,
        apiUrl = chemin,
        keyLabel = "nom_complet",
        keyValue = "id_role",
    )

    @Test
    fun hors_ligne_le_cache_est_servi_et_AUCUNE_exception_ne_remonte() {
        MonitoringCache.setJson(
            MonitoringCache.keyOptionsDatalist(chemin),
            """[{"id_role":7,"nom_complet":"DUPONT jean"},{"id_role":8,"nom_complet":"MARTIN claire"}]""",
        )
        // Le test échoue par exception (et non par assertion) si la régression revient : c'est
        // exactement ce que voyait l'utilisateur, un crash.
        val options = runBlocking { MonitoringApi.chargerOptionsDatalist(config, propriete()) }
        assertNotNull("hors-ligne, les options doivent venir du cache, pas planter", options)
        assertEquals(listOf("7", "8"), options!!.map { it.value })
        assertEquals(listOf("DUPONT jean", "MARTIN claire"), options.map { it.label })
    }

    @Test
    fun hors_ligne_sans_cache_renvoie_null_sans_planter() {
        // Aucune option en cache : le formulaire doit se rendre avec une liste vide (l'appelant
        // injecte alors la valeur sauvegardée en édition), surtout pas faire tomber l'écran.
        val options = runBlocking { MonitoringApi.chargerOptionsDatalist(config, propriete()) }
        assertEquals(null, options)
    }
}
