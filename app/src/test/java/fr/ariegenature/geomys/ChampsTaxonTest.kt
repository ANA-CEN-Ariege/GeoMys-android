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

import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.ui.saisie.ChampsTaxon
import org.junit.Assert.assertEquals
import org.junit.Test

/** Contexte taxonomique (groupesEtRegno) pour le filtrage des valeurs de nomenclature.
 *  La visibilité des champs est désormais pilotée par la config serveur (OcctaxFieldsConfig),
 *  plus par taxon : les anciens tests de visibilité ont donc été retirés. */
class ChampsTaxonTest {

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
