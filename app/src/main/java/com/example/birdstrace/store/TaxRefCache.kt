package com.example.birdstrace.store

import android.content.Context
import com.example.birdstrace.model.Taxon
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

// Une seule entrée par clé du cache (clé = nom normalisé) — aligné sur iOS.
// `nomFrOriginal` = le nom français spécifique correspondant à CETTE clé (avec
// accents préservés). Null quand la clé est un nom scientifique.
data class TaxRefEntry(
    val cdNom: Int,
    val sciNom: String,
    val nomFrOriginal: String? = null
)

// Tolère l'ancien format (vernNoms: List<String>) pour éviter un crash si le
// fichier de cache survit à la mise à jour de l'app.
private class TaxRefEntryDeserializer : JsonDeserializer<TaxRefEntry> {
    override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): TaxRefEntry {
        val obj = json.asJsonObject
        val cdNom = obj.get("cdNom").asInt
        val sciNom = obj.get("sciNom").asString
        val nomFr: String? = when {
            obj.has("nomFrOriginal") && !obj.get("nomFrOriginal").isJsonNull ->
                obj.get("nomFrOriginal").asString.takeIf { it.isNotEmpty() }
            obj.has("vernNoms") && obj.get("vernNoms").isJsonArray ->
                obj.getAsJsonArray("vernNoms")
                    .mapNotNull { it.takeIf { e -> !e.isJsonNull }?.asString }
                    .firstOrNull { it.isNotEmpty() }
            obj.has("vernNom") && !obj.get("vernNom").isJsonNull ->
                obj.get("vernNom").asString.takeIf { it.isNotEmpty() }
            else -> null
        }
        return TaxRefEntry(cdNom, sciNom, nomFr)
    }
}

object TaxRefCache {
    // Petites clés conservées en SharedPreferences.
    private const val KEY_VERSION = "gn_taxref_version_cache"
    private const val KEY_COMPTES = "gn_taxref_comptes_v1"

    // Gros fichiers stockés sur disque dans filesDir/taxref/.
    // SharedPreferences (XML lu/écrit en bloc) tronque ou échoue silencieusement
    // au-delà de ~1 Mo — un cache TaxRef complet (15k+ entrées) dépasse facilement.
    private const val FILE_CACHE = "cache_v3.json"
    private const val FILE_GROUPES = "groupes_v1.json"
    private const val FILE_GROUPES1 = "groupes1_v1.json"
    private const val FILE_REGNES = "regnes_v1.json"
    private const val FILE_INDEX_TAXON = "index_taxon_v1.json"

    // Anciennes clés SharedPreferences — purgées à l'init pour libérer l'espace
    // après migration vers le stockage fichier.
    private val LEGACY_PREFS_KEYS = listOf(
        "gn_taxref_cache_v1", "gn_taxref_cache_v2",
        "gn_taxref_groupes_v1", "gn_taxref_groupes1_v1", "gn_taxref_regnes_v1",
        "gn_taxref_index_taxon_v1"
    )

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var dir: File
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(TaxRefEntry::class.java, TaxRefEntryDeserializer())
        .create()

    @Volatile private var mem: Map<String, TaxRefEntry>? = null
    @Volatile private var memGroupes: Map<String, String>? = null
    @Volatile private var memGroupes1: Map<String, String>? = null
    @Volatile private var memRegnes: Map<String, String>? = null
    @Volatile private var memIndexTaxon: Map<String, List<Int>>? = null
    @Volatile private var memEntreesParCdNom: Map<Int, TaxRefEntry>? = null
    @Volatile private var memVernsParCdNom: Map<Int, List<String>>? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("taxref_cache", Context.MODE_PRIVATE)
        dir = File(context.filesDir, "taxref").apply { mkdirs() }
        purgerLegacyPrefs()
    }

    private fun purgerLegacyPrefs() {
        val present = LEGACY_PREFS_KEYS.filter { prefs.contains(it) }
        if (present.isEmpty()) return
        val editor = prefs.edit()
        present.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun fichier(nom: String) = File(dir, nom)

    private fun lireFichier(nom: String): String? = try {
        val f = fichier(nom)
        if (f.exists()) f.readText() else null
    } catch (_: Exception) { null }

    private fun ecrireFichier(nom: String, contenu: String) {
        try {
            // Écriture atomique : tmp + rename. Évite un fichier tronqué si le
            // process est tué pendant la sauvegarde.
            val cible = fichier(nom)
            val tmp = File(dir, "$nom.tmp")
            tmp.writeText(contenu)
            if (cible.exists()) cible.delete()
            tmp.renameTo(cible)
        } catch (_: Exception) {}
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

    fun set(nom: String, cdNom: Int, sciNom: String, nomFr: String? = null) {
        val cache = charger().toMutableMap()
        cache[normaliser(nom)] = TaxRefEntry(cdNom, sciNom, nomFr?.takeIf { it.isNotEmpty() })
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

    /** Map cdNom → un TaxRefEntry représentatif (préfère ceux avec nomFrOriginal non null). */
    fun entreesParCdNom(): Map<Int, TaxRefEntry> {
        memEntreesParCdNom?.let { return it }
        val source = charger().values
        val parCdNom = HashMap<Int, TaxRefEntry>(source.size)
        for (e in source) {
            val existing = parCdNom[e.cdNom]
            if (existing == null || (existing.nomFrOriginal == null && e.nomFrOriginal != null)) {
                parCdNom[e.cdNom] = e
            }
        }
        return parCdNom.also { memEntreesParCdNom = it }
    }

    /** Map cdNom → tous les noms français connus (dérivée du cache, en mémoire). */
    fun vernsParCdNom(): Map<Int, List<String>> {
        memVernsParCdNom?.let { return it }
        val result = HashMap<Int, LinkedHashSet<String>>()
        for (entry in charger().values) {
            val nomFr = entry.nomFrOriginal ?: continue
            if (nomFr.isEmpty()) continue
            result.getOrPut(entry.cdNom) { LinkedHashSet() }.add(nomFr)
        }
        val frozen = result.mapValues { (_, v) -> v.toList() }
        memVernsParCdNom = frozen
        return frozen
    }

    fun getVernaculaireParCdNom(cdNom: Int): String? =
        vernsParCdNom()[cdNom]?.firstOrNull()

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
        ecrireFichier(FILE_GROUPES, gson.toJson(existing))
        memGroupes = existing
    }

    fun tousLesGroupes(): Map<String, String> = chargerGroupes()

    private fun chargerGroupes(): Map<String, String> {
        memGroupes?.let { return it }
        val json = lireFichier(FILE_GROUPES) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, String>()).also { memGroupes = it }
        } catch (e: Exception) { emptyMap() }
    }

    fun ajouterGroupes1etRegnes(groupes1: Map<Int, String>, regnes: Map<Int, String>) {
        val existingG1 = chargerGroupes1().toMutableMap()
        groupes1.forEach { (k, v) -> if (v.isNotEmpty()) existingG1[k.toString()] = v }
        ecrireFichier(FILE_GROUPES1, gson.toJson(existingG1))
        memGroupes1 = existingG1

        val existingR = chargerRegnes().toMutableMap()
        regnes.forEach { (k, v) -> if (v.isNotEmpty()) existingR[k.toString()] = v }
        ecrireFichier(FILE_REGNES, gson.toJson(existingR))
        memRegnes = existingR
    }

    fun tousLesGroupes1(): Map<String, String> = chargerGroupes1()
    fun tousLesRegnes(): Map<String, String> = chargerRegnes()

    private fun chargerGroupes1(): Map<String, String> {
        memGroupes1?.let { return it }
        val json = lireFichier(FILE_GROUPES1) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, String>()).also { memGroupes1 = it }
        } catch (e: Exception) { emptyMap() }
    }

    private fun chargerRegnes(): Map<String, String> {
        memRegnes?.let { return it }
        val json = lireFichier(FILE_REGNES) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, String>()).also { memRegnes = it }
        } catch (e: Exception) { emptyMap() }
    }

    /** Index pré-calculé Taxon → list<cdNom> pour servir l'autocomplétion en O(1) sur switch. */
    fun setIndexParTaxon(index: Map<Taxon, List<Int>>) {
        val asString = index.mapKeys { it.key.name }
        ecrireFichier(FILE_INDEX_TAXON, gson.toJson(asString))
        memIndexTaxon = asString
    }

    fun indexParTaxon(taxon: Taxon): List<Int>? = chargerIndexTaxon()[taxon.name]

    private fun chargerIndexTaxon(): Map<String, List<Int>> {
        memIndexTaxon?.let { return it }
        val json = lireFichier(FILE_INDEX_TAXON) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, List<Int>>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, List<Int>>()).also { memIndexTaxon = it }
        } catch (e: Exception) { emptyMap() }
    }

    fun vider() {
        listOf(FILE_CACHE, FILE_GROUPES, FILE_GROUPES1, FILE_REGNES, FILE_INDEX_TAXON)
            .forEach { runCatching { fichier(it).delete() } }
        prefs.edit().remove(KEY_VERSION).remove(KEY_COMPTES).apply()
        mem = null
        memGroupes = null
        memGroupes1 = null
        memRegnes = null
        memIndexTaxon = null
        memEntreesParCdNom = null
        memVernsParCdNom = null
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
        val json = lireFichier(FILE_CACHE) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, TaxRefEntry>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, TaxRefEntry>()).also { mem = it }
        } catch (e: Exception) { emptyMap() }
    }

    private fun sauvegarder(cache: Map<String, TaxRefEntry>) {
        ecrireFichier(FILE_CACHE, gson.toJson(cache))
        mem = cache
        memEntreesParCdNom = null
        memVernsParCdNom = null
    }
}