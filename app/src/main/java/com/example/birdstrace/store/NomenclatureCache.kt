package com.example.birdstrace.store

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class TaxrefRestriction(val regne: String, val group2Inpn: String)
data class NomValeur(val id: Int, val label: String, val taxref: List<TaxrefRestriction> = emptyList())

object NomenclatureCache {
    private const val KEY = "nom_cache_v1"
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private var mem: Map<String, List<NomValeur>>? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("nomenclature_cache", Context.MODE_PRIVATE)
    }

    fun setAll(data: Map<String, List<NomValeur>>) {
        prefs.edit().putString(KEY, gson.toJson(data)).apply()
        mem = data
    }

    fun get(type: String): List<NomValeur> = charger()[type] ?: emptyList()

    val estDisponible: Boolean get() = charger().isNotEmpty()

    fun filtrerPourGroupe(type: String, groupe2Inpn: String?): List<NomValeur> {
        return get(type).filter { v ->
            when {
                v.taxref.isEmpty() -> true
                groupe2Inpn == null -> true
                else -> v.taxref.any { r ->
                    r.group2Inpn.equals("all", ignoreCase = true) ||
                    r.group2Inpn.equals(groupe2Inpn, ignoreCase = true)
                }
            }
        }
    }

    fun resume(): String {
        val data = charger()
        if (data.isEmpty()) return ""
        return data.entries.joinToString(" | ") { (type, vals) ->
            val nbTaxref = vals.count { it.taxref.isNotEmpty() }
            "$type:${vals.size}val/${nbTaxref}taxref"
        }
    }

    val count: Int get() = charger().values.sumOf { it.size }

    fun vider() {
        prefs.edit().remove(KEY).apply()
        mem = null
    }

    private fun charger(): Map<String, List<NomValeur>> {
        mem?.let { return it }
        val json = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, List<NomValeur>>>() {}.type
            (gson.fromJson<Map<String, List<NomValeur>>>(json, type) ?: emptyMap()).also { mem = it }
        } catch (_: Exception) { emptyMap() }
    }
}
