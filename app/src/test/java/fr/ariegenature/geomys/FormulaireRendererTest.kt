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
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.monitoring.form.EditableField
import fr.ariegenature.geomys.monitoring.form.FormulaireRenderer
import fr.ariegenature.geomys.monitoring.form.ViewType
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ASSEMBLAGE du moteur de formulaire monitoring ([FormulaireRenderer]) — trou de couverture de
 * l'audit 2026-08-27 : rendu → lecture typée des valeurs (texte, décimal avec virgule, entier
 * strict), champs obligatoires manquants (mis à jour par la saisie dans la vue), règle `change`
 * appliquée via le flush synchrone, sans boucle ni écrasement d'un champ modifié par l'utilisateur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormulaireRendererTest {

    private lateinit var ctx: Context
    private lateinit var parent: LinearLayout
    private lateinit var renderer: FormulaireRenderer

    @Before
    fun setup() {
        ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_GeoMys)
        parent = LinearLayout(ctx)
        renderer = FormulaireRenderer(ctx, parent, MainScope())
    }

    /** Tous les EditText du formulaire, dans l'ordre de rendu. */
    private fun editTexts(v: View): List<EditText> = when (v) {
        is EditText -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { editTexts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun lecture_typee_des_valeurs_rendues() {
        renderer.rendre(listOf(
            EditableField("nom", ViewType.TEXT, "Nom", value = "Merle"),
            EditableField("taux", ViewType.NUMBER, "Taux", value = "12,5", decimal = true),
            EditableField("nb", ViewType.NUMBER, "Nombre", value = "7"),
            EditableField("vide", ViewType.NUMBER, "Vide"),
        ))
        val v = renderer.lireValeurs()
        assertEquals("Merle", v["nom"])
        assertEquals("virgule décimale acceptée", 12.5, v["taux"])
        assertEquals("entier reste entier", 7, v["nb"])
        assertNull(v["vide"])
    }

    @Test
    fun widget_entier_ne_renvoie_jamais_de_decimal() {
        renderer.rendre(listOf(EditableField("nb", ViewType.NUMBER, "Nombre", value = "3")))
        editTexts(parent).single().setText("4.7") // collé / posé par une règle
        renderer.flushChangementsEnAttente()
        assertEquals("tronqué en entier (audit lot C)", 4, renderer.lireValeurs()["nb"])
    }

    @Test
    fun champs_obligatoires_manquants_suivent_la_saisie() {
        renderer.rendre(listOf(
            EditableField("nom", ViewType.TEXT, "Nom", obligatoire = true),
            EditableField("nb", ViewType.NUMBER, "Nombre", obligatoire = true, value = "1"),
        ))
        val manquants = renderer.champsObligatoiresManquants()
        assertEquals(1, manquants.size)
        assertTrue("le champ vide est signalé : $manquants", manquants.single().contains("Nom", ignoreCase = true) || manquants.single() == "nom")
        editTexts(parent).first().setText("Grive")
        renderer.flushChangementsEnAttente()
        assertTrue(renderer.champsObligatoiresManquants().isEmpty())
    }

    @Test
    fun regle_change_appliquee_au_flush_sans_ecraser_un_champ_modifie_par_l_utilisateur() {
        renderer.rendre(listOf(
            EditableField("presence", ViewType.TEXT, "Présence", value = "Oui"),
            EditableField("count_min", ViewType.NUMBER, "Min", value = "5"),
            EditableField("count_max", ViewType.NUMBER, "Max", value = "9"),
        ))
        // Garde `!dirty` des scripts gn_module_monitoring : le patch ne s'applique que tant que
        // l'utilisateur n'a pas touché count_min lui-même.
        renderer.setReglesChange(listOf(
            "({objForm, meta}) => {",
            "if (objForm.value.presence === 'Non' && !objForm.controls.count_min.dirty) {",
            "objForm.patchValue({count_min: 0, count_max: 0})",
            "}",
            "}",
        ))
        val (presence, min, _) = editTexts(parent)
        presence.setText("Non")
        renderer.flushChangementsEnAttente()
        shadowOf(Looper.getMainLooper()).idle()
        val v = renderer.lireValeurs()
        assertEquals("patch appliqué (règle change)", 0, v["count_min"])
        assertEquals(0, v["count_max"])
        // L'utilisateur reprend la main sur count_min → dirty : un nouveau déclenchement de la
        // règle ne doit pas re-écraser sa valeur (garde !dirty), et ne doit pas boucler.
        min.setText("3")
        renderer.flushChangementsEnAttente()
        presence.setText("Non ")
        renderer.flushChangementsEnAttente()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("champ modifié par l'utilisateur conservé", 3, renderer.lireValeurs()["count_min"])
    }
}
