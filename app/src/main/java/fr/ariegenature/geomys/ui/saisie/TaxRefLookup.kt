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

package fr.ariegenature.geomys.ui.saisie

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.network.TaxRefService
import fr.ariegenature.geomys.network.TaxRefStatut
import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Recherche TaxRef debounced (500 ms) avec mise à jour automatique d'un TextView
 *  de statut et d'une ProgressBar. Le caller passe la fonction qui retourne le
 *  [Taxon] et la [GeoNatureConfig] courants — ils peuvent changer entre deux frappes. */
class TaxRefLookupController(
    private val scope: LifecycleCoroutineScope,
    private val progress: ProgressBar,
    private val tvStatut: TextView,
    private val taxonProvider: () -> Taxon,
    private val configProvider: () -> GeoNatureConfig,
    /** Mode d'affichage courant de l'autocomplétion (noms scientifiques ou français) : il définit
     *  l'ensemble des noms proposés, donc celui des noms acceptables. */
    private val scientifiqueProvider: () -> Boolean = { false },
    private val onChange: (TaxRefStatut?) -> Unit = {},
) {
    var statut: TaxRefStatut? = null
        private set

    private var job: Job? = null

    /** Texte pour lequel le taxon a été CHOISI dans la liste de propositions : tant qu'il est
     *  affiché tel quel, aucune recherche ne vient le réécrire (cf. [poser]). */
    private var nomFige: String? = null

    /** Aucune proposition disponible (liste de taxons absente du cache) : le message reste
     *  affiché, la frappe et les remises à zéro ne l'effacent pas. */
    private var pasDeDonnees = false

    /** Signale l'absence de données de saisie (ou la lève avec [actif] à false). */
    fun signalerPasDeDonnees(actif: Boolean) {
        if (pasDeDonnees == actif) return
        pasDeDonnees = actif
        job?.cancel()
        statut = if (actif) TaxRefStatut.PasDeDonnees else null
        updateUI()
        onChange(statut)
    }

    /**
     * Fixe le taxon CHOISI dans la liste de propositions, sans le re-chercher (audit 2026-09-17,
     * C2) : la suggestion connaît son `cd_nom`, le retrouver à partir de son seul texte pouvait
     * désigner un autre taxon du même groupe et de la même liste portant ce nom.
     * La recherche déclenchée par la frappe est annulée, et ignorée tant que le texte ne change
     * pas — c'est la même liste qui a fourni le nom et le taxon.
     */
    fun poser(trouve: TaxRefStatut.Trouve, pourTexte: String) {
        job?.cancel()
        nomFige = pourTexte
        statut = trouve
        updateUI()
        onChange(trouve)
    }

    /**
     * Relance la résolution du texte affiché dans un périmètre qui vient de CHANGER (groupe
     * taxonomique choisi pendant la frappe).
     *
     * Le statut courant est invalidé IMMÉDIATEMENT — pas seulement à la fin du debounce : il
     * désigne un taxon de l'ancien groupe, et le laisser en place laisserait démarrer une saisie
     * avec ce taxon pendant la demi-seconde suivante. Un choix figé dans la liste ([poser]) est
     * levé pour la même raison.
     */
    fun relancer(nom: String) {
        nomFige = null
        statut = null
        updateUI()
        onChange(null)
        rechercher(nom)
    }

    /** Annule la recherche en cours et en lance une nouvelle après 500 ms si
     *  [nom] fait au moins 2 caractères ; sinon réinitialise. */
    fun rechercher(nom: String) {
        if (pasDeDonnees) { updateUI(); return }
        // Texte inchangé depuis un choix dans la liste : le taxon est déjà connu.
        if (nom == nomFige && statut is TaxRefStatut.Trouve) { updateUI(); return }
        nomFige = null
        job?.cancel()
        if (nom.length < 2) {
            statut = null
            updateUI()
            onChange(null)
            return
        }
        job = scope.launch {
            delay(500)
            if (!isActive) return@launch
            progress.visibility = View.VISIBLE
            tvStatut.visibility = View.GONE
            val s = TaxRefService.rechercher(
                nom, taxonProvider(), configProvider(), scientifiqueProvider(),
            )
            statut = s
            progress.visibility = View.GONE
            updateUI()
            onChange(s)
        }
    }

    fun reset() {
        job?.cancel()
        nomFige = null
        if (pasDeDonnees) { updateUI(); return }
        statut = null
        updateUI()
    }

    private fun updateUI() {
        val ctx = tvStatut.context
        when (val s = statut) {
            is TaxRefStatut.Trouve -> {
                tvStatut.visibility = View.VISIBLE
                tvStatut.text = "✓ ${s.nomScientifique}  •  cd_nom ${s.cdNom}"
                tvStatut.setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_green_dark))
            }
            TaxRefStatut.NonTrouve -> {
                tvStatut.visibility = View.VISIBLE
                tvStatut.text = ctx.getString(R.string.taxref_non_trouve)
                tvStatut.setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_orange_dark))
            }
            TaxRefStatut.PasDeDonnees -> {
                tvStatut.visibility = View.VISIBLE
                tvStatut.text = ctx.getString(R.string.taxref_pas_de_donnees)
                tvStatut.setTextColor(fr.ariegenature.geomys.ui.couleurAvertissement())
            }
            null -> {
                tvStatut.visibility = View.GONE
                progress.visibility = View.GONE
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
