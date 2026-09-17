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
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.network.TaxRefService
import fr.ariegenature.geomys.network.TaxRefStatut
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.store.TaxRefEntry
import kotlinx.coroutines.runBlocking
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
 * LA RÉSOLUTION D'UN NOM EST PUREMENT LOCALE (décision produit 2026-09-17).
 *
 * L'interrogation de l'API TaxHub a été retirée du chemin de saisie. Elle partait à chaque pause
 * de frappe sur un texte que le cache ne résolvait pas, avec 5 s de timeout — donc autant
 * d'attentes sur le terrain en réseau faible — et, quand elle aboutissait, réécrivait les 10 Mo
 * du cache en invalidant tous les mémos (constat C3 de l'audit). Son apport est devenu nul depuis
 * que la saisie n'accepte que les noms PROPOSÉS, lesquels sortent du cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResolutionPurementLocaleTest {

    private lateinit var ctx: Context
    private lateinit var fichierCache: File

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        TaxRefCache.init(ctx)
        TaxRefCache.vider()
        TaxRefCache.remplacerTout(
            mapOf(
                TaxRefCache.normaliser("Machaon") to TaxRefEntry(54468, "Papilio machaon", "Machaon"),
            )
        )
        TaxRefCache.ajouterVerns(mapOf(54468 to listOf("Machaon")))
        TaxRefCache.setIndexParTaxon(mapOf(Taxon.INSECTE to listOf(54468)))
        fichierCache = File(ctx.filesDir, "taxref/cache_v3.json")
    }

    /** Un serveur est configuré : avant, le nom inconnu partait vers l'API. Plus maintenant —
     *  et rien n'est écrit sur le disque au passage. */
    @Test
    fun un_nom_inconnu_n_interroge_pas_le_serveur_et_n_ecrit_rien() = runBlocking {
        val config = GeoNatureConfig(ctx).apply {
            urlServeur = "https://exemple-inexistant.invalid"
            login = "x"
            motDePasse = "y"
        }
        val avant = fichierCache.readText()
        // Un appel réseau vers un hôte inexistant coûterait plusieurs secondes ; celui-ci rend
        // la main immédiatement puisqu'il ne sort jamais du cache.
        val debut = System.currentTimeMillis()
        val statut = TaxRefService.rechercher("Espèce totalement inconnue", Taxon.INSECTE, config)
        val duree = System.currentTimeMillis() - debut
        assertEquals(TaxRefStatut.NonTrouve, statut)
        assertTrue("la résolution doit être immédiate (aucun réseau), mesuré : $duree ms", duree < 2000)
        assertEquals("le cache ne doit pas être réécrit", avant, fichierCache.readText())
    }

    @Test
    fun un_nom_du_cache_est_resolu_normalement() = runBlocking {
        val statut = TaxRefService.rechercher("Machaon", Taxon.INSECTE, GeoNatureConfig(ctx))
        assertEquals(54468, (statut as TaxRefStatut.Trouve).cdNom)
        assertEquals("Papilio machaon", statut.nomScientifique)
    }

    /** SITE D'APPEL : que le réseau ne revienne pas dans le chemin de résolution. */
    @Test
    fun le_service_taxref_ne_contient_plus_aucun_appel_reseau() {
        val src = File("src/main/java/fr/ariegenature/geomys/network/TaxRefService.kt").readText()
        listOf("HttpClient", "URL(", "urlTaxhub", "JSONArray").forEach {
            assertFalse("TaxRefService ne doit plus référencer « $it »", src.contains(it))
        }
    }

    /** Et que personne ne réintroduise une écriture du cache au fil de la saisie (constat C3). */
    @Test
    fun aucun_chemin_de_saisie_n_ecrit_dans_le_cache_taxref() {
        listOf(
            "network/TaxRefService.kt",
            "ui/SaisieObservationFragment.kt",
            "ui/SaisieRapideFragment.kt",
            "ui/saisie/TaxRefLookup.kt",
            "monitoring/form/FormulaireRenderer.kt",
        ).forEach { chemin ->
            val src = File("src/main/java/fr/ariegenature/geomys/$chemin").readText()
            assertFalse("$chemin ne doit pas appeler TaxRefCache.set()", src.contains("TaxRefCache.set("))
        }
    }
}
