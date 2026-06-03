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
    private val onChange: (TaxRefStatut?) -> Unit = {},
) {
    var statut: TaxRefStatut? = null
        private set

    private var job: Job? = null

    /** Annule la recherche en cours et en lance une nouvelle après 500 ms si
     *  [nom] fait au moins 2 caractères ; sinon réinitialise. */
    fun rechercher(nom: String) {
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
            val (s, _) = TaxRefService.rechercher(nom, taxonProvider(), configProvider())
            statut = s
            progress.visibility = View.GONE
            updateUI()
            onChange(s)
        }
    }

    fun reset() {
        job?.cancel()
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
            TaxRefStatut.Indisponible -> {
                tvStatut.visibility = View.VISIBLE
                tvStatut.text = ctx.getString(R.string.taxref_indisponible)
                tvStatut.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
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
