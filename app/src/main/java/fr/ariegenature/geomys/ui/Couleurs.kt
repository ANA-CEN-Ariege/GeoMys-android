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

import android.content.Context
import com.google.android.material.color.MaterialColors

/** Masque le clavier logiciel et retire le focus du champ — appelé après une sélection dans un
 *  menu déroulant (Paramètres, Détails du relevé…) pour que le clavier ne reste pas affiché. */
fun masquerClavier(champ: android.view.View) {
    (champ.context.getSystemService(Context.INPUT_METHOD_SERVICE)
        as? android.view.inputmethod.InputMethodManager)
        ?.hideSoftInputFromWindow(champ.windowToken, 0)
    champ.clearFocus()
}

// Helpers de résolution des couleurs thématiques Material. Remplacent les valeurs en dur
// (#666666, #888888, #C62828…) qui devenaient illisibles sur le fond accueil sombre forcé
// par v0.9.65. Tous prennent un fallback légacy au cas où le thème n'expose pas l'attribut
// (pas attendu en pratique avec Theme.Material3.DayNight).

/** Texte secondaire (labels, sous-titres, hints, "Choisir…", aide…). Avant : #666666 ou
 *  #888888 codés en dur. Après : ?attr/colorOnSurfaceVariant Material, lisible sur fond
 *  clair comme sur le dégradé accueil. */
fun couleurSecondaire(ctx: Context): Int = MaterialColors.getColor(
    ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt(),
)

/** Texte principal sur surface (valeur saisie, libellé d'objet…). */
fun couleurSurOnSurface(ctx: Context): Int = MaterialColors.getColor(
    ctx, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt(),
)

/** Couleur d'erreur (validation, refus, suppression…). Avant : #C62828 ou
 *  @android:color/holo_red_dark. */
fun couleurErreur(ctx: Context): Int = MaterialColors.getColor(
    ctx, com.google.android.material.R.attr.colorError, 0xFFB00020.toInt(),
)

/** Cadre coloré arrondi sur fond transparent — le marqueur d'état standard des listes
 *  (rouge = erreur/à envoyer, vert = envoyé, ambre = en cours). Remplace les fonds pastel
 *  (illisibles sur le thème sombre) ; centralisé pour que rayon/épaisseur restent uniformes
 *  entre les écrans (Mes visites, Mes saisies, drill-down protocole). */
fun cadreColore(couleur: Int, density: Float): android.graphics.drawable.GradientDrawable =
    android.graphics.drawable.GradientDrawable().apply {
        setColor(0x00000000)
        cornerRadius = 8 * density
        setStroke((2 * density).toInt(), couleur)
    }
