package fr.ariegenature.geonat.ui.saisie

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
import fr.ariegenature.geonat.model.Taxon
import com.google.android.material.button.MaterialButton

/** Rangée de boutons-icônes pour sélectionner un taxon, avec mise en surbrillance
 *  du taxon sélectionné. Le caller mappe chaque [MaterialButton] de son layout au
 *  [Taxon] correspondant via [buttons]. */
class TaxonSelector(
    private val context: Context,
    private val buttons: Map<Taxon, MaterialButton>,
    initial: Taxon = Taxon.OISEAU,
    private val onChanged: (Taxon) -> Unit,
) {
    var taxon: Taxon = initial
        private set

    fun init() {
        buttons.forEach { (t, btn) ->
            btn.setOnClickListener {
                if (taxon != t) {
                    taxon = t
                    updateUI()
                    onChanged(t)
                }
            }
        }
        updateUI()
    }

    /** Force le taxon courant sans déclencher [onChanged] — utile pour restaurer
     *  un état hérité (ex : réédition d'une obs existante). */
    fun setTaxon(t: Taxon) {
        if (taxon == t) return
        taxon = t
        updateUI()
    }

    private fun updateUI() {
        val gray = ContextCompat.getColorStateList(context, android.R.color.darker_gray)
        val white = ColorStateList.valueOf(Color.WHITE)
        val transparent = ColorStateList.valueOf(Color.TRANSPARENT)
        buttons.values.forEach { btn ->
            btn.backgroundTintList = transparent
            btn.setTextColor(gray)
            btn.iconTint = gray
        }
        buttons[taxon]?.let { btn ->
            val color = taxonCouleur(context, taxon)
            btn.backgroundTintList = ColorStateList.valueOf(color)
            btn.setTextColor(Color.WHITE)
            btn.iconTint = white
        }
    }
}
