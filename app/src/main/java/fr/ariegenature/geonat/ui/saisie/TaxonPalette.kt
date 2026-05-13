package fr.ariegenature.geonat.ui.saisie

import android.content.Context
import androidx.core.content.ContextCompat
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.model.Taxon

/** Couleurs canoniques des 9 taxons, partagées entre tous les écrans de saisie. */
fun taxonCouleur(context: Context, taxon: Taxon): Int = ContextCompat.getColor(context, when (taxon) {
    Taxon.OISEAU      -> R.color.orange
    Taxon.MAMMIFERE   -> R.color.brown
    Taxon.REPTILE     -> R.color.colorSecondary
    Taxon.BATRACIEN   -> R.color.blue_batracien
    Taxon.POISSON     -> R.color.blue_poisson
    Taxon.INSECTE     -> R.color.amber_insecte
    Taxon.FONGE       -> R.color.brown_fonge
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
    Taxon.INVERTEBRES -> R.drawable.mollusques
    Taxon.PLANTE      -> R.drawable.fleurs
}
