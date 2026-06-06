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

import fr.ariegenature.geomys.store.OcctaxFieldsConfig
import fr.ariegenature.geomys.store.OcctaxFieldsConfig.Niveau
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pilotage de la visibilité des champs Occtax par la config serveur (settings.json). */
class OcctaxFieldsConfigTest {

    private fun codes(niveau: Niveau, json: String) =
        OcctaxFieldsConfig.champsVisibles(json, niveau).map { it.code }

    @Test
    fun json_vide_renvoie_tout_le_registre_du_niveau() {
        // Cas du serveur ANA actuel : pas de listes information/counting publiées.
        val info = codes(Niveau.INFORMATION, "")
        assertTrue("NATURALITE" in info)
        assertTrue("STATUT_OBS" in info)
        assertEquals(OcctaxFieldsConfig.REGISTRE.count { it.niveau == Niveau.INFORMATION }, info.size)
        val counting = codes(Niveau.COUNTING, "")
        assertEquals(setOf("SEXE", "STADE_VIE", "OBJ_DENBR", "TYP_DENBR"), counting.toSet())
    }

    @Test
    fun liste_information_agit_comme_whitelist_ordonnee() {
        val json = """{"information":["METH_OBS","NATURALITE"]}"""
        assertEquals(listOf("METH_OBS", "NATURALITE"), codes(Niveau.INFORMATION, json))
    }

    @Test
    fun entree_objet_visible_false_est_exclue() {
        val json = """{"information":[{"key":"METH_OBS","visible":true},{"key":"NATURALITE","visible":false}]}"""
        assertEquals(listOf("METH_OBS"), codes(Niveau.INFORMATION, json))
    }

    @Test
    fun objet_nomenclature_englobant_est_accepte() {
        // Le JSON caché peut être le settings complet contenant `nomenclature`.
        val json = """{"nomenclature":{"counting":["SEXE","STADE_VIE"]}}"""
        assertEquals(listOf("SEXE", "STADE_VIE"), codes(Niveau.COUNTING, json))
    }

    @Test
    fun cle_inconnue_du_registre_est_ignoree() {
        val json = """{"information":["METH_OBS","CHAMP_INEXISTANT"]}"""
        assertEquals(listOf("METH_OBS"), codes(Niveau.INFORMATION, json))
    }

    @Test
    fun json_illisible_retombe_sur_le_registre() {
        assertTrue("NATURALITE" in codes(Niveau.INFORMATION, "{pas du json"))
    }
}
