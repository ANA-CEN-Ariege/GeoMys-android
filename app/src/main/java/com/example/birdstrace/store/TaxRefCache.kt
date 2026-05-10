package com.example.birdstrace.store

import android.content.Context
import com.example.birdstrace.model.Taxon
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

data class TaxRefEntry(
    val cdNom: Int,
    val sciNom: String,
    val vernNoms: List<String> = emptyList()
) {
    val premierVern: String? get() = vernNoms.firstOrNull { it.isNotEmpty() }
}

// Désérialise les anciens caches au format { cdNom, sciNom, vernNom: String? } vers le nouveau
// format vernNoms: List<String>. Évite une perte de cache à la mise à jour de l'app.
private class TaxRefEntryDeserializer : JsonDeserializer<TaxRefEntry> {
    override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): TaxRefEntry {
        val obj = json.asJsonObject
        val cdNom = obj.get("cdNom").asInt
        val sciNom = obj.get("sciNom").asString
        val vernNoms: List<String> = when {
            obj.has("vernNoms") && obj.get("vernNoms").isJsonArray ->
                obj.getAsJsonArray("vernNoms").mapNotNull { it.takeIf { !it.isJsonNull }?.asString }
            obj.has("vernNom") && !obj.get("vernNom").isJsonNull ->
                listOf(obj.get("vernNom").asString)
            else -> emptyList()
        }
        return TaxRefEntry(cdNom, sciNom, vernNoms.filter { it.isNotEmpty() })
    }
}

object TaxRefCache {
    private const val KEY = "gn_taxref_cache_v2"
    private const val KEY_VERSION = "gn_taxref_version_cache"
    private const val KEY_COMPTES = "gn_taxref_comptes_v1"
    private const val KEY_GROUPES = "gn_taxref_groupes_v1"
    private const val KEY_GROUPES1 = "gn_taxref_groupes1_v1"
    private const val KEY_REGNES = "gn_taxref_regnes_v1"
    private const val KEY_INDEX_TAXON = "gn_taxref_index_taxon_v1"

    private lateinit var prefs: android.content.SharedPreferences
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(TaxRefEntry::class.java, TaxRefEntryDeserializer())
        .create()

    @Volatile private var mem: Map<String, TaxRefEntry>? = null
    @Volatile private var memGroupes: Map<String, String>? = null
    @Volatile private var memGroupes1: Map<String, String>? = null
    @Volatile private var memRegnes: Map<String, String>? = null
    @Volatile private var memIndexTaxon: Map<String, List<Int>>? = null
    @Volatile private var memEntreesParCdNom: Map<Int, TaxRefEntry>? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("taxref_cache", Context.MODE_PRIVATE)
    }

    // Suffixe d'article ajouté par l'INPN aux noms vernaculaires : "Triton palmé (Le)".
    // Couvre apostrophe ASCII ' et typographique ’, casse insensible.
    private val regexSuffixeArticle =
        Regex("""\s*\(\s*(?:[Ll]es?|[Ll]a|[Ll][''’]|[UuDd]ne?|[Dd]es)\s*\)\s*$""")
    fun nettoyerSuffixeArticle(nom: String): String = nom.replace(regexSuffixeArticle, "").trim()

    fun get(nom: String): TaxRefEntry? {
        val cache = charger()
        return cache[normaliser(nom)] ?: cache[normaliser(nettoyerSuffixeArticle(nom))]
    }

    fun set(nom: String, cdNom: Int, sciNom: String, vernNom: String? = null) {
        val cache = charger().toMutableMap()
        val noms = listOfNotNull(vernNom?.takeIf { it.isNotEmpty() })
        cache[normaliser(nom)] = TaxRefEntry(cdNom, sciNom, noms)
        sauvegarder(cache)
    }

    fun ajouter(entries: Map<String, TaxRefEntry>) {
        val cache = charger().toMutableMap()
        cache.putAll(entries)
        sauvegarder(cache)
    }

    val count: Int get() = charger().size

    fun toutesLesEntrees(): Map<String, TaxRefEntry> = charger()

    fun tousLesCdNoms(): Set<Int> = entreesParCdNom().keys

    /** Map cdNom → entry agrégée (un seul TaxRefEntry par cdNom, fusionnant les vernNoms). */
    fun entreesParCdNom(): Map<Int, TaxRefEntry> {
        memEntreesParCdNom?.let { return it }
        val source = charger().values
        val parCdNom = HashMap<Int, TaxRefEntry>(source.size)
        for (e in source) {
            val existing = parCdNom[e.cdNom]
            parCdNom[e.cdNom] = if (existing == null) e
            else existing.copy(vernNoms = (existing.vernNoms + e.vernNoms).distinct())
        }
        return parCdNom.also { memEntreesParCdNom = it }
    }

    fun getVernaculaireParCdNom(cdNom: Int): String? = entreesParCdNom()[cdNom]?.premierVern

    var comptesGroupes: Map<String, Int>
        get() {
            val json = prefs.getString(KEY_COMPTES, null) ?: return emptyMap()
            return try {
                val type = object : TypeToken<Map<String, Int>>() {}.type
                gson.fromJson(json, type) ?: emptyMap()
            } catch (e: Exception) { emptyMap() }
        }
        set(v) = prefs.edit().putString(KEY_COMPTES, gson.toJson(v)).apply()

    fun ajouterGroupes(groupes: Map<Int, String>) {
        val existing = chargerGroupes().toMutableMap()
        groupes.forEach { (k, v) -> existing[k.toString()] = v }
        prefs.edit().putString(KEY_GROUPES, gson.toJson(existing)).apply()
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

    /** Index pré-calculé Taxon → list<cdNom> pour servir l'autocomplétion en O(1) sur switch. */
    fun setIndexParTaxon(index: Map<Taxon, List<Int>>) {
        val asString = index.mapKeys { it.key.name }
        prefs.edit().putString(KEY_INDEX_TAXON, gson.toJson(asString)).apply()
        memIndexTaxon = asString
    }

    fun indexParTaxon(taxon: Taxon): List<Int>? = chargerIndexTaxon()[taxon.name]

    private fun chargerIndexTaxon(): Map<String, List<Int>> {
        memIndexTaxon?.let { return it }
        val json = prefs.getString(KEY_INDEX_TAXON, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, List<Int>>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, List<Int>>()).also { memIndexTaxon = it }
        } catch (e: Exception) { emptyMap() }
    }

    fun vider() {
        prefs.edit().remove(KEY).remove(KEY_VERSION).remove(KEY_COMPTES)
            .remove(KEY_GROUPES).remove(KEY_GROUPES1).remove(KEY_REGNES)
            .remove(KEY_INDEX_TAXON).apply()
        mem = null
        memGroupes = null
        memGroupes1 = null
        memRegnes = null
        memIndexTaxon = null
        memEntreesParCdNom = null
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
        memEntreesParCdNom = null
    }
}
