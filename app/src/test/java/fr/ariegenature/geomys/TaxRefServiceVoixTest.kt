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
import fr.ariegenature.geomys.network.TaxRefService
import fr.ariegenature.geomys.network.TaxRefStatut
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.store.TaxRefEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Résolution vocale multi-candidats (niveau 1a) sur le cache LOCAL (gnConfig=null → pas de
 *  réseau) : [TaxRefService.rechercherParmiCandidats] essaie les hypothèses ASR dans l'ordre et
 *  retourne la première résolue (via la recherche étendue mots/approché) avec son texte gagnant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxRefServiceVoixTest {

    @Before
    fun setup() {
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
        TaxRefCache.vider()
        TaxRefCache.remplacerTout(
            listOf("Rougegorge familier" to 4001, "Tichodrome échelette" to 4494, "Bombus lucorum" to 240)
                .associate { (nom, cd) -> TaxRefCache.normaliser(nom) to TaxRefEntry(cd, nom, nom) }
        )
    }

    @Test
    fun candidat_3_gagnant_quand_1_et_2_ne_matchent_pas() = runBlocking {
        val (statut, gagnant) = TaxRefService.rechercherParmiCandidats(
            listOf("rouje gorje", "route gorge", "rouge gorge"), taxon = null, gnConfig = null,
        )
        assertTrue(statut is TaxRefStatut.Trouve)
        assertEquals(4001, (statut as TaxRefStatut.Trouve).cdNom)
        assertEquals("rouge gorge", gagnant)
    }

    @Test
    fun aucun_candidat_ne_matche_renvoie_non_trouve_et_premier_candidat() = runBlocking {
        val (statut, gagnant) = TaxRefService.rechercherParmiCandidats(
            listOf("zzz", "qqq"), taxon = null, gnConfig = null,
        )
        assertEquals(TaxRefStatut.NonTrouve, statut)
        assertEquals("zzz", gagnant)
    }

    @Test
    fun faute_de_transcription_rattrapee_par_l_approche() = runBlocking {
        // « bombus lucorun » (n au lieu de m) — aucun candidat exact, l'approché le rattrape.
        val (statut, gagnant) = TaxRefService.rechercherParmiCandidats(
            listOf("bombus lucorun"), taxon = null, gnConfig = null,
        )
        assertEquals(240, (statut as TaxRefStatut.Trouve).cdNom)
        assertEquals("bombus lucorun", gagnant)
    }
}
