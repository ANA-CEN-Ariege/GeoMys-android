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

package fr.ariegenature.geomys.ui

import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.network.MiseAJour
import kotlinx.coroutines.launch

/** Accès à la mise à jour intégrée depuis l'écran d'accueil — IMPLÉMENTATION DU FLAVOR GITHUB.
 *
 *  Tap sur le numéro de version → écran de MAJ ([MiseAJourFragment]), et polling des releases
 *  GitHub ([MiseAJour]) pour afficher une pastille « MAJ disponible ». Le flavor `play` fournit
 *  une implémentation VIDE de même signature ([src/play]) : tout le code de MAJ (cet objet,
 *  [MiseAJour], [MiseAJourFragment]) est ABSENT du binaire `.aab` du Play Store. */
object MiseAJourAccueil {

    /** True une fois qu'un check réseau a abouti (AJour/Disponible) : on cesse de re-vérifier.
     *  Réinitialisé à chaque nouvelle vue d'accueil par [configurer] pour re-tester. */
    private var verifiee = false

    /** Ancre de compilation : [MiseAJourFragment] n'est cité QUE par le nav_graph (chaîne
     *  `android:name`), sans aucune référence Kotlin entrante. Dans cet état, la compilation
     *  INCRÉMENTALE a déjà « oublié » la classe (absente du dex → ClassNotFoundException = crash
     *  au tap sur le numéro de version, vu en v1.2.x). Cette référence dure force la classe à
     *  toujours être compilée/dexée. (Le filet définitif reste le build `clean` en release.) */
    @Suppress("unused")
    private val ancreCompilation: Class<MiseAJourFragment> = MiseAJourFragment::class.java

    /** Tap sur le numéro de version → écran de mise à jour. */
    fun configurer(fragment: AccueilFragment, tvVersion: TextView) {
        verifiee = false
        tvVersion.setOnClickListener {
            fragment.findNavController().navigate(R.id.action_accueil_to_mise_a_jour)
        }
    }

    /** Vérifie s'il existe une release GitHub plus récente ; le cas échéant, pastille sur le
     *  numéro de version. Silencieux hors-ligne / en erreur, re-tenté au retour sur l'accueil. */
    fun reverifier(fragment: AccueilFragment) {
        if (verifiee) return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val r = MiseAJour.verifier(fragment.versionAffichee())
            if (!fragment.vueVivante()) return@launch
            // Erreur réseau (hors-ligne) → on laisse verifiee=false pour retenter plus tard.
            if (r is MiseAJour.Resultat.Erreur) return@launch
            verifiee = true
            if (r is MiseAJour.Resultat.Disponible) fragment.afficherPastilleMaj()
        }
    }
}
