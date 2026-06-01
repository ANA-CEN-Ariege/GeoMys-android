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

package fr.ariegenature.geonat.ui.saisie

import android.content.Context
import fr.ariegenature.geonat.model.Taxon

/** Persistance légère des choix utilisateur sur les écrans de saisie d'observations
 *  (multi-taxons & mono-taxon) : dernier groupe sélectionné et état du switch "noms
 *  scientifiques". On ne mémorise que des valeurs UI — pas de donnée naturaliste. */
object PreferencesSaisie {
    private const val PREFS = "GeoNat_prefs"
    private const val KEY_DERNIER_TAXON = "saisie_dernier_taxon"
    private const val KEY_NOM_SCI = "saisie_nom_sci"

    fun dernierTaxon(context: Context): Taxon? {
        val nom = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DERNIER_TAXON, null) ?: return null
        return runCatching { Taxon.valueOf(nom) }.getOrNull()
    }

    fun memoiserTaxon(context: Context, taxon: Taxon) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DERNIER_TAXON, taxon.name).apply()
    }

    fun rechercheNomSci(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOM_SCI, false)

    fun memoiserNomSci(context: Context, actif: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOM_SCI, actif).apply()
    }
}
