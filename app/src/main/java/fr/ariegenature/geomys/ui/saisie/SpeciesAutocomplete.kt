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
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.store.TaxRefCache

/** Adapter d'autocomplétion insensible aux accents et à la casse.
 *  Tri composite : les correspondances qui *commencent* par la requête en premier,
 *  puis celles qui la *contiennent*. Pré-normalise les suggestions une seule fois
 *  pour éviter O(n) appels à `normaliser()` à chaque frappe. */
/** Variante GÉNÉRIQUE : la liste déroulante porte des OBJETS, pas des chaînes — pour qu'une
 *  suggestion d'espèce transporte son `cd_nom` jusqu'à l'enregistrement (audit 2026-09-17, C2)
 *  au lieu d'être re-résolue depuis son texte. L'affichage et le texte recopié dans le champ
 *  viennent de `toString()` de l'objet. */
fun <T> createAutocompleteAdapter(
    context: Context,
    suggestions: List<T>,
    /** Paires (clé normalisée → élément) PRÉ-CALCULÉES, depuis un thread de fond. */
    normalized: List<Pair<String, T>>,
    /** Nom montré en ITALIQUE sous le nom principal — le nom scientifique sous un nom français
     *  (et le nom français en mode « noms scientifiques »). Null ⇒ ligne simple, comme avant. */
    secondaire: (T) -> String? = { null },
): ArrayAdapter<T> {
    return object : ArrayAdapter<T>(
        context,
        R.layout.item_suggestion_taxon,
        R.id.tv_suggestion_nom,
        suggestions.toMutableList()
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val vue = super.getView(position, convertView, parent)
            val tvSecondaire = vue.findViewById<TextView>(R.id.tv_suggestion_secondaire)
            val item = getItem(position)
            val sous = item?.let(secondaire)?.takeIf { it.isNotEmpty() && it != item.toString() }
            if (sous == null) {
                tvSecondaire.visibility = View.GONE
            } else {
                tvSecondaire.text = sous
                tvSecondaire.visibility = View.VISIBLE
            }
            return vue
        }
        override fun getFilter() = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val filtered: List<T> = if (constraint.isNullOrEmpty()) suggestions
                else {
                    val q = TaxRefCache.normaliser(constraint.toString())
                    val starts = ArrayList<T>()
                    val contains = ArrayList<T>()
                    for ((key, item) in normalized) {
                        when {
                            key.startsWith(q) -> starts.add(item)
                            key.contains(q)   -> contains.add(item)
                        }
                    }
                    starts + contains
                }
                return FilterResults().apply { values = filtered; count = filtered.size }
            }
            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                clear()
                if (results.count > 0) addAll(results.values as List<T>)
                notifyDataSetChanged()
            }
        }
    }
}

fun createSpeciesAutocompleteAdapter(
    context: Context,
    suggestions: List<String>,
    /** Paires (clé normalisée → affichage) PRÉ-CALCULÉES. À fournir depuis un thread de fond
     *  (Dispatchers.Default) — normaliser 15-50k noms sur le thread principal figeait l'UI à
     *  chaque changement de groupe taxon / bascule nom sci. Défaut = calcul sur place (compat). */
    normalized: List<Pair<String, String>> = suggestions.map { TaxRefCache.normaliser(it) to it },
): ArrayAdapter<String> {
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
