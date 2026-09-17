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

/** Une proposition d'autocomplétion d'espèce : le nom AFFICHÉ, le `cd_nom` qu'il désigne, et le
 *  nom montré juste en dessous (nom scientifique sous un nom français, et l'inverse en mode
 *  « noms scientifiques »).
 *
 *  Le nom seul ne suffit pas (audit 2026-09-17, C2) : deux taxons du même périmètre peuvent le
 *  porter, et le cache ne garde qu'un taxon par clé. Re-résoudre le texte après le clic rendait
 *  alors un AUTRE taxon que celui proposé — « Bousier rhinocéros » (*Copris lunaris*) enregistrait
 *  *Odonteus armiger*. La suggestion transporte donc son cd_nom jusqu'à l'enregistrement, et son
 *  nom scientifique rend le choix lisible quand deux taxons partagent le même nom français.
 *
 *  [toString] rend le nom principal : c'est lui que l'AutoCompleteTextView recopie dans le champ. */
data class SuggestionTaxon(val nom: String, val cdNom: Int, val secondaire: String? = null) {
    override fun toString(): String = nom
}

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
    // Index COMPLET cd_nom → nom scientifique, construit à la synchro. Même raison d'être que
    // [FILE_VERNS] côté noms français : le cache principal étant indexé par NOM, un taxon dont le
    // nom scientifique est déjà pris par un autre (homonymie inter-règnes comme le genre *Pieris*,
    // papillon ET plante ; ou deux cd_nom TaxRef pour un même lb_nom) n'y a AUCUNE entrée et
    // devient invisible partout. Cet index, lui, est sans perte.
    private const val FILE_SCI = "sci_v1.json"

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
    // Verrou process-wide sérialisant les lire-modifier-écrire du cache principal (charger / set /
    // ajouter / sauvegarder) : une résolution en ligne (`set`, sur IO) peut courir en parallèle
    // d'une synchro (`remplacerTout`, IO) → sans lock, lost-update et divergence mem↔disque.
    private val verrou = Any()
    @Volatile private var memGroupes: Map<String, String>? = null
    @Volatile private var memGroupes1: Map<String, String>? = null
    @Volatile private var memRegnes: Map<String, String>? = null
    @Volatile private var memIndexTaxon: Map<String, List<Int>>? = null
    @Volatile private var memListes: Map<String, List<Int>>? = null
    @Volatile private var memEntreesParCdNom: Map<Int, TaxRefEntry>? = null
    @Volatile private var memVernsParCdNom: Map<Int, List<String>>? = null
    @Volatile private var memSciParCdNom: Map<Int, String>? = null

    /** Incrémenté à chaque écriture du cache ou de ses index. Sert aux mémos EXTERNES
     *  (TaxRefLocal) qui dérivent du cache et doivent tomber avec lui. */
    @Volatile private var version = 0
    val versionDonnees: Int get() = version
    // Memoization du dernier filtre par id_liste demandé — la saisie reste sur la même
    // liste pendant toute une session, recalculer à chaque suggestion serait gâché.
    @Volatile private var memCdNomsDansListe: Pair<Int, Set<Int>>? = null
    // Listes de suggestions (clés normalisées) servant l'autocomplete taxon. Memoizées
    // pour ne pas re-matérialiser 15-50k entrées à chaque rendu d'un champ TAXON (audit B5).
    @Volatile private var memTousLesNoms: List<String>? = null
    @Volatile private var memNomsParListe: Pair<Int, List<String>>? = null
    // Index de RESOLUTION restreint a une liste taxonomique (module Suivis) : cle normalisee →
    // taxon. Memoize comme les suggestions — un formulaire reste sur la liste de son protocole.
    @Volatile private var memIndexResolutionListe: Pair<Int, Map<String, TaxRefEntry>>? = null

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

    /**
     * Résolution d'un nom RESTREINTE À UN GROUPE de taxons (oiseaux, mammifères…).
     *
     * Le cache principal est indexé par nom et ne garde qu'UNE entrée par clé : quand deux taxons
     * partagent un nom vernaculaire, l'autre devient inatteignable par ce nom. Cas réel du terrain
     * (2026-09-16) : « gobemouche gris » est le nom usuel de *Muscicapa striata* (4319, l'oiseau)
     * ET le quatrième nom de *Menemerus bivittatus* (2080, une araignée sauteuse) ; la clé pointait
     * sur l'araignée, et saisir « gobemouche gris » dans le groupe OISEAUX en attachait le cd_nom.
     *
     * Filtrer ne suffisait donc pas : un filtre ne sait que REJETER l'intrus, jamais RETROUVER le
     * bon. Ici, si l'entrée globale n'appartient pas au groupe, on cherche parmi les cd_nom DU
     * GROUPE celui qui porte ce nom — l'information existe déjà dans l'index vernaculaire, elle
     * n'était simplement pas consultée à la résolution.
     *
     * Le parcours porte sur le groupe (quelques centaines de cd_nom pour les vertébrés), et
     * seulement quand l'entrée globale est hors groupe — donc jamais sur le chemin nominal.
     */
    fun get(nom: String, cdNomsAutorises: Set<Int>?): TaxRefEntry? {
        val globale = get(nom)
        if (cdNomsAutorises == null || globale == null || globale.cdNom in cdNomsAutorises) return globale
        val cle = normaliser(nettoyerSuffixeArticle(nom))
        if (cle.isEmpty()) return globale
        val verns = vernsParCdNom()
        val parCdNom = entreesParCdNom()
        // DÉPARTAGE STABLE quand plusieurs taxons du groupe portent le nom (audit 2026-09-17,
        // C4) : rendre « le premier du Set » laissait décider l'ordre de hachage d'un HashSet.
        // Même critère qu'à la synchro (GeoNatureSync.meilleurCandidatVernaculaire) : le nom
        // SCIENTIFIQUE prime (rang -1, désignation univoque), puis le RANG du nom vernaculaire
        // (TaxRef énumère nom_vern par ordre de préférence), puis le plus petit cd_nom.
        var meilleurCd = -1
        var meilleurRang = Int.MAX_VALUE
        var meilleurNomFr: String? = null
        for (cd in cdNomsAutorises) {
            if (cd !in parCdNom) continue
            val rangVern = verns[cd].orEmpty()
                .indexOfFirst { normaliser(nettoyerSuffixeArticle(it)) == cle }
            val rang = when {
                parCdNom[cd]?.sciNom?.let { normaliser(it) == cle } == true -> -1
                rangVern >= 0 -> rangVern
                else -> continue
            }
            if (rang < meilleurRang || (rang == meilleurRang && cd < meilleurCd)) {
                meilleurRang = rang
                meilleurCd = cd
                meilleurNomFr = verns[cd].orEmpty().getOrNull(rangVern)
            }
        }
        if (meilleurCd > 0) {
            val e = parCdNom[meilleurCd]!!
            // Le nom AFFICHÉ reste celui que l'utilisateur a choisi, pas le nom principal du
            // taxon retrouvé : il a tapé « gobemouche gris », il doit lire « gobemouche gris ».
            return TaxRefEntry(e.cdNom, e.sciNom, meilleurNomFr ?: e.nomFrOriginal)
        }
        // Aucun taxon du groupe ne porte ce nom : on rend l'entrée globale telle quelle, à charge
        // pour l'appelant de la rejeter (c'est ce que fait TaxRefService via appartientAuGroupe).
        return globale
    }

    fun get(nom: String): TaxRefEntry? {
        val cache = charger()
        for (cle in variantesCle(nom)) cache[cle]?.let { return it }
        return null
    }

    /** Toutes les écritures d'un nom acceptées à la LECTURE, dans l'ordre d'essai : clé brute,
     *  clé sans suffixe d'article, puis — pour chacune — espaces multiples repliés, tiret ↔
     *  espace, et suppression totale des séparateurs (« rouge gorge » → « rougegorge », pour
     *  matcher les noms INPN écrits en un mot). La clé canonique écrite à la synchro n'est PAS
     *  modifiée : aucun rechargement requis, ces variantes marchent sur les caches existants.
     *  Factorisé pour que [get] et [getDansListe] acceptent EXACTEMENT les mêmes écritures. */
    internal fun variantesCle(nom: String): Set<String> {
        val base = normaliser(nom)
        val sansArticle = normaliser(nettoyerSuffixeArticle(nom))
        val res = linkedSetOf(base, sansArticle)
        for (b in linkedSetOf(base, sansArticle)) {
            val collapse = b.replace(Regex("\\s+"), " ").trim()
            res.add(collapse)
            res.add(collapse.replace(' ', '-'))
            res.add(collapse.replace('-', ' '))
            res.add(collapse.replace(" ", "").replace("-", ""))
        }
        return res
    }

    /**
     * Résolution d'un nom DANS le périmètre d'une liste taxonomique — celle qu'un protocole de
     * monitoring impose à son champ espèce (`id_list_taxonomy`).
     *
     * Pourquoi (audit 2026-09-17, C1) : le champ espèce des formulaires monitoring restreignait
     * ses SUGGESTIONS à la liste du protocole mais résolvait le nom saisi contre le cache ENTIER.
     * Le cache ne gardant qu'un taxon par clé, un nom du protocole capté par un autre taxon
     * partait avec le cd_nom de l'intrus : sur STERF (papillons), « Souci » — *Colias crocea* —
     * rendait *Calendula*, la plante ; « Paon » rendait *Pavo cristatus*, l'oiseau ; sur la liste
     * flore, « Genette » rendait le mammifère. Aucun signal : le champ n'affiche pas le taxon
     * retenu, et ne proteste que sur une résolution NULLE.
     *
     * [idListe] null → résolution globale inchangée. Liste absente du cache (non synchronisée) →
     * repli sur la résolution globale, cohérent avec le repli des suggestions côté formulaire
     * (un champ sans suggestion est inutilisable sur le terrain). Sinon, un nom qui ne désigne
     * aucun taxon de la liste est REFUSÉ (null) : le protocole impose son périmètre.
     */
    fun getDansListe(nom: String, idListe: Int?): TaxRefEntry? {
        if (idListe == null) return get(nom)
        // Liste ABSENTE du cache : aucune proposition n'est faite, donc aucun nom n'est
        // acceptable (décision produit 2026-09-17). Ce cas ne devrait pas se produire — le
        // chargement des données, listes comprises, est exigé avant toute saisie.
        if (listeAbsenteDuCache(idListe)) return null
        val index = indexResolutionListe(idListe)
        // Liste si large qu'elle ne restreint rien (cf. SEUIL_INDEX_LISTE) : résolution globale.
        if (index.isEmpty()) return get(nom)
        for (cle in variantesCle(nom)) index[cle]?.let { return it }
        return null
    }

    /** true quand la liste taxonomique demandée n'est pas du tout en cache : ni proposition ni
     *  résolution possibles — il faut recharger les données. */
    fun listeAbsenteDuCache(idListe: Int): Boolean = cdNomsDansListe(idListe).isEmpty()

    /**
     * Noms PROPOSABLES pour une liste taxonomique : exactement les noms que [getDansListe] sait
     * résoudre dans cette liste, dans leur graphie d'origine (« Souci », pas « souci »).
     *
     * Règle produit (2026-09-17) : dans un champ espèce, **seuls les noms proposés sont
     * acceptés** — et réciproquement, tout nom d'un taxon du protocole doit être proposé. Les
     * suggestions venaient jusqu'ici des CLÉS du cache principal, où un seul taxon garde chaque
     * clé : « Souci » (nom usuel de *Colias crocea*, dans la liste STERF) n'était pas proposé
     * parce que la clé appartient à *Calendula*, la plante. Construites depuis l'index de
     * résolution, les deux listes ne peuvent plus diverger.
     */
    fun nomsProposablesListe(idListe: Int): List<String> {
        // Liste absente du cache : AUCUNE proposition (et la résolution refuse tout de même).
        if (listeAbsenteDuCache(idListe)) return emptyList()
        val propositions = propositionsListe(idListe)
        // Liste si large qu'elle ne restreint rien (cf. SEUIL_INDEX_LISTE) : on garde les clés
        // du cache, que la résolution globale sait toutes retrouver.
        if (propositions.isEmpty()) return nomsSuggestion(idListe)
        return propositions.map { it.nom }.distinct()
    }

    /** Propositions d'un protocole, **porteuses de leur `cd_nom`** et du nom à afficher dessous
     *  (nom scientifique sous un nom français, premier nom français sous un nom scientifique).
     *
     *  Un nom porté par plusieurs taxons de la liste donne UNE LIGNE PAR TAXON : le nom
     *  scientifique affiché rend le choix explicite, au lieu de le trancher par une heuristique.
     *  Vide quand la liste n'est pas exploitable (non synchronisée, ou couvrant tout le
     *  référentiel) — l'appelant retombe alors sur [nomsProposablesListe]. */
    fun propositionsListe(idListe: Int): List<SuggestionTaxon> {
        if (indexResolutionListe(idListe).isEmpty()) return emptyList()
        val autorises = cdNomsDansListe(idListe)
        val parCdNom = entreesParCdNom()
        val verns = vernsParCdNom()
        val lignes = ArrayList<SuggestionTaxon>(autorises.size * 2)
        for (cd in autorises) {
            val sci = parCdNom[cd]?.sciNom.orEmpty()
            val vs = verns[cd].orEmpty()
            for (nom in vs) lignes.add(SuggestionTaxon(nom, cd, sci.ifEmpty { null }))
            if (sci.isNotEmpty()) lignes.add(SuggestionTaxon(sci, cd, vs.firstOrNull()))
        }
        return lignes.distinctBy { it.nom to it.cdNom }
            .sortedWith(compareBy({ it.nom }, { it.cdNom }))
    }

    /** Taille au-delà de laquelle une liste taxonomique est considérée comme non restrictive
     *  (cf. [indexResolutionListe]). Les listes de protocole réelles font quelques centaines à
     *  quelques milliers de taxons ; seule une liste « toutes espèces » approche ce seuil. */
    private const val SEUIL_INDEX_LISTE = 60_000

    /** Index de résolution d'une liste taxonomique : clé normalisée → taxon DE CETTE LISTE.
     *  Construit depuis l'index vernaculaire complet ([vernsParCdNom]) et les noms scientifiques,
     *  donc SANS la perte par collision du cache principal. Départage identique à la synchro :
     *  nom scientifique d'abord, puis rang du nom vernaculaire, puis plus petit cd_nom.
     *  Memoizé pour la dernière liste demandée — un formulaire reste sur celle de son protocole ;
     *  à préchauffer hors du thread principal (le rendu du champ TAXON le fait). */
    fun indexResolutionListe(idListe: Int): Map<String, TaxRefEntry> {
        memIndexResolutionListe?.let { (id, index) -> if (id == idListe) return index }
        val autorises = cdNomsDansListe(idListe)
        // Une « liste » qui couvre tout le référentiel (la 100 de ce serveur : 100 454 taxons sur
        // 100 468) ne restreint rien : lui construire un index dupliquerait le cache entier en
        // mémoire — plusieurs dizaines de Mo sur un téléphone d'entrée de gamme — pour un
        // résultat équivalent à la résolution globale. On rend alors un index VIDE, ce qui fait
        // retomber [getDansListe] sur [get] et [nomsProposablesListe] sur les clés du cache.
        if (autorises.size >= SEUIL_INDEX_LISTE) {
            return emptyMap<String, TaxRefEntry>().also { memIndexResolutionListe = idListe to it }
        }
        val parCdNom = entreesParCdNom()
        val verns = vernsParCdNom()
        // clé → (rang, cd_nom, nom français d'origine)
        val meilleur = HashMap<String, Triple<Int, Int, String?>>()
        fun proposer(cle: String, rang: Int, cd: Int, nomFr: String?) {
            if (cle.isEmpty()) return
            val actuel = meilleur[cle]
            if (actuel == null || rang < actuel.first || (rang == actuel.first && cd < actuel.second)) {
                meilleur[cle] = Triple(rang, cd, nomFr)
            }
        }
        for (cd in autorises) {
            parCdNom[cd]?.sciNom?.takeIf { it.isNotEmpty() }?.let { proposer(normaliser(it), -1, cd, null) }
            verns[cd].orEmpty().forEachIndexed { rang, nom ->
                proposer(normaliser(nettoyerSuffixeArticle(nom)), rang, cd, nom)
            }
        }
        // Un cd_nom de la liste peut n'avoir AUCUNE entrée dans le cache principal (toutes ses
        // clés captées par d'autres taxons) : on le sert quand même, avec un nom scientifique
        // vide plutôt que de le rendre introuvable dans son propre protocole.
        val index = meilleur.mapValues { (_, v) ->
            TaxRefEntry(v.second, parCdNom[v.second]?.sciNom.orEmpty(), v.third)
        }
        return index.also { memIndexResolutionListe = idListe to it }
    }

    // ── Recherche vocale : index par mots (niveau 3) + approché (niveau 2) ─────────────────────
    // Utilisés en dernier recours par TaxRefService.rechercher(avecRechercheEtendue=true), sur le
    // CHEMIN VOCAL uniquement — jamais par l'autocomplétion clavier (perf). Opèrent sur le cache
    // LOCAL (hors-ligne). Objectif : rattraper une transcription approximative sans jamais
    // produire de FAUX POSITIF (un auto-ajout vocal erroné = donnée fausse) → « pas de match »
    // plutôt qu'un match douteux.

    @Volatile private var memIndexMots: Map<String, Set<Int>>? = null

    /** Index inversé mot-normalisé → cd_nom, construit (memoïzé) depuis les clés du cache. Les
     *  clés étant déjà uniques par nom (dédoublonnées au sync, cf. collision cd_nom), un mot
     *  pointe vers l'ensemble des cd_nom des noms qui le contiennent. */
    private fun indexMots(): Map<String, Set<Int>> {
        memIndexMots?.let { return it }
        val idx = HashMap<String, MutableSet<Int>>()
        for ((cle, entry) in charger()) {
            for (mot in cle.split(regexSeparateurs)) {
                if (mot.length < 2) continue
                idx.getOrPut(mot) { HashSet() }.add(entry.cdNom)
            }
        }
        return idx.also { memIndexMots = it }
    }

    private val regexSeparateurs = Regex("[ \\-]+")

    /** Toutes les segmentations d'une liste de mots par CONCATÉNATION de mots adjacents
     *  (« rouge »,« gorge » → {rouge,gorge} ET {rougegorge}). Bornée : au-delà de 4 mots, on
     *  se limite au tout-séparé et au tout-concaténé pour éviter l'explosion 2^(n-1). */
    private fun segmentations(mots: List<String>): List<List<String>> {
        if (mots.size > 4) return listOf(mots, listOf(mots.joinToString("")))
        val res = mutableListOf<List<String>>()
        fun rec(i: Int, courant: List<String>) {
            if (i == mots.size) { res.add(courant); return }
            val sb = StringBuilder()
            for (j in i until mots.size) {
                sb.append(mots[j])
                rec(j + 1, courant + sb.toString())
            }
        }
        rec(0, emptyList())
        return res
    }

    /** Match par MOTS : tolère l'ordre, les mots manquants (sous-ensemble) et la
     *  concaténation/séparation (« rouge gorge » ↔ « Rougegorge familier », « gorge rouge »
     *  ↔ « rouge gorge »). Retourne l'entrée SEULEMENT si l'ensemble des segmentations désigne
     *  un cd_nom UNIQUE (après filtrage éventuel par [cdNomsAutorises]) — sinon null (ambigu). */
    fun chercherParMots(nomNormalise: String, cdNomsAutorises: Set<Int>? = null): TaxRefEntry? {
        val mots = nomNormalise.split(regexSeparateurs).filter { it.length >= 2 }
        if (mots.isEmpty()) return null
        val idx = indexMots()
        val cdNoms = LinkedHashSet<Int>()
        for (seg in segmentations(mots)) {
            var inter: MutableSet<Int>? = null
            var ok = true
            for (token in seg) {
                val s = idx[token]
                if (s == null) { ok = false; break }
                inter = if (inter == null) HashSet(s) else inter.apply { retainAll(s) }
                if (inter.isEmpty()) { ok = false; break }
            }
            if (!ok || inter == null) continue
            if (cdNomsAutorises != null) inter.retainAll(cdNomsAutorises)
            cdNoms.addAll(inter)
            if (cdNoms.size > 1) return null // ambigu → on n'invente pas
        }
        return if (cdNoms.size == 1) entreesParCdNom()[cdNoms.first()] else null
    }

    /** Match APPROCHÉ (distance de Levenshtein bornée) — dernier recours, pour une vraie faute
     *  de transcription (« lucorun » → « lucorum »). Préfiltre bon marché (même 1ʳᵉ lettre,
     *  écart de longueur ≤ seuil) avant tout calcul. Seuil : ≤1 pour clé courte (<8), ≤2 sinon.
     *  Retourne null en cas d'ambiguïté (deux cd_nom à égale distance minimale) — pas de faux
     *  positif. [cdNomsAutorises] restreint au groupe taxon quand fourni. */
    fun chercherApproche(nomNormalise: String, cdNomsAutorises: Set<Int>? = null): TaxRefEntry? {
        val q = nomNormalise.replace(regexSeparateurs, " ").trim()
        if (q.length < 3) return null // trop court → trop de collisions fortuites
        val seuil = if (q.length < 8) 1 else 2
        val premier = q.first()
        var meilleureCle: String? = null
        var meilleureDist = Int.MAX_VALUE
        var meilleurCd = -1
        var ambigu = false
        for ((cle, entry) in charger()) {
            if (cle.isEmpty() || cle.first() != premier) continue
            if (kotlin.math.abs(cle.length - q.length) > seuil) continue
            if (cdNomsAutorises != null && entry.cdNom !in cdNomsAutorises) continue
            val d = distanceBornee(q, cle, seuil)
            if (d < 0) continue
            if (d < meilleureDist) {
                meilleureDist = d; meilleureCle = cle; meilleurCd = entry.cdNom; ambigu = false
            } else if (d == meilleureDist && entry.cdNom != meilleurCd) {
                ambigu = true
            }
        }
        if (meilleureCle == null || ambigu) return null
        return charger()[meilleureCle]
    }

    /** Distance de Levenshtein bornée (2 lignes, sans dépendance) : renvoie -1 dès que toute une
     *  ligne dépasse [seuil] (abandon anticipé), sinon la distance exacte. */
    private fun distanceBornee(a: String, b: String, seuil: Int): Int {
        val n = a.length; val m = b.length
        if (kotlin.math.abs(n - m) > seuil) return -1
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var minLigne = cur[0]
            val ca = a[i - 1]
            for (j in 1..m) {
                val cout = if (ca == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cout)
                if (cur[j] < minLigne) minLigne = cur[j]
            }
            if (minLigne > seuil) return -1
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[m]
    }

    /** Ajoute UNE entrée et réécrit tout le fichier (plusieurs Mo) en invalidant les mémos :
     *  réservé aux préparations de cache et aux tests. **Aucun chemin de saisie ne doit
     *  l'appeler** — c'était le défaut C3 de l'audit 2026-09-17, quand une réponse de l'API
     *  TaxRef y passait à chaque frappe. La résolution est désormais purement locale. */
    fun set(nom: String, cdNom: Int, sciNom: String, nomFr: String? = null) = synchronized(verrou) {
        val cache = chargerInterne().toMutableMap()
        cache[normaliser(nom)] = TaxRefEntry(cdNom, sciNom, nomFr?.takeIf { it.isNotEmpty() })
        sauvegarder(cache)
    }

    fun ajouter(entries: Map<String, TaxRefEntry>) = synchronized(verrou) {
        val cache = chargerInterne().toMutableMap()
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
        // Taxons dont AUCUN nom n'a gagné sa clé dans le cache principal (260 sur le référentiel
        // de référence : homonymies comme le genre *Pieris*, papillon et plante à la fois). Sans
        // eux ici, ils n'étaient ni affichables, ni proposables, ni résolvables — audit
        // 2026-09-17, constat C5. L'index par cd_nom, lui, ne perd personne.
        val verns = vernsParCdNom()
        for ((cd, sci) in sciNomsParCdNom()) {
            if (cd in parCdNom || sci.isEmpty()) continue
            parCdNom[cd] = TaxRefEntry(cd, sci, verns[cd]?.firstOrNull())
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

    /** Map cdNom → nom scientifique, SANS la perte par collision de clés du cache principal
     *  (cf. [FILE_SCI]). Vide tant que l'index n'a pas été écrit par une synchro. */
    fun sciNomsParCdNom(): Map<Int, String> {
        memSciParCdNom?.let { return it }
        lireFichier(FILE_SCI)?.let { json ->
            runCatching {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val m: Map<String, String> = gson.fromJson(json, type) ?: emptyMap()
                if (m.isNotEmpty()) {
                    val parInt = HashMap<Int, String>(m.size)
                    for ((k, v) in m) k.toIntOrNull()?.let { parInt[it] = v }
                    memSciParCdNom = parInt
                    return parInt
                }
            }
        }
        // Index absent (cache écrit avant ce correctif) : VIDE, et non un repli dérivé du cache
        // principal — celui-ci ne contient par définition que les taxons ayant déjà une entrée,
        // le repli n'apporterait donc rien et dupliquerait 100 000 entrées en mémoire. Les
        // taxons sans clé restent invisibles jusqu'au prochain « Recharger les données » : pas
        // de rechargement forcé pour 260 taxons sur 100 000.
        return emptyMap<Int, String>().also { memSciParCdNom = it }
    }

    /** Persiste l'index COMPLET cd_nom → nom scientifique (cf. [FILE_SCI]).
     *  À appeler APRÈS [remplacerTout], qui réinitialise les mémos. */
    fun ajouterSciNoms(sciNoms: Map<Int, String>) {
        val asString = sciNoms.entries.filter { it.value.isNotEmpty() }
            .associate { it.key.toString() to it.value }
        if (asString.isEmpty()) return
        ecrireFichier(FILE_SCI, gson.toJson(asString))
        version++
        memSciParCdNom = asString.entries.associate { it.key.toInt() to it.value }
        // [entreesParCdNom] intègre ces taxons : le mémo précédent les ignore.
        memEntreesParCdNom = null
    }

    /** Persiste l'index COMPLET cd_nom → noms français, construit à la synchro sans collision de
     *  clés (cf. [FILE_VERNS]). À appeler APRÈS [remplacerTout] (qui réinitialise les memo). */
    fun ajouterVerns(verns: Map<Int, Collection<String>>) {
        val asString = verns.entries.associate { it.key.toString() to it.value.toList() }
        ecrireFichier(FILE_VERNS, gson.toJson(asString))
        version++
        memVernsParCdNom = verns.entries.associate { it.key to it.value.toList() }
        // L'index de résolution par liste est construit SUR ces noms : le laisser en place
        // servirait des taxons résolus depuis l'index vernaculaire précédent. Idem pour les
        // entrées par cd_nom, qui y puisent le nom français des taxons sans clé.
        memIndexResolutionListe = null
        memEntreesParCdNom = null
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
        version++
        memListes = existing
        memCdNomsDansListe = null
        memNomsParListe = null
        memIndexResolutionListe = null
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
        version++
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
        listOf(FILE_CACHE, FILE_GROUPES, FILE_GROUPES1, FILE_REGNES, FILE_INDEX_TAXON, FILE_LISTES,
            FILE_VERNS, FILE_SCI)
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
        memSciParCdNom = null
        memCdNomsDansListe = null
        memTousLesNoms = null
        memNomsParListe = null
        memIndexResolutionListe = null
        memIndexMots = null
        version++
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

    /** Clé de recherche : minuscules, accents dépliés. C'est la fonction la plus appelée de
     *  l'application (chaque nom proposé, à chaque frappe, sur des listes de 20 à 50 000 noms) —
     *  d'où l'écriture sans allocation : `map { }.joinToString()` construisait une liste de
     *  caractères BOXÉS par nom, et les noms scientifiques, tous en ASCII, ressortent désormais
     *  sans qu'une seule chaîne intermédiaire soit créée. */
    fun normaliser(nom: String): String {
        val bas = nom.trim().lowercase()
        var i = 0
        while (i < bas.length && deplier(bas[i]) == bas[i]) i++
        if (i == bas.length) return bas // rien à déplier (cas majoritaire)
        val sb = StringBuilder(bas.length)
        sb.append(bas, 0, i)
        while (i < bas.length) { sb.append(deplier(bas[i])); i++ }
        return sb.toString()
    }

    private fun deplier(c: Char): Char = when (c) {
        'à', 'â', 'ä' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'î', 'ï' -> 'i'
        'ô', 'ö' -> 'o'
        'ù', 'û', 'ü' -> 'u'
        'ç' -> 'c'
        else -> c
    }

    // Verrouillé : sérialise avec set/ajouter/sauvegarder (le corps réel est [chargerInterne]).
    private fun charger(): Map<String, TaxRefEntry> = synchronized(verrou) { chargerInterne() }

    private fun chargerInterne(): Map<String, TaxRefEntry> {
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
     *  recharger ni copier la map existante. Évite les pics mémoire sur les gros référentiels.
     *  Renvoie le succès de l'ÉCRITURE DISQUE : false (espace plein…) = rien n'est en place, ni
     *  sur disque ni en mémoire — l'appelant ne doit pas poser la version (audit 2026-08-27 : une
     *  écriture échouée passait pour une synchro réussie, appli « configurée » sans taxons). */
    fun remplacerTout(entries: Map<String, TaxRefEntry>): Boolean = synchronized(verrou) { sauvegarder(entries) }

    private fun sauvegarder(cache: Map<String, TaxRefEntry>): Boolean = synchronized(verrou) {
        if (!ecrireCacheStream(cache)) return@synchronized false
        mem = cache
        version++
        memEntreesParCdNom = null
        memVernsParCdNom = null
        memTousLesNoms = null
        memNomsParListe = null
        memIndexResolutionListe = null
        memIndexMots = null
        true
    }

    /** Écrit FILE_CACHE en STREAMING : la String JSON complète n'est jamais construite en mémoire
     *  (`gson.toJson` sur ~400k entrées = dizaines de Mo → OOM). Même format que la sérialisation
     *  Gson de Map<String, TaxRefEntry> (champ null omis). Écriture atomique tmp + rename.
     *  Renvoie false si l'écriture ou le rename échoue (le tmp est nettoyé). */
    private fun ecrireCacheStream(cache: Map<String, TaxRefEntry>): Boolean {
        return try {
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
                if (!tmp.renameTo(cible)) { runCatching { tmp.delete() }; return false }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("TaxRefCache", "Écriture du cache TaxRef échouée : ${e.javaClass.simpleName} ${e.message}")
            runCatching { File(dir, "$FILE_CACHE.tmp").delete() }
            false
        }
    }
}