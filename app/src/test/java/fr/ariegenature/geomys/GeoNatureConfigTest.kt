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
import fr.ariegenature.geomys.store.GeoNatureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Persistance et état de la configuration GeoNature (URL/login/mdp/dataset). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoNatureConfigTest {

    private fun config() = GeoNatureConfig(ApplicationProvider.getApplicationContext())

    @Before
    fun resetMotDePasseMemoire() {
        // Sous Robolectric le Keystore est absent → le mot de passe est gardé en mémoire
        // (companion process-wide), ce qui fuirait d'un test à l'autre. On repart propre.
        config().motDePasse = ""
    }

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

    @Test
    fun dataset_acceptable_pour_envoi_override() {
        val c = config()
        // Cache vide → permissif (on ne bloque pas l'envoi faute de pouvoir trancher).
        assertTrue(c.datasetAcceptablePourEnvoi(416))
        c.datasetsCacheJson = """[{"id":5,"nom":"A"},{"id":12,"nom":"B"}]"""
        assertTrue("dataset présent → accepté", c.datasetAcceptablePourEnvoi(12))
        assertFalse("dataset fantôme → refusé", c.datasetAcceptablePourEnvoi(416))
    }

    @Test
    fun dataset_valide_vrai_si_cache_vide() {
        // Cache datasets absent → on ne peut pas trancher : ne pas bloquer.
        val c = config()
        c.idDataset = "416"
        assertTrue("cache vide → considéré valide", c.datasetValide)
    }

    @Test
    fun dataset_valide_faux_si_id_absent_du_cache() {
        val c = config()
        c.datasetsCacheJson = """[{"id":5,"nom":"A"},{"id":12,"nom":"B"}]"""
        c.idDataset = "416"
        assertFalse("416 absent du cache → invalide", c.datasetValide)
        c.idDataset = "12"
        assertTrue("12 présent dans le cache → valide", c.datasetValide)
    }

    @Test
    fun saisie_possible_exige_dataset_present_sur_le_serveur() {
        val c = config()
        c.urlServeur = "https://x"; c.login = "u"; c.motDePasse = "p"
        c.datasetsCacheJson = """[{"id":5,"nom":"A"}]"""
        c.idDataset = "416"
        assertFalse("dataset fantôme → saisie interdite", c.saisiePossible)
        c.idDataset = "5"
        assertTrue("dataset présent → saisie autorisée", c.saisiePossible)
    }

    /** Configuration OCCTAX complète et cohérente avec les caches du serveur courant. */
    private fun configValideOcctax() = config().apply {
        urlServeur = "https://x"; login = "u"; motDePasse = "p"
        datasetsCacheJson = """[{"id":5,"nom":"A"}]"""
        listesCacheJson = """[{"id":100,"nom":"L"}]"""
        observateursCacheJson = """[{"idRole":3,"nomComplet":"Alice"}]"""
        idDataset = "5"; taxaListeId = "100"; observateurDefautId = "3"
    }

    @Test
    fun saisie_occtax_valide_quand_tout_present_dans_les_caches() {
        assertTrue(configValideOcctax().saisieOcctaxValide)
    }

    @Test
    fun saisie_occtax_invalide_si_cache_vide() {
        // Sélections renseignées mais aucun cache → on n'a pas la preuve qu'elles existent.
        val c = config()
        c.urlServeur = "https://x"; c.login = "u"; c.motDePasse = "p"
        c.idDataset = "5"; c.taxaListeId = "100"; c.observateurDefautId = "3"
        assertFalse("caches vides → non valide", c.saisieOcctaxValide)
    }

    @Test
    fun saisie_occtax_invalide_si_une_selection_fantome() {
        configValideOcctax().apply { idDataset = "999" }.let {
            assertFalse("dataset fantôme", it.saisieOcctaxValide)
        }
        configValideOcctax().apply { taxaListeId = "999" }.let {
            assertFalse("liste fantôme", it.saisieOcctaxValide)
        }
        configValideOcctax().apply { observateurDefautId = "999" }.let {
            assertFalse("observateur fantôme", it.saisieOcctaxValide)
        }
    }
}
