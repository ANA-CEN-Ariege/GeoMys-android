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

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.TaxRefCache

/** Couleurs canoniques des 9 taxons, partagées entre tous les écrans de saisie. */
fun taxonCouleur(context: Context, taxon: Taxon): Int = ContextCompat.getColor(context, when (taxon) {
    Taxon.OISEAU      -> R.color.orange
    Taxon.MAMMIFERE   -> R.color.brown
    Taxon.REPTILE     -> R.color.colorSecondary
    Taxon.BATRACIEN   -> R.color.blue_batracien
    Taxon.POISSON     -> R.color.blue_poisson
    Taxon.INSECTE     -> R.color.amber_insecte
    Taxon.FONGE       -> R.color.brown_fonge
    Taxon.MOLLUSQUE   -> R.color.purple_invertebres
    Taxon.INVERTEBRES -> R.color.purple_invertebres
    Taxon.PLANTE      -> R.color.teal
})

/** Drawable id de l'icône représentant un taxon (icônes utilisées dans les rangées
 *  de boutons, les markers de la carte, les résumés). */
fun taxonIcon(taxon: Taxon): Int = when (taxon) {
    Taxon.OISEAU      -> R.drawable.oiseaux
    Taxon.MAMMIFERE   -> R.drawable.mammiferes2
    Taxon.REPTILE     -> R.drawable.reptiles2
    Taxon.BATRACIEN   -> R.drawable.amphibiens
    Taxon.POISSON     -> R.drawable.poissons
    Taxon.INSECTE     -> R.drawable.insectes
    Taxon.FONGE       -> R.drawable.champignons2
    Taxon.MOLLUSQUE   -> R.drawable.mollusques
    Taxon.INVERTEBRES -> R.drawable.araignees
    Taxon.PLANTE      -> R.drawable.fleurs
}

/** Masque les boutons des groupes vides (aucun cd_nom chargé pour ce taxon) et
 *  retourne la sous-map des boutons restants — utilisée par les écrans de saisie
 *  pour ne proposer que les groupes effectivement présents dans la liste chargée.
 *  Si [idListeFiltre] est fourni, l'évaluation porte sur l'intersection avec cette
 *  liste UsersHub — utile quand le cache TaxRef est exhaustif mais que l'utilisateur
 *  ne saisit qu'une liste précise. */
fun filtrerBoutonsGroupesNonVides(
    buttons: Map<Taxon, MaterialButton>,
    idListeFiltre: Int? = null,
): Map<Taxon, MaterialButton> {
    val res = LinkedHashMap<Taxon, MaterialButton>()
    for ((t, btn) in buttons) {
        if (!TaxRefCache.indexParTaxon(t, idListeFiltre).isNullOrEmpty()) {
            btn.visibility = View.VISIBLE
            res[t] = btn
        } else {
            btn.visibility = View.GONE
        }
    }
    return res
}
