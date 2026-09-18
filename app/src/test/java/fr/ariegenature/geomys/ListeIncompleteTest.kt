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
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.store.TaxRefEntry
import fr.ariegenature.geomys.ui.configurationComplete
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
 * UNE LISTE DE TAXONS PARTIELLEMENT TÉLÉCHARGÉE BLOQUE LA SAISIE (audit 2026-09-18, constat T3).
 *
 * La pagination d'une liste peut s'interrompre (coupure réseau, serveur qui lâche). Les taxons
 * déjà reçus sont conservés — mieux vaut une liste partielle que rien — mais le cache se croit
 * alors complet : depuis la règle « seuls les noms proposés sont acceptés », la saisie refuse les
 * espèces manquantes avec un « Nom invalide » qui met en cause l'observateur.
 *
 * Décision produit du 2026-09-18 : tant qu'une liste est incomplète, la configuration n'est pas
 * complète — l'écran Paramètres se verrouille, avec le bandeau qui nomme la liste et les accès aux
 * saisies déjà faites. C'est un blocage SUBI, au même titre que le rechargement imposé par une
 * mise à jour, d'où les mêmes issues de secours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListeIncompleteTest {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        TaxRefCache.init(ctx)
        TaxRefCache.vider()
    }

    /** Configuration par ailleurs valide : connexion, jeu de données, liste et observateur en
     *  cache, et des taxons chargés. */
    private fun configValide() = fr.ariegenature.geomys.store.GeoNatureConfig(ctx).apply {
        urlServeur = "https://exemple.test"
        login = "u"
        motDePasse = "p"
        serveurCompatible = true
        datasetsCacheJson = """[{"id":1,"nom":"JDD"}]"""
        idDataset = "1"
        listesCacheJson = """[{"id":100,"nom":"Saisie"}]"""
        taxaListeId = "100"
        observateursCacheJson = """[{"idRole":7,"nom":"DUPONT jean"}]"""
        observateurDefautId = "7"
        rechargementRequisApresMaj = false
    }

    private fun poserDesTaxons() {
        TaxRefCache.remplacerTout(
            mapOf(TaxRefCache.normaliser("Machaon") to TaxRefEntry(54468, "Papilio machaon", "Machaon"))
        )
        TaxRefCache.versionSauvegardee = "17"
    }

    @Test
    fun cache_complet_la_configuration_est_complete() {
        poserDesTaxons()
        assertEquals(emptyList<Int>(), TaxRefCache.listesIncompletes)
        assertTrue(configurationComplete(configValide()))
    }

    @Test
    fun une_liste_incomplete_rend_la_configuration_incomplete() {
        poserDesTaxons()
        TaxRefCache.listesIncompletes = listOf(109)
        assertFalse("la saisie doit rester bloquée", configurationComplete(configValide()))
    }

    @Test
    fun un_chargement_reussi_leve_le_blocage() {
        poserDesTaxons()
        TaxRefCache.listesIncompletes = listOf(109, 102)
        assertFalse(configurationComplete(configValide()))
        // Ce que fait la synchro quand toutes les listes vont à leur terme.
        TaxRefCache.listesIncompletes = emptyList()
        assertTrue(configurationComplete(configValide()))
    }

    @Test
    fun le_verdict_survit_au_redemarrage_et_tombe_avec_le_cache() {
        TaxRefCache.listesIncompletes = listOf(105, 109)
        assertEquals(listOf(105, 109), TaxRefCache.listesIncompletes)
        // « Vider le cache » ou une purge de synchro repart d'une ardoise propre.
        TaxRefCache.vider()
        assertEquals(emptyList<Int>(), TaxRefCache.listesIncompletes)
    }

    /** SITE D'APPEL : la synchro doit écrire ce verdict, sinon le blocage ne se déclenche jamais. */
    @Test
    fun la_synchro_persiste_le_verdict_et_l_ecran_le_prend_en_compte() {
        val sync = File("src/main/java/fr/ariegenature/geomys/network/GeoNatureSync.kt").readText()
        assertTrue("la synchro doit persister les listes incomplètes",
            sync.contains("TaxRefCache.listesIncompletes = listesEnEchec.sorted()"))

        val config = File("src/main/java/fr/ariegenature/geomys/ui/ConfigGeoNatureFragment.kt").readText()
        assertTrue("configurationComplete doit en tenir compte",
            config.contains("TaxRefCache.listesIncompletes.isEmpty()"))
    }

    /**
     * L'écran Paramètres ne doit offrir AUCUNE sortie vers « Mes saisies / Mes visites / Mes
     * stations » — décision produit de l'utilisateur (2026-09-18) : le blocage est entier, sans
     * porte dérobée, quelle qu'en soit la cause. Deux audits ont proposé l'inverse (R7-C1 pour le
     * rechargement imposé, R7-M3 pour l'élargir) ; cette garde est là pour que la proposition ne
     * repasse pas en douce, dans le code comme dans le layout.
     */
    @Test
    fun parametres_n_ouvre_aucune_porte_derobee_vers_les_saisies() {
        val config = File("src/main/java/fr/ariegenature/geomys/ui/ConfigGeoNatureFragment.kt").readText()
        val layout = File("src/main/res/layout/fragment_config_geonature.xml").readText()
        for (interdit in listOf(
            "llAccesSaisiesRechargement", "btnRechargementMesSaisies",
            "btnRechargementMesVisites", "btnRechargementMesStations",
        )) {
            assertFalse(
                "ConfigGeoNatureFragment ne doit plus référencer $interdit : les accès de secours " +
                    "ont été retirés sur demande explicite (2026-09-18).",
                config.contains(interdit),
            )
        }
        assertFalse(
            "le layout de Paramètres ne doit plus porter les boutons d'accès aux saisies",
            layout.contains("ll_acces_saisies_rechargement"),
        )
        assertFalse(
            "le bandeau ne doit plus renvoyer à des écrans accessibles « ci-dessous »",
            config.contains("consultables ci-dessous"),
        )
    }
}
