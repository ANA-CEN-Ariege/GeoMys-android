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

import fr.ariegenature.geomys.network.AdditionalFieldDef
import fr.ariegenature.geomys.network.AdditionalFieldsObject
import fr.ariegenature.geomys.network.WidgetType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique qui décide si un champ additionnel OccTax s'affiche : niveau d'objet (appliqueA)
 *  + restrictions dataset / liste taxonomique (visiblePour). C'est ce qui déclenche (ou non)
 *  l'écran intercalaire « Détails du relevé » en saisie multi-taxons. */
class AdditionalFieldVisibiliteTest {

    private fun champ(
        objectsCode: List<String> = listOf(AdditionalFieldsObject.RELEVE),
        datasetsIds: List<Int> = emptyList(),
        idList: Int? = null,
    ) = AdditionalFieldDef(
        idField = 1, fieldName = "test", fieldLabel = "Test",
        widget = WidgetType.NUMBER, objectsCode = objectsCode,
        datasetsIds = datasetsIds, idList = idList,
    )

    // ── appliqueA (niveau d'objet) ──────────────────────────────────────────────
    @Test
    fun appliqueA_vrai_si_objet_present() {
        assertTrue(champ().appliqueA(AdditionalFieldsObject.RELEVE))
    }

    @Test
    fun appliqueA_faux_pour_un_autre_niveau() {
        val f = champ(objectsCode = listOf(AdditionalFieldsObject.OCCURRENCE))
        assertFalse(f.appliqueA(AdditionalFieldsObject.RELEVE))
        assertTrue(f.appliqueA(AdditionalFieldsObject.OCCURRENCE))
    }

    // ── visiblePour : restriction dataset ──────────────────────────────────────
    @Test
    fun sans_restriction_dataset_visible_partout() {
        assertTrue(champ(datasetsIds = emptyList()).visiblePour(42, emptyList()))
        assertTrue(champ(datasetsIds = emptyList()).visiblePour(null, emptyList()))
    }

    @Test
    fun restreint_a_un_dataset() {
        val f = champ(datasetsIds = listOf(10, 20))
        assertTrue(f.visiblePour(10, emptyList()))
        assertFalse("dataset hors liste", f.visiblePour(99, emptyList()))
        assertFalse("dataset courant inconnu mais champ restreint", f.visiblePour(null, emptyList()))
    }

    // ── visiblePour : restriction liste taxonomique ─────────────────────────────
    @Test
    fun sans_idList_visible_pour_tous_les_taxons() {
        assertTrue(champ(idList = null).visiblePour(1, emptyList()))
    }

    @Test
    fun restreint_a_une_liste_taxonomique() {
        val f = champ(idList = 100)
        assertTrue("taxon dans la liste", f.visiblePour(1, listOf(100, 200)))
        assertFalse("taxon hors liste", f.visiblePour(1, listOf(200, 300)))
        assertFalse("aucune liste pour le taxon", f.visiblePour(1, emptyList()))
    }

    @Test
    fun combinaison_dataset_et_liste() {
        val f = champ(datasetsIds = listOf(10), idList = 100)
        assertTrue(f.visiblePour(10, listOf(100)))
        assertFalse("bon dataset mais mauvaise liste", f.visiblePour(10, listOf(999)))
        assertFalse("bonne liste mais mauvais dataset", f.visiblePour(11, listOf(100)))
    }
}
