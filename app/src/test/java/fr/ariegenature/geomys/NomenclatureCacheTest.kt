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
import fr.ariegenature.geomys.store.NomValeur
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.TaxrefRestriction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Filtrage des valeurs de nomenclature par groupe taxonomique / regno (alimente les spinners
 *  de caractérisation/dénombrement) + valeurs par défaut serveur. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NomenclatureCacheTest {

    @Before
    fun setup() {
        NomenclatureCache.init(ApplicationProvider.getApplicationContext())
        NomenclatureCache.vider()
        NomenclatureCache.setDefauts(emptyMap())
    }

    private fun labels(groupes: Set<String>, regno: String) =
        NomenclatureCache.filtrerPourGroupes("STATUT_BIO", groupes, regno).map { it.label }.toSet()

    @Test
    fun valeur_sans_restriction_taxref_toujours_incluse() {
        NomenclatureCache.setAll(mapOf("STATUT_BIO" to listOf(NomValeur(1, "Universel"))))
        assertEquals(setOf("Universel"), labels(setOf("Oiseaux"), "Animalia"))
    }

    @Test
    fun groupes_vides_renvoie_tout() {
        NomenclatureCache.setAll(mapOf("STATUT_BIO" to listOf(
            NomValeur(1, "A", listOf(TaxrefRestriction("Animalia", "Oiseaux"))),
        )))
        assertEquals(setOf("A"), labels(emptySet(), ""))
    }

    @Test
    fun joker_regne_all_universel() {
        NomenclatureCache.setAll(mapOf("STATUT_BIO" to listOf(
            NomValeur(1, "Joker", listOf(TaxrefRestriction("all", "Oiseaux"))),
        )))
        assertEquals(setOf("Joker"), labels(setOf("Mammifères"), "Animalia"))
    }

    @Test
    fun regne_plus_group2_all_couvre_tout_le_royaume() {
        NomenclatureCache.setAll(mapOf("STATUT_BIO" to listOf(
            NomValeur(1, "Anim", listOf(TaxrefRestriction("Animalia", "all"))),
        )))
        assertTrue("Anim" in labels(setOf("Oiseaux"), "Animalia"))
        assertFalse("autre regne exclu", "Anim" in labels(setOf("Angiospermes"), "Plantae"))
    }

    @Test
    fun groupe_exact_insensible_a_la_casse() {
        NomenclatureCache.setAll(mapOf("STATUT_BIO" to listOf(
            NomValeur(1, "Oiseau", listOf(TaxrefRestriction("Animalia", "Oiseaux"))),
            NomValeur(2, "Plante", listOf(TaxrefRestriction("Plantae", "Angiospermes"))),
        )))
        assertEquals(setOf("Oiseau"), labels(setOf("oiseaux"), "Animalia"))
    }

    @Test
    fun defauts_roundtrip() {
        NomenclatureCache.setDefauts(mapOf("STATUT_OBS" to "3"))
        assertEquals("3", NomenclatureCache.defautPour("STATUT_OBS"))
        assertNull(NomenclatureCache.defautPour("INEXISTANT"))
        assertEquals(mapOf("STATUT_OBS" to "3"), NomenclatureCache.tousLesDefauts())
    }

    @Test
    fun est_disponible_reflete_le_cache() {
        assertFalse(NomenclatureCache.estDisponible)
        NomenclatureCache.setAll(mapOf("X" to listOf(NomValeur(1, "v"))))
        assertTrue(NomenclatureCache.estDisponible)
    }
}
