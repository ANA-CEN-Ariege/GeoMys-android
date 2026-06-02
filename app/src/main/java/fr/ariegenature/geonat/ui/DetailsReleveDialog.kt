/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.ariegenature.geonat.network.AdditionalFieldDef
import fr.ariegenature.geonat.ui.saisie.AdditionalFieldsRenderer

/** Dialog "Détails du relevé" partagé par la saisie multi-taxons ([SaisieObservationFragment])
 *  et mono-taxons ([SaisieRapideFragment]).
 *
 *  Affiche d'abord des lignes d'info en lecture seule ([infos] : jeu de données, observateur,
 *  position…), puis les champs additionnels niveau OCCTAX_RELEVE ([defs]) **éditables** (pas un
 *  simple affichage des valeurs). Sur "Valider", [onValider] reçoit les valeurs collectées.
 *
 *  Si [defs] est vide, le dialog se résume à l'en-tête et n'a qu'un bouton "Fermer". */
fun ouvrirDialogDetailsReleve(
    ctx: Context,
    infos: List<Pair<String, String>>,
    defs: List<AdditionalFieldDef>,
    valeursInitiales: Map<String, String>,
    onValider: (Map<String, String>) -> Unit,
) {
    val density = ctx.resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val racine = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    // En-tête : infos lecture seule du relevé.
    infos.forEach { (label, valeur) ->
        if (valeur.isBlank()) return@forEach
        racine.addView(TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(couleurSecondaire(ctx))
        })
        racine.addView(TextView(ctx).apply {
            text = valeur
            textSize = 15f
            setPadding(0, 0, 0, (8 * density).toInt())
        })
    }

    // Conteneur des champs additionnels éditables — peut rester vide si rien déclaré côté serveur.
    val containerAdd = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    if (defs.isNotEmpty()) {
        racine.addView(TextView(ctx).apply {
            text = "Champs additionnels"
            textSize = 12f
            setTextColor(couleurSecondaire(ctx))
            setPadding(0, (8 * density).toInt(), 0, 0)
        })
        AdditionalFieldsRenderer.rendre(containerAdd, defs, valeursInitiales)
        racine.addView(containerAdd)
    }

    val scroll = ScrollView(ctx).apply { addView(racine) }
    MaterialAlertDialogBuilder(ctx)
        .setTitle("Détails du relevé")
        .setView(scroll)
        .setPositiveButton(if (defs.isEmpty()) "Fermer" else "Valider") { _, _ ->
            if (defs.isNotEmpty()) onValider(AdditionalFieldsRenderer.collecter(containerAdd))
        }
        .apply { if (defs.isNotEmpty()) setNegativeButton("Annuler", null) }
        .show()
}
