package com.example.birdstrace.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class TaxRefEntry(val cdNom: Int, val sciNom: String, val vernNom: String? = null)

object TaxRefCache {
    private const val KEY = "gn_taxref_cache_v2"
    private const val KEY_VERSION = "gn_taxref_version_cache"
    private lateinit var prefs: android.content.SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("taxref_cache", Context.MODE_PRIVATE)
    }

    fun get(nom: String): TaxRefEntry? {
        val cache = charger()
        return cache[normaliser(nom)]
    }

    fun set(nom: String, cdNom: Int, sciNom: String, vernNom: String? = null) {
        val cache = charger().toMutableMap()
        cache[normaliser(nom)] = TaxRefEntry(cdNom, sciNom, vernNom)
        sauvegarder(cache)
    }

    fun ajouter(entries: Map<String, TaxRefEntry>) {
        val cache = charger().toMutableMap()
        cache.putAll(entries)
        sauvegarder(cache)
    }

    val count: Int get() = charger().size

    fun toutesLesEntrees(): Map<String, TaxRefEntry> = charger()

    fun tousLesCdNoms(): Set<Int> = charger().values.map { it.cdNom }.toSet()

    fun getVernaculaireParCdNom(cdNom: Int): String? {
        return charger().values.find { it.cdNom == cdNom }?.vernNom
    }

    private const val KEY_COMPTES = "gn_taxref_comptes_v1"

    var comptesGroupes: Map<String, Int>
        get() {
            val json = prefs.getString(KEY_COMPTES, null) ?: return emptyMap()
            return try {
                val type = object : TypeToken<Map<String, Int>>() {}.type
                gson.fromJson(json, type) ?: emptyMap()
            } catch (e: Exception) { emptyMap() }
        }
        set(v) = prefs.edit().putString(KEY_COMPTES, gson.toJson(v)).apply()

    private const val KEY_GROUPES = "gn_taxref_groupes_v1"

    fun ajouterGroupes(groupes: Map<Int, String>) {
        val existing = chargerGroupes().toMutableMap()
        groupes.forEach { (k, v) -> existing[k.toString()] = v }
        prefs.edit().putString(KEY_GROUPES, gson.toJson(existing)).apply()
    }

    fun tousLesGroupes(): Map<String, String> = chargerGroupes()

    private fun chargerGroupes(): Map<String, String> {
        val json = prefs.getString(KEY_GROUPES, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
    }

    fun vider() {
        prefs.edit().remove(KEY).remove(KEY_VERSION).remove(KEY_COMPTES).remove(KEY_GROUPES).apply()
    }

    var versionSauvegardee: String?
        get() = prefs.getString(KEY_VERSION, null)
        set(v) = prefs.edit().putString(KEY_VERSION, v).apply()

    fun normaliser(nom: String): String =
        nom.trim().lowercase()
            .map { c ->
                when (c) {
                    'à', 'â', 'ä' -> 'a'
                    'é', 'è', 'ê', 'ë' -> 'e'
                    'î', 'ï' -> 'i'
                    'ô', 'ö' -> 'o'
                    'ù', 'û', 'ü' -> 'u'
                    'ç' -> 'c'
                    else -> c
                }
            }.joinToString("")

    private fun charger(): Map<String, TaxRefEntry> {
        val json = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, TaxRefEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun sauvegarder(cache: Map<String, TaxRefEntry>) {
        prefs.edit().putString(KEY, gson.toJson(cache)).apply()
    }
}