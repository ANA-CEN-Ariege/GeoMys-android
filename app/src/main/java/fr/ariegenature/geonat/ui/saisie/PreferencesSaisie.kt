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
