/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat

import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geonat.store.GeoNatureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Persistance et état de la configuration GeoNature (URL/login/mdp/dataset). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoNatureConfigTest {

    private fun config() = GeoNatureConfig(ApplicationProvider.getApplicationContext())

    @Test
    fun setters_getters_roundtrip() {
        val c = config()
        c.urlServeur = "https://geonature.test"
        c.login = "alice"
        c.motDePasse = "secret"
        c.idDataset = "12"
        // Nouvelle instance → relit les mêmes SharedPreferences.
        val c2 = config()
        assertEquals("https://geonature.test", c2.urlServeur)
        assertEquals("alice", c2.login)
        assertEquals("secret", c2.motDePasse)
        assertEquals("12", c2.idDataset)
    }

    @Test
    fun connexion_configuree_exige_url_login_mdp() {
        val c = config()
        c.urlServeur = "https://x"
        c.login = "u"
        assertFalse("mdp manquant", c.connexionConfiguree)
        c.motDePasse = "p"
        assertTrue(c.connexionConfiguree)
    }

    @Test
    fun est_configuree_exige_en_plus_un_dataset() {
        val c = config()
        c.urlServeur = "https://x"; c.login = "u"; c.motDePasse = "p"
        assertFalse("dataset manquant", c.estConfiguree)
        c.idDataset = "7"
        assertTrue(c.estConfiguree)
    }

    @Test
    fun id_role_defaut_moins_un() {
        assertEquals(-1, config().idRoleUtilisateur)
    }
}
