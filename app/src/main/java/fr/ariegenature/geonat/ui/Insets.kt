package fr.ariegenature.geonat.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

// Helpers edge-to-edge : appliquent les insets système (status bar, navigation bar,
// notch, IME) en padding sur des vues précises, en cumulant avec le padding défini
// en XML. Permet aux backgrounds (carte, drawables d'accueil) de rester edge-to-edge
// tout en gardant les boutons cliquables à l'écart des barres système.

private val typeSystemBars
    get() = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

private val typeSystemBarsAndIme
    get() = typeSystemBars or WindowInsetsCompat.Type.ime()

/** Padding-top = status bar + notch, cumulé avec le padding XML d'origine. */
fun View.applyStatusBarInset() {
    val basePaddingTop = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val insets = windowInsets.getInsets(typeSystemBars)
        v.updatePadding(top = basePaddingTop + insets.top)
        windowInsets
    }
}

/** Padding-bottom = navigation bar (+ IME si demandé), cumulé avec le padding XML d'origine. */
fun View.applyNavBarInset(includeIme: Boolean = false) {
    val basePaddingBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val types = if (includeIme) typeSystemBarsAndIme else typeSystemBars
        val insets = windowInsets.getInsets(types)
        v.updatePadding(bottom = basePaddingBottom + insets.bottom)
        windowInsets
    }
}

/** Padding top + bottom (formulaires ScrollView edge-to-edge). */
fun View.applySystemBarInsets(includeIme: Boolean = false) {
    val basePaddingTop = paddingTop
    val basePaddingBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val types = if (includeIme) typeSystemBarsAndIme else typeSystemBars
        val insets = windowInsets.getInsets(types)
        v.updatePadding(top = basePaddingTop + insets.top, bottom = basePaddingBottom + insets.bottom)
        windowInsets
    }
}

/** Marge top = status bar + notch (cumulée avec la marge XML). Utile pour boutons absolus
 *  qui ne peuvent pas absorber d'inset par padding sans déformer leur taille. */
fun View.applyStatusBarMargin() {
    val baseMarginTop = (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val insets = windowInsets.getInsets(typeSystemBars)
        val lp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        if (lp != null) {
            lp.topMargin = baseMarginTop + insets.top
            v.layoutParams = lp
        }
        windowInsets
    }
}

/** Marge bottom = nav bar (+ IME si demandé). */
fun View.applyNavBarMargin(includeIme: Boolean = false) {
    val baseMarginBottom = (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val types = if (includeIme) typeSystemBarsAndIme else typeSystemBars
        val insets = windowInsets.getInsets(types)
        val lp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        if (lp != null) {
            lp.bottomMargin = baseMarginBottom + insets.bottom
            v.layoutParams = lp
        }
        windowInsets
    }
}
