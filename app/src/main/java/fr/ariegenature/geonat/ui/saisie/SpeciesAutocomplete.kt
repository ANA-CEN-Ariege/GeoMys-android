package fr.ariegenature.geonat.ui.saisie

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter
import fr.ariegenature.geonat.store.TaxRefCache

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
