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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROPOSÉ ⇔ ACCEPTÉ, MODE D'AFFICHAGE COMPRIS (terrain 2026-09-17).
 *
 * Les propositions dépendent du mode : « noms français » n'affiche que des noms vernaculaires.
 * La résolution, elle, acceptait tout nom du cache appartenant au périmètre — donc aussi les noms
 * scientifiques. Sur le référentiel de l'appareil, 42 256 insectes sur 46 382 n'ont aucun nom
 * français : taper « Pieris » dans le groupe Insectes en mode français était accepté (cd_nom
 * 196270) alors que ce nom n'était jamais proposé.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProposeEgaleAccepteTest {

    private val liste = 100
    private val pieris = 196270      // genre de papillons, SANS nom français
    private val machaon = 54468      // avec nom français
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        TaxRefCache.init(ctx)
        TaxRefCache.vider()
        TaxRefCache.remplacerTout(
            mapOf(
                TaxRefCache.normaliser("Pieris") to TaxRefEntry(pieris, "Pieris", null),
                TaxRefCache.normaliser("Machaon") to TaxRefEntry(machaon, "Papilio machaon", "Machaon"),
                TaxRefCache.normaliser("Papilio machaon") to TaxRefEntry(machaon, "Papilio machaon", null),
            )
        )
        TaxRefCache.ajouterVerns(mapOf(machaon to listOf("Machaon")))
        TaxRefCache.ajouterListesParCdNom(mapOf(pieris to listOf(liste), machaon to listOf(liste)))
        TaxRefCache.setIndexParTaxon(mapOf(Taxon.INSECTE to listOf(pieris, machaon)))
    }

    private fun config() = GeoNatureConfig(ctx).apply { taxaListeId = liste.toString() }

    @Test
    fun en_mode_francais_un_taxon_sans_nom_francais_n_est_ni_propose_ni_accepte() = runBlocking {
        val proposes = TaxRefLocal.getSuggestionsTaxon(Taxon.INSECTE, scientifique = false, idListeFiltre = liste)
        assertTrue("« Pieris » ne doit pas être proposé", proposes.none { it.nom == "Pieris" })
        // C'était le bug : le statut disait « ✓ Pieris • cd_nom 196270 ».
        assertEquals(
            TaxRefStatut.NonTrouve,
            TaxRefService.rechercher("Pieris", Taxon.INSECTE, config(), scientifique = false),
        )
    }

    @Test
    fun en_mode_scientifique_le_meme_nom_est_propose_et_accepte() = runBlocking {
        val proposes = TaxRefLocal.getSuggestionsTaxon(Taxon.INSECTE, scientifique = true, idListeFiltre = liste)
        assertTrue(proposes.any { it.nom == "Pieris" && it.cdNom == pieris })
        val statut = TaxRefService.rechercher("Pieris", Taxon.INSECTE, config(), scientifique = true)
        assertEquals(pieris, (statut as TaxRefStatut.Trouve).cdNom)
    }

    @Test
    fun en_mode_francais_le_nom_scientifique_d_un_taxon_propose_n_est_pas_accepte_non_plus() = runBlocking {
        // Le Machaon EST proposé, mais par son nom français : son nom scientifique n'est pas dans
        // la liste affichée, il n'est donc pas acceptable dans ce mode.
        assertEquals(
            TaxRefStatut.NonTrouve,
            TaxRefService.rechercher("Papilio machaon", Taxon.INSECTE, config(), scientifique = false),
        )
        assertEquals(
            machaon,
            (TaxRefService.rechercher("Machaon", Taxon.INSECTE, config(), scientifique = false)
                as TaxRefStatut.Trouve).cdNom,
        )
    }

    @Test
    fun la_dictee_ne_peut_pas_non_plus_rendre_un_taxon_non_propose() = runBlocking {
        // Recherche étendue (mots + approché) : même règle, le taxon retenu doit être proposé.
        val (statut, _) = TaxRefService.rechercherParmiCandidats(
            listOf("pieris"), Taxon.INSECTE, config(), scientifique = false,
        )
        assertEquals(TaxRefStatut.NonTrouve, statut)
        val (statutSci, _) = TaxRefService.rechercherParmiCandidats(
            listOf("pieris"), Taxon.INSECTE, config(), scientifique = true,
        )
        assertEquals(pieris, (statutSci as TaxRefStatut.Trouve).cdNom)
    }

    @Test
    fun tout_nom_propose_est_accepte_dans_le_meme_mode() = runBlocking {
        for (scientifique in listOf(false, true)) {
            TaxRefLocal.getSuggestionsTaxon(Taxon.INSECTE, scientifique, liste).forEach { s ->
                val statut = TaxRefService.rechercher("" + s.nom, Taxon.INSECTE, config(), scientifique)
                assertEquals(
                    "« ${s.nom} » est proposé (mode sci=$scientifique) mais pas accepté",
                    s.cdNom, (statut as? TaxRefStatut.Trouve)?.cdNom,
                )
            }
        }
    }

    @Test
    fun les_propositions_sont_memorisees_par_configuration_et_perimees_par_une_synchro() {
        val fr1 = TaxRefLocal.getSuggestionsTaxon(Taxon.INSECTE, scientifique = false, idListeFiltre = liste)
        val sci = TaxRefLocal.getSuggestionsTaxon(Taxon.INSECTE, scientifique = true, idListeFiltre = liste)
        val fr2 = TaxRefLocal.getSuggestionsTaxon(Taxon.INSECTE, scientifique = false, idListeFiltre = liste)
        // Aller-retour sur le switch : la liste précédente est réutilisée telle quelle (même
        // instance), la bascule n'a donc rien à recalculer.
        assertSame("le retour au mode français doit être instantané", fr1, fr2)
        assertNotSame(fr1, sci)

        // Une écriture du cache les périme : la version change, tout est recalculé.
        TaxRefCache.ajouterVerns(mapOf(machaon to listOf("Machaon", "Grand porte-queue")))
        assertNotSame(
            fr1,
            TaxRefLocal.getSuggestionsTaxon(Taxon.INSECTE, scientifique = false, idListeFiltre = liste),
        )
    }

    /** SITE D'APPEL : pendant le recalcul, l'ancienne liste ne doit plus répondre — sinon les
     *  propositions du mode précédent s'affichent puis sont remplacées (terrain 2026-09-17). */
    @Test
    fun les_ecrans_retirent_les_propositions_perimees_avant_de_recalculer() {
        listOf("SaisieObservationFragment.kt", "SaisieRapideFragment.kt").forEach { f ->
            val src = java.io.File("src/main/java/fr/ariegenature/geomys/ui/$f").readText()
            val bloc = src.substringAfter("private fun refreshAutocompleteAdapter()")
                .substringBefore("viewLifecycleOwner.lifecycleScope.launch")
            assertTrue("$f doit fermer la liste déroulante", bloc.contains("dismissDropDown()"))
            assertTrue("$f doit retirer l'adapter périmé", bloc.contains("setAdapter(null)"))
        }
    }
}
