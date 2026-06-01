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

import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.store.NomenclatureCache
import fr.ariegenature.geonat.ui.saisie.ChampsTaxon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique d'affichage des champs par taxon (factorisée depuis 3 fragments). */
class ChampsTaxonTest {

    // ── champsCaracterisation (niveau occurrence) ──────────────────────────────
    @Test
    fun caracterisation_oiseau_a_statut_bio_et_comportement() {
        val c = ChampsTaxon.champsCaracterisation(Taxon.OISEAU)
        assertTrue("STATUT_BIO" in c)
        assertTrue("OCC_COMPORTEMENT" in c)
        assertTrue("METH_DETERMIN" in c)
    }

    @Test
    fun caracterisation_n_inclut_jamais_les_champs_de_denombrement() {
        // SEXE / STADE_VIE / OBJ_DENBR / TYP_DENBR sont gérés au dénombrement, pas ici.
        Taxon.entries.forEach { t ->
            val c = ChampsTaxon.champsCaracterisation(t)
            listOf("SEXE", "STADE_VIE", "OBJ_DENBR", "TYP_DENBR").forEach {
                assertFalse("$it ne doit pas être dans la caractérisation de $t", it in c)
            }
        }
    }

    @Test
    fun caracterisation_plante_et_fonge_minimal() {
        assertEquals(setOf("METH_OBS", "PREUVE_EXIST", "METH_DETERMIN"), ChampsTaxon.champsCaracterisation(Taxon.PLANTE))
        assertEquals(setOf("METH_OBS", "PREUVE_EXIST", "METH_DETERMIN"), ChampsTaxon.champsCaracterisation(Taxon.FONGE))
    }

    @Test
    fun caracterisation_definie_pour_tous_les_taxons() {
        Taxon.entries.forEach { assertTrue(ChampsTaxon.champsCaracterisation(it).isNotEmpty()) }
    }

    // ── champsObservationDetails (écran legacy : caractérisation + dénombrement) ─
    @Test
    fun details_inclut_sexe_stade_et_denombrement_pour_oiseau() {
        val c = ChampsTaxon.champsObservationDetails(Taxon.OISEAU)
        listOf("SEXE", "STADE_VIE", "OBJ_DENBR", "TYP_DENBR", "STATUT_BIO", "OCC_COMPORTEMENT").forEach {
            assertTrue("$it attendu pour OISEAU", it in c)
        }
    }

    @Test
    fun details_plante_sans_sexe() {
        val c = ChampsTaxon.champsObservationDetails(Taxon.PLANTE)
        assertFalse("SEXE" in c)
        assertTrue("STADE_VIE" in c) // stade phénologique
    }

    @Test
    fun details_fonge_sans_sexe_ni_stade() {
        val c = ChampsTaxon.champsObservationDetails(Taxon.FONGE)
        assertFalse("SEXE" in c)
        assertFalse("STADE_VIE" in c)
    }

    // ── groupesEtRegno ──────────────────────────────────────────────────────────
    // NB : la branche PLANTE de groupesEtRegno appelle NomenclatureCache.groupesBotaniquesConnus()
    // → TaxRefCache (SharedPreferences), non initialisé en test JVM → non couvert ici.
    @Test
    fun regno_fonge_et_animalia() {
        val fonge = ChampsTaxon.groupesEtRegno(Taxon.FONGE, "")
        assertEquals("Fungi", fonge.second)
        assertEquals(NomenclatureCache.GROUPES_FONGE, fonge.first)
        assertEquals(Pair(setOf("Animalia"), "Animalia"), ChampsTaxon.groupesEtRegno(Taxon.MOLLUSQUE, ""))
        assertEquals(Pair(setOf("Animalia"), "Animalia"), ChampsTaxon.groupesEtRegno(Taxon.INVERTEBRES, ""))
    }

    @Test
    fun groupe_fallback_par_taxon_quand_groupe2inpn_vide() {
        assertEquals(Pair(setOf("Oiseaux"), "Animalia"), ChampsTaxon.groupesEtRegno(Taxon.OISEAU, ""))
        assertEquals(Pair(setOf("Amphibiens"), "Animalia"), ChampsTaxon.groupesEtRegno(Taxon.BATRACIEN, ""))
    }

    @Test
    fun groupe2inpn_explicite_prime_sur_le_fallback() {
        val (groupes, regno) = ChampsTaxon.groupesEtRegno(Taxon.OISEAU, "Rapaces diurnes")
        assertEquals(setOf("Rapaces diurnes"), groupes)
        assertEquals("Animalia", regno)
    }
}
