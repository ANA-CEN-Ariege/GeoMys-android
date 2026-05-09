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
    @Volatile private var mem: Map<String, TaxRefEntry>? = null
    @Volatile private var memGroupes: Map<String, String>? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("taxref_cache", Context.MODE_PRIVATE)
    }

    fun get(nom: String): TaxRefEntry? = charger()[normaliser(nom)]

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

    fun getVernaculaireParCdNom(cdNom: Int): String? =
        charger().values.find { it.cdNom == cdNom }?.vernNom

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
        val json = gson.toJson(existing)
        prefs.edit().putString(KEY_GROUPES, json).apply()
        memGroupes = existing
    }

    fun tousLesGroupes(): Map<String, String> = chargerGroupes()

    private fun chargerGroupes(): Map<String, String> {
        memGroupes?.let { return it }
        val json = prefs.getString(KEY_GROUPES, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, String>()).also { memGroupes = it }
        } catch (e: Exception) { emptyMap() }
    }

    private const val KEY_GROUPES1 = "gn_taxref_groupes1_v1"
    @Volatile private var memGroupes1: Map<String, String>? = null

    private const val KEY_REGNES = "gn_taxref_regnes_v1"
    @Volatile private var memRegnes: Map<String, String>? = null

    fun ajouterGroupes1etRegnes(groupes1: Map<Int, String>, regnes: Map<Int, String>) {
        val existingG1 = chargerGroupes1().toMutableMap()
        groupes1.forEach { (k, v) -> if (v.isNotEmpty()) existingG1[k.toString()] = v }
        prefs.edit().putString(KEY_GROUPES1, gson.toJson(existingG1)).apply()
        memGroupes1 = existingG1

        val existingR = chargerRegnes().toMutableMap()
        regnes.forEach { (k, v) -> if (v.isNotEmpty()) existingR[k.toString()] = v }
        prefs.edit().putString(KEY_REGNES, gson.toJson(existingR)).apply()
        memRegnes = existingR
    }

    fun tousLesGroupes1(): Map<String, String> = chargerGroupes1()
    fun tousLesRegnes(): Map<String, String> = chargerRegnes()

    private fun chargerGroupes1(): Map<String, String> {
        memGroupes1?.let { return it }
        val json = prefs.getString(KEY_GROUPES1, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, String>()).also { memGroupes1 = it }
        } catch (e: Exception) { emptyMap() }
    }

    private fun chargerRegnes(): Map<String, String> {
        memRegnes?.let { return it }
        val json = prefs.getString(KEY_REGNES, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, String>()).also { memRegnes = it }
        } catch (e: Exception) { emptyMap() }
    }

    fun vider() {
        prefs.edit().remove(KEY).remove(KEY_VERSION).remove(KEY_COMPTES)
            .remove(KEY_GROUPES).remove(KEY_GROUPES1).remove(KEY_REGNES).apply()
        mem = null
        memGroupes = null
        memGroupes1 = null
        memRegnes = null
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
        mem?.let { return it }
        val json = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, TaxRefEntry>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, TaxRefEntry>()).also { mem = it }
        } catch (e: Exception) { emptyMap() }
    }

    private fun sauvegarder(cache: Map<String, TaxRefEntry>) {
        prefs.edit().putString(KEY, gson.toJson(cache)).apply()
        mem = cache
    }
}