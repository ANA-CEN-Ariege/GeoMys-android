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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.network.TaxRefStatut
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.ui.saisie.TaxRefLookupController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CHANGER DE GROUPE TAXONOMIQUE PENDANT LA FRAPPE (demande terrain 2026-09-17).
 *
 * Le texte déjà saisi dans le champ espèce est CONSERVÉ et re-résolu dans le nouveau groupe — on
 * tape souvent le nom avant de s'apercevoir qu'on est dans le mauvais groupe, et tout effacer
 * obligeait à ressaisir.
 *
 * L'invariant délicat est l'inverse : le taxon résolu dans l'ANCIEN groupe ne doit pas survivre
 * une demi-seconde de plus (le temps du debounce), sans quoi « Démarrer » partirait avec lui.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangementDeGroupeTest {

    private class Owner : LifecycleOwner {
        val registre = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registre
    }

    @Test
    fun relancer_invalide_immediatement_le_taxon_de_l_ancien_groupe() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val owner = Owner().apply { registre.currentState = Lifecycle.State.RESUMED }
        var dernierStatut: TaxRefStatut? = null
        val controller = TaxRefLookupController(
            scope = owner.lifecycleScope,
            progress = ProgressBar(ctx),
            tvStatut = TextView(ctx),
            taxonProvider = { Taxon.INSECTE },
            configProvider = { GeoNatureConfig(ctx) },
            onChange = { s -> dernierStatut = s },
        )

        // L'utilisateur a choisi « Machaon » dans la liste du groupe Insectes.
        controller.poser(TaxRefStatut.Trouve(54468, "Papilio machaon", "Machaon"), pourTexte = "Machaon")
        assertTrue(controller.statut is TaxRefStatut.Trouve)

        // Il bascule sur un autre groupe sans toucher au texte : le taxon précédent doit tomber
        // TOUT DE SUITE, avant même que la nouvelle recherche (debouncée) n'aboutisse.
        controller.relancer("Machaon")
        assertNull("le taxon de l'ancien groupe ne doit pas survivre", controller.statut)
        assertNull("et l'écran doit en être informé (bouton Démarrer)", dernierStatut)
    }

    /** SITE D'APPEL : les deux écrans de saisie doivent conserver le texte et le re-résoudre. */
    @Test
    fun les_deux_ecrans_conservent_le_texte_au_changement_de_groupe() {
        val multi = File("src/main/java/fr/ariegenature/geomys/ui/SaisieObservationFragment.kt").readText()
        val bloc = multi.substringAfter("private fun onTaxonChanged()").substringBefore("\n    private fun")
        assertFalse("le texte ne doit plus être effacé", bloc.contains("setText(\"\")"))
        assertTrue("et doit être re-résolu dans le nouveau groupe", bloc.contains("taxrefLookup.relancer("))

        val rapide = File("src/main/java/fr/ariegenature/geomys/ui/SaisieRapideFragment.kt").readText()
        val blocRapide = rapide.substringAfter("PreferencesSaisie.memoiserTaxon(requireContext(), t)")
            .substringBefore("taxonSelector.init()")
        assertFalse("le texte ne doit plus être effacé", blocRapide.contains("setText(\"\")"))
        assertTrue("et doit être re-résolu dans le nouveau groupe", blocRapide.contains("taxrefLookup.relancer("))
    }

    /** Même règle pour la bascule « Noms scientifiques » : le texte survit et est re-résolu. */
    @Test
    fun le_switch_noms_scientifiques_conserve_aussi_le_texte() {
        listOf("SaisieObservationFragment.kt", "SaisieRapideFragment.kt").forEach { f ->
            val src = File("src/main/java/fr/ariegenature/geomys/ui/$f").readText()
            val bloc = src.substringAfter("memoiserNomSci(requireContext(), isChecked)")
                .substringBefore("        }")
            assertFalse("$f ne doit plus effacer le texte à la bascule", bloc.contains("setText("))
            assertTrue("$f doit re-résoudre le texte dans le nouveau mode",
                bloc.contains("taxrefLookup.relancer("))
        }
    }
}
