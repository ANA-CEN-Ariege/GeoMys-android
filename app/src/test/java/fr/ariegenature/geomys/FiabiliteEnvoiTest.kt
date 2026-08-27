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
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.network.humaniserErreurReseau
import fr.ariegenature.geomys.store.PictoCache
import fr.ariegenature.geomys.store.SortieStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lot B de l'audit 2026-08-27 (fiabilité de l'envoi) — briques unitaires : marquages du
 * SortieStore désormais Booléens + statut incertain d'une occurrence, messages réseau humanisés
 * (panne réseau ≠ « identifiants expirés »), session PictoCache limitée à l'origine du serveur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FiabiliteEnvoiTest {

    private lateinit var store: SortieStore

    @Before
    fun setup() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        SortieStore.reinitialiserCacheMemoire()
        ctx.getSharedPreferences("sorties_store", Context.MODE_PRIVATE).edit().clear().commit()
        store = SortieStore(ctx)
    }

    private fun obs(id: String) = Observation(id = id, espece = "Merle", cdNom = 4001, latitude = 42.9, longitude = 1.4, nombre = 1)

    @Test
    fun marquages_renvoient_le_succes_et_l_incertitude_est_effacee_a_la_creation() {
        val sortie = Sortie(observations = listOf(obs("a"), obs("b")))
        store.ajouter(sortie)
        assertTrue(store.marquerObservationIncertaine(sortie.id, "a", 55))
        assertEquals(55, store.charger().single().observations.first { it.id == "a" }.idReleveIncertain)
        assertTrue(store.marquerObservationsEnvoyees(sortie.id, listOf("a")))
        val a = store.charger().single().observations.first { it.id == "a" }
        assertTrue(a.envoyeeServeur)
        assertNull("créée → plus d'incertitude", a.idReleveIncertain)
        assertTrue("liste vide = succès", store.marquerObservationsEnvoyees(sortie.id, emptyList()))
        assertTrue(store.marquerEnvoyee(sortie.id))
        assertTrue(store.charger().single().envoyeGeoNature)
    }

    @Test
    fun uuid_occurrence_stable_et_regenere_pour_un_ancien_json() {
        val o = obs("a")
        assertTrue(o.uuidOccurrence.isNotBlank())
        store.ajouter(Sortie(observations = listOf(o)))
        assertEquals(o.uuidOccurrence, store.charger().single().observations.single().uuidOccurrence)
    }

    @Test
    fun panne_reseau_n_est_plus_presentee_comme_identifiants_expires() {
        assertFalse(humaniserErreurReseau(java.net.UnknownHostException("geonature.example")).contains("401"))
        assertTrue(humaniserErreurReseau(java.net.UnknownHostException("geonature.example")).contains("réseau"))
        assertTrue(humaniserErreurReseau(java.net.SocketTimeoutException("timeout")).contains("délai"))
        assertTrue(humaniserErreurReseau(java.net.ConnectException("refused")).contains("Connexion"))
    }

    @Test
    fun session_picto_uniquement_vers_l_origine_du_serveur() {
        val base = "https://geonature.example.org/geonature"
        assertTrue(PictoCache.memeOrigine("https://geonature.example.org/api/media/x/img.jpg", base))
        assertTrue(PictoCache.memeOrigine("HTTPS://GEONATURE.example.org/x.png", base))
        assertFalse("autre hôte", PictoCache.memeOrigine("https://cdn.example.net/x.png", base))
        assertFalse("http nu vers un serveur https", PictoCache.memeOrigine("http://geonature.example.org/x.png", base))
        assertFalse("sans base : jamais de session", PictoCache.memeOrigine("https://geonature.example.org/x.png", null))
        assertFalse("URL illisible", PictoCache.memeOrigine("pas une url", base))
    }
}
