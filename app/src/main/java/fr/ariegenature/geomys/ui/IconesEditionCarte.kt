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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import fr.ariegenature.geomys.R

/**
 * Icônes de l'édition de géométrie OccHab. Fabriques PURES — elles ne lisent ni ne modifient
 * l'état de la saisie — extraites d'`OccHabCarteFragment` (audit OccHab 2026-10-18, O1).
 */
object IconesEditionCarte {

    /** Petit disque plein à liseré blanc, pour matérialiser un sommet. */
    fun cercleSommet(contexte: Context, couleur: Int): Drawable {
        val densite = contexte.resources.displayMetrics.density
        val d = (12 * densite).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(couleur)
            setStroke((2 * densite).toInt(), Color.WHITE)
            setSize(d, d)
        }
    }

    /**
     * Poignée « + » d'ajout de sommet : VISUEL de 18 dp (réglé terrain 2026-08-27 : 26 → 9 → 18)
     * dessiné au centre d'une image transparente de 24 dp, qui conserve une zone de tap
     * raisonnable.
     *
     * ⚠ osmdroid dimensionne un Marker sur la taille INTRINSÈQUE de son icône : l'ancien
     * LayerDrawable héritait des 24 dp d'`ic_add` et `setSize`/`setBounds` étaient ignorés — les
     * « réductions » à 5 dp n'avaient aucun effet visible (rendu réel ≈ 26 dp). On RASTÉRISE donc
     * dans un bitmap. L'appelant garde UNE instance partagée par toutes les poignées (osmdroid
     * pose les bounds avant chaque draw).
     */
    fun poigneeAjout(contexte: Context): Drawable {
        val densite = contexte.resources.displayMetrics.density
        val zone = (24 * densite).toInt()
        val visuel = (18 * densite).toInt()
        val bmp = Bitmap.createBitmap(zone, zone, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val debut = (zone - visuel) / 2
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1976D2.toInt())
            setStroke(maxOf(1, (1 * densite).toInt()), Color.WHITE)
            setBounds(debut, debut, debut + visuel, debut + visuel)
        }.draw(canvas)
        val marge = (1.5f * densite).toInt()
        ContextCompat.getDrawable(contexte, R.drawable.ic_add)!!.mutate().apply {
            setTint(Color.WHITE)
            setBounds(debut + marge, debut + marge, debut + visuel - marge, debut + visuel - marge)
        }.draw(canvas)
        return BitmapDrawable(contexte.resources, bmp)
    }
}
