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

package fr.ariegenature.geomys.store

import android.content.Context
import fr.ariegenature.geomys.model.Taxon
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
    private const val KEY_LISTE_SYNC = "gn_taxref_liste_sync"
    private const val KEY_LISTES_SYNC = "gn_taxref_listes_sync_v1"

    // Gros fichiers stockés sur disque dans filesDir/taxref/.
    // SharedPreferences (XML lu/écrit en bloc) tronque ou échoue silencieusement
    // au-delà de ~1 Mo — un cache TaxRef complet (15k+ entrées) dépasse facilement.
    private const val FILE_CACHE = "cache_v3.json"
    private const val FILE_GROUPES = "groupes_v1.json"
    private const val FILE_GROUPES1 = "groupes1_v1.json"
    private const val FILE_REGNES = "regnes_v1.json"
    private const val FILE_INDEX_TAXON = "index_taxon_v1.json"
    private const val FILE_LISTES = "listes_v1.json"
    // Index COMPLET cd_nom → noms français, construit à la synchro. Indispensable car le cache
    // principal est indexé par NOM : quand plusieurs cd_nom partagent un nom vernaculaire, leurs
    // clés entrent en collision et tous sauf un perdent l'association. Cet index, lui, est sans perte.
    private const val FILE_VERNS = "verns_v1.json"

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
    @Volatile private var memListes: Map<String, List<Int>>? = null
    @Volatile private var memEntreesParCdNom: Map<Int, TaxRefEntry>? = null
    @Volatile private var memVernsParCdNom: Map<Int, List<String>>? = null
    // Memoization du dernier filtre par id_liste demandé — la saisie reste sur la même
    // liste pendant toute une session, recalculer à chaque suggestion serait gâché.
    @Volatile private var memCdNomsDansListe: Pair<Int, Set<Int>>? = null
    // Listes de suggestions (clés normalisées) servant l'autocomplete taxon. Memoizées
    // pour ne pas re-matérialiser 15-50k entrées à chaque rendu d'un champ TAXON (audit B5).
    @Volatile private var memTousLesNoms: List<String>? = null
    @Volatile private var memNomsParListe: Pair<Int, List<String>>? = null

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
            // Écriture atomique : tmp + rename (qui écrase la cible). On NE supprime PAS la
            // cible avant le rename — sinon un kill entre delete et rename laisserait l'ancien
            // cache PERDU (pas seulement non mis à jour). delete + retry seulement si le rename
            // direct échoue (certains FS refusent l'écrasement).
            val cible = fichier(nom)
            val tmp = File(dir, "$nom.tmp")
            tmp.writeText(contenu)
            if (!tmp.renameTo(cible)) {
                if (cible.exists()) cible.delete()
                tmp.renameTo(cible)
            }
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

    /** Nombre de CLÉS de noms en cache (un cd_nom a plusieurs clés : nom scientifique +
     *  vernaculaires). Sert aux tests « cache non vide » ; PAS au comptage de taxons affiché. */
    val count: Int get() = charger().size

    /** Nombre de taxons UNIQUES (cd_nom distincts) — c'est ce qu'on affiche à l'utilisateur,
     *  cohérent avec le détail par liste (où chaque ligne compte des cd_nom). */
    val nbTaxonsUniques: Int get() = entreesParCdNom().size

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

    /** Map cdNom → tous les noms français connus.
     *  Source PRIORITAIRE : l'index complet [FILE_VERNS] persisté à la synchro (sans perte par
     *  collision de noms). REPLI (cache ancien sans ce fichier) : dérivation depuis les entrées —
     *  incomplète quand plusieurs cd_nom partagent un nom vernaculaire, mais préserve l'existant. */
    fun vernsParCdNom(): Map<Int, List<String>> {
        memVernsParCdNom?.let { return it }
        lireFichier(FILE_VERNS)?.let { json ->
            runCatching {
                val type = object : TypeToken<Map<String, List<String>>>() {}.type
                val m: Map<String, List<String>> = gson.fromJson(json, type) ?: emptyMap()
                if (m.isNotEmpty()) {
                    val parInt = HashMap<Int, List<String>>(m.size)
                    for ((k, v) in m) k.toIntOrNull()?.let { parInt[it] = v }
                    memVernsParCdNom = parInt
                    return parInt
                }
            }
        }
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

    /** Persiste l'index COMPLET cd_nom → noms français, construit à la synchro sans collision de
     *  clés (cf. [FILE_VERNS]). À appeler APRÈS [remplacerTout] (qui réinitialise les memo). */
    fun ajouterVerns(verns: Map<Int, Collection<String>>) {
        val asString = verns.entries.associate { it.key.toString() to it.value.toList() }
        ecrireFichier(FILE_VERNS, gson.toJson(asString))
        memVernsParCdNom = verns.entries.associate { it.key to it.value.toList() }
    }

    fun getVernaculaireParCdNom(cdNom: Int): String? =
        vernsParCdNom()[cdNom]?.firstOrNull()

    /** Nom à AFFICHER pour un cd_nom : nom français si connu (index complet [vernsParCdNom]), sinon
     *  nom scientifique. Null si le taxon n'est pas dans le cache (espèce hors listes synchronisées). */
    fun nomAffichageParCdNom(cdNom: Int): String? =
        vernsParCdNom()[cdNom]?.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: entreesParCdNom()[cdNom]?.sciNom

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

    /** Stocke les listes UsersHub auxquelles chaque cd_nom appartient.
     *  Sert au filtrage des additional_fields (un champ avec `id_list = X` ne s'affiche que
     *  si le taxon observé est dans la liste X). */
    fun ajouterListesParCdNom(listes: Map<Int, List<Int>>) {
        if (listes.isEmpty()) return
        val existing = chargerListesParCdNom().toMutableMap()
        listes.forEach { (cd, l) -> if (l.isNotEmpty()) existing[cd.toString()] = l }
        ecrireFichier(FILE_LISTES, gson.toJson(existing))
        memListes = existing
        memCdNomsDansListe = null
        memNomsParListe = null
    }

    /** Retourne les id_liste UsersHub auxquelles le cd_nom appartient (vide si inconnu). */
    fun listesPourCdNom(cdNom: Int): List<Int> = chargerListesParCdNom()[cdNom.toString()] ?: emptyList()

    /** Tous les cd_nom appartenant à [idListe]. Renvoie un Set vide si la liste n'a pas
     *  été synchronisée. Memoizé pour la dernière liste demandée (recalcul en O(n) sur
     *  l'ensemble du cache `listesParCdNom`). */
    fun cdNomsDansListe(idListe: Int): Set<Int> {
        memCdNomsDansListe?.let { (id, set) -> if (id == idListe) return set }
        val result = HashSet<Int>()
        for ((cdStr, listes) in chargerListesParCdNom()) {
            if (idListe in listes) cdStr.toIntOrNull()?.let(result::add)
        }
        return result.also { memCdNomsDansListe = idListe to it }
    }

    /** id_liste → nombre de cd_nom (taxons uniques) du cache appartenant à cette liste. Sert au
     *  panneau « Détails » qui présente le contenu du cache regroupé par liste. Vide si aucune
     *  appartenance n'a été synchronisée. */
    fun comptesParListe(): Map<Int, Int> {
        val comptes = HashMap<Int, Int>()
        for ((_, listes) in chargerListesParCdNom()) {
            for (l in listes) comptes[l] = (comptes[l] ?: 0) + 1
        }
        return comptes
    }

    /** Clés (noms normalisés) servant de suggestions à l'autocomplete taxon.
     *  [idListe]=null → toutes les clés du cache. Sinon restreint aux taxons appartenant
     *  à la liste. Memoizé : la liste taxon ne change pas pendant une session de saisie,
     *  et le rendu d'un champ TAXON ne doit pas re-matérialiser 15-50k entrées à chaque
     *  fois (cf. audit B5). Invalidé quand le cache ou les listes changent. */
    fun nomsSuggestion(idListe: Int?): List<String> {
        if (idListe == null) {
            memTousLesNoms?.let { return it }
            return charger().keys.toList().also { memTousLesNoms = it }
        }
        memNomsParListe?.let { (id, noms) -> if (id == idListe) return noms }
        val autorises = cdNomsDansListe(idListe)
        val noms = charger().asSequence()
            .filter { (_, entry) -> entry.cdNom in autorises }
            .map { it.key }
            .toList()
        return noms.also { memNomsParListe = idListe to noms }
    }

    private fun chargerListesParCdNom(): Map<String, List<Int>> {
        memListes?.let { return it }
        val json = lireFichier(FILE_LISTES) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, List<Int>>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, List<Int>>()).also { memListes = it }
        } catch (e: Exception) { emptyMap() }
    }

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

    /** Index par taxon optionnellement filtré par appartenance à [idListeFiltre].
     *  [idListeFiltre]=null → comportement identique à [indexParTaxon]. */
    fun indexParTaxon(taxon: Taxon, idListeFiltre: Int?): List<Int>? {
        val full = chargerIndexTaxon()[taxon.name] ?: return null
        if (idListeFiltre == null) return full
        val dansListe = cdNomsDansListe(idListeFiltre)
        if (dansListe.isEmpty()) return emptyList()
        return full.filter { it in dansListe }
    }

    private fun chargerIndexTaxon(): Map<String, List<Int>> {
        memIndexTaxon?.let { return it }
        val json = lireFichier(FILE_INDEX_TAXON) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, List<Int>>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, List<Int>>()).also { memIndexTaxon = it }
        } catch (e: Exception) { emptyMap() }
    }

    fun vider() {
        listOf(FILE_CACHE, FILE_GROUPES, FILE_GROUPES1, FILE_REGNES, FILE_INDEX_TAXON, FILE_LISTES, FILE_VERNS)
            .forEach { runCatching { fichier(it).delete() } }
        prefs.edit()
            .remove(KEY_VERSION)
            .remove(KEY_COMPTES)
            .remove(KEY_LISTE_SYNC)
            .remove(KEY_LISTES_SYNC)
            .apply()
        mem = null
        memGroupes = null
        memGroupes1 = null
        memRegnes = null
        memIndexTaxon = null
        memListes = null
        memEntreesParCdNom = null
        memVernsParCdNom = null
        memCdNomsDansListe = null
        memTousLesNoms = null
        memNomsParListe = null
    }

    var versionSauvegardee: String?
        get() = prefs.getString(KEY_VERSION, null)
        set(v) = prefs.edit().putString(KEY_VERSION, v).apply()

    /** id_liste UsersHub utilisé lors de la dernière synchro réussie — sert à détecter
     *  un changement de liste dans la config sans re-sync (cache obsolète).
     *  Conservé pour compatibilité ascendante ; depuis le sync exhaustif, préférer
     *  [listesSynchronisees] qui porte l'ensemble des listes chargées. */
    var listeSynchroniseeId: String?
        get() = prefs.getString(KEY_LISTE_SYNC, null)
        set(v) = prefs.edit().putString(KEY_LISTE_SYNC, v).apply()

    /** Ensemble des id_liste UsersHub couvertes par le dernier sync exhaustif.
     *  Vide quand seul l'ancien sync mono-liste a été exécuté ou que rien n'est en cache.
     *  Stocké en CSV dans SharedPreferences (petit volume — ~quelques dizaines d'ids max). */
    var listesSynchronisees: List<Int>
        get() = prefs.getString(KEY_LISTES_SYNC, "")
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
        set(v) = prefs.edit().putString(KEY_LISTES_SYNC, v.joinToString(",")).apply()

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
        val f = fichier(FILE_CACHE)
        if (!f.exists()) return emptyMap()
        // Lecture en STREAMING : on ne matérialise jamais tout le fichier en une String géante
        // (sur 200k+ taxons, readText + gson.fromJson = dizaines de Mo → OOM). On lit les classes
        // de flux de Gson (pur Java → OK aussi en tests Robolectric, contrairement à android.util.*).
        return try {
            val map = HashMap<String, TaxRefEntry>(1 shl 18)
            java.io.BufferedReader(java.io.FileReader(f)).use { br ->
                com.google.gson.stream.JsonReader(br).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        val nom = r.nextName()
                        var cd = 0; var sci = ""; var fr: String? = null
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "cdNom" -> cd = r.nextInt()
                                "sciNom" -> sci = r.nextString()
                                "nomFrOriginal", "nomFr" ->
                                    fr = if (r.peek() == com.google.gson.stream.JsonToken.NULL) { r.nextNull(); null }
                                         else r.nextString().takeIf { it.isNotEmpty() }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        map[nom] = TaxRefEntry(cd, sci, fr)
                    }
                    r.endObject()
                }
            }
            map.also { mem = it }
        } catch (e: Exception) {
            // Repli tolérant (format atypique / fichier corrompu) : tentative Gson classique.
            try {
                val type = object : TypeToken<Map<String, TaxRefEntry>>() {}.type
                (gson.fromJson(lireFichier(FILE_CACHE), type) ?: emptyMap<String, TaxRefEntry>()).also { mem = it }
            } catch (_: Exception) { emptyMap() }
        }
    }

    /** Remplace TOUT le cache (chemin de synchro, après [vider]) — écriture streaming directe, sans
     *  recharger ni copier la map existante. Évite les pics mémoire sur les gros référentiels. */
    fun remplacerTout(entries: Map<String, TaxRefEntry>) = sauvegarder(entries)

    private fun sauvegarder(cache: Map<String, TaxRefEntry>) {
        ecrireCacheStream(cache)
        mem = cache
        memEntreesParCdNom = null
        memVernsParCdNom = null
        memTousLesNoms = null
        memNomsParListe = null
    }

    /** Écrit FILE_CACHE en STREAMING : la String JSON complète n'est jamais construite en mémoire
     *  (`gson.toJson` sur ~400k entrées = dizaines de Mo → OOM). Même format que la sérialisation
     *  Gson de Map<String, TaxRefEntry> (champ null omis). Écriture atomique tmp + rename. */
    private fun ecrireCacheStream(cache: Map<String, TaxRefEntry>) {
        try {
            val cible = fichier(FILE_CACHE)
            val tmp = File(dir, "$FILE_CACHE.tmp")
            java.io.BufferedWriter(java.io.FileWriter(tmp)).use { bw ->
                com.google.gson.stream.JsonWriter(bw).use { w ->
                    w.beginObject()
                    for ((nom, e) in cache) {
                        w.name(nom).beginObject()
                        w.name("cdNom").value(e.cdNom.toLong())
                        w.name("sciNom").value(e.sciNom)
                        if (e.nomFrOriginal != null) w.name("nomFrOriginal").value(e.nomFrOriginal)
                        w.endObject()
                    }
                    w.endObject()
                }
            }
            if (!tmp.renameTo(cible)) {
                if (cible.exists()) cible.delete()
                tmp.renameTo(cible)
            }
        } catch (_: Exception) {}
    }
}