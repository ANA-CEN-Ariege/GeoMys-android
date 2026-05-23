package fr.ariegenature.geonat.store

import android.content.Context
import fr.ariegenature.geonat.model.Sortie
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SortieStore(context: Context) {
    private val prefs = context.getSharedPreferences("sorties_store", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "sorties_sauvegardees"

    fun charger(): MutableList<Sortie> {
        val json = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Sortie>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun sauvegarder(sorties: List<Sortie>) {
        prefs.edit().putString(key, gson.toJson(sorties)).apply()
    }

    fun ajouter(sortie: Sortie) {
        val sorties = charger()
        sorties.add(0, sortie)
        sauvegarder(sorties)
    }

    /** Remplace la sortie [id] par [sortieMaj] en préservant sa position dans la liste. Si
     *  l'id n'existe pas, ajoute en tête (= comportement [ajouter]). Utilisé pour la reprise
     *  d'une sortie depuis l'onglet "À envoyer". */
    fun remplacer(id: String, sortieMaj: Sortie) {
        val sorties = charger()
        val idx = sorties.indexOfFirst { it.id == id }
        if (idx >= 0) {
            sorties[idx] = sortieMaj
            sauvegarder(sorties)
        } else {
            sorties.add(0, sortieMaj)
            sauvegarder(sorties)
        }
    }

    fun supprimer(id: String) {
        val sorties = charger()
        sorties.removeAll { it.id == id }
        sauvegarder(sorties)
    }

    fun marquerEnvoyee(id: String) {
        val sorties = charger()
        val idx = sorties.indexOfFirst { it.id == id }
        if (idx >= 0) {
            sorties[idx] = sorties[idx].copy(envoyeGeoNature = true)
            sauvegarder(sorties)
        }
    }
}