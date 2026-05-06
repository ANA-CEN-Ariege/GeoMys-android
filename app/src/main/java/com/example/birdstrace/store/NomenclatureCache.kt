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

    // Groupes botaniques connus dans INPN/TaxRef (même liste que l'app iOS)
    val GROUPES_BOTANIQUES: Set<String> = setOf(
        "Angiospermes", "Gymnospermes", "Ptéridophytes",
        "Mousses", "Hépatiques et anthocérotes",
        "Lichens", "Chlorophytes et charophytes", "Rhodophytes",
        "Trachéophytes", "Bryophytes", "Plantes"
    )

    // Intersection des groupes botaniques connus avec ceux présents dans le cache TaxRef.
    // Fallback sur tous les groupes botaniques si aucun n'est encore synchronisé.
    fun groupesBotaniquesConnus(): Set<String> {
        val connus = TaxRefCache.comptesGroupes.keys.toSet()
        val intersection = connus.intersect(GROUPES_BOTANIQUES)
        return if (intersection.isEmpty()) GROUPES_BOTANIQUES else intersection
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("nomenclature_cache", Context.MODE_PRIVATE)
    }

    fun setAll(data: Map<String, List<NomValeur>>) {
        prefs.edit().putString(KEY, gson.toJson(data)).apply()
        mem = data
    }

    fun get(type: String): List<NomValeur> = charger()[type] ?: emptyList()

    val estDisponible: Boolean get() = charger().isNotEmpty()

    // Filtre pour un ensemble de groupes.
    // Une valeur est incluse si :
    //   - son taxref est vide (universelle)
    //   - un entry a group2_inpn = "all" ou regno = "all"
    //   - un entry a group2_inpn correspondant à l'un des groupes demandés
    fun filtrerPourGroupes(type: String, groupes: Set<String>): List<NomValeur> {
        return get(type).filter { v ->
            when {
                v.taxref.isEmpty() -> true
                groupes.isEmpty()  -> true
                else -> v.taxref.any { r ->
                    r.group2Inpn.equals("all", ignoreCase = true) ||
                    r.regne.equals("all", ignoreCase = true) ||
                    groupes.any { g -> r.group2Inpn.equals(g, ignoreCase = true) }
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
