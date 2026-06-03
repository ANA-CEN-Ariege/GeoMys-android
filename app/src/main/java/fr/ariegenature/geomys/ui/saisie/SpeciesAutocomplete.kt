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
import android.widget.ArrayAdapter
import android.widget.Filter
import fr.ariegenature.geomys.store.TaxRefCache

/** Adapter d'autocomplétion insensible aux accents et à la casse.
 *  Tri composite : les correspondances qui *commencent* par la requête en premier,
 *  puis celles qui la *contiennent*. Pré-normalise les suggestions une seule fois
 *  pour éviter O(n) appels à `normaliser()` à chaque frappe. */
fun createSpeciesAutocompleteAdapter(
    context: Context,
    suggestions: List<String>,
): ArrayAdapter<String> {
    val normalized: List<Pair<String, String>> = suggestions.map { TaxRefCache.normaliser(it) to it }
    return object : ArrayAdapter<String>(
        context,
        android.R.layout.simple_dropdown_item_1line,
        suggestions.toMutableList()
    ) {
        override fun getFilter() = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val filtered: List<String> = if (constraint.isNullOrEmpty()) suggestions
                else {
                    val q = TaxRefCache.normaliser(constraint.toString())
                    val starts = ArrayList<String>()
                    val contains = ArrayList<String>()
                    for ((key, display) in normalized) {
                        when {
                            key.startsWith(q) -> starts.add(display)
                            key.contains(q)   -> contains.add(display)
                        }
                    }
                    starts + contains
                }
                return FilterResults().apply { values = filtered; count = filtered.size }
            }
            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                clear()
                if (results.count > 0) addAll(results.values as List<String>)
                notifyDataSetChanged()
            }
        }
    }
}
