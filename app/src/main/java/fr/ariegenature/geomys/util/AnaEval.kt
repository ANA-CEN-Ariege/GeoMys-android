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

package fr.ariegenature.geomys.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Champs métier ANA / Natura 2000 encodés dans les champs libres d'OccHab — portage Kotlin
 * FIDÈLE du plugin QGIS maison `occhab-qgis` (src/processing/eval_fields.py + referentiels.py ;
 * la répartition station/habitat des clés vient de champs.py).
 *
 * OccHab n'a pas de champ natif pour ces notions : le plugin insère dans les champs texte —
 * `comment` (station) et `technical_precision` (habitat) — un **bloc balisé non destructif**
 * dont le contenu est du **JSON** :
 *
 *     Texte libre saisi par l'utilisateur.
 *
 *     [ANA-EVAL] {"enjeu": "fort", "etat_conservation": "bon"} [/ANA-EVAL]
 *
 * L'**ancien format** `clé=valeur | clé=valeur` reste lu (stations déjà synchronisées) et se
 * convertit en JSON à la première réécriture, sans jamais toucher au texte humain.
 *
 * Les valeurs sont **validées à l'écriture comme à la lecture** contre les référentiels
 * fermés : un code hors liste n'est pas écrit, un code hérité (alias) est converti au passage.
 * Deux clés STRUCTURÉES (`determination`, `corresp`) portent des arbitrages du botaniste :
 * GeoMys les PRÉSERVE telles quelles (validées mais jamais éditées ici).
 *
 * Représentation des valeurs dans les maps : String (codes, textes), Int (échelle, recouvrement
 * entier), Double (recouvrement décimal), List<String> (pee), Map (determination, corresp).
 */
object AnaEval {

    const val EVAL_START = "[ANA-EVAL]"
    const val EVAL_END = "[/ANA-EVAL]"
    private val EVAL_RE = Regex(
        Regex.escape(EVAL_START) + "(.*?)" + Regex.escape(EVAL_END),
        RegexOption.DOT_MATCHES_ALL,
    )

    // ── Référentiels fermés (referentiels.py — ordre d'AFFICHAGE conservé). ──────────────────
    // (code stocké, libellé UI). Chaque liste garde ses alias de codes hérités : sans eux, une
    // valeur retirée du référentiel serait relue « non renseigné » puis effacée à la réécriture.

    val STATUTS_VALIDATION = listOf("brouillon" to "Brouillon", "valide" to "Validé")

    /** Niveau d'enjeu ANA (hors cahier des charges N2000), du plus fort au plus faible. */
    val NIVEAUX_ENJEU = listOf(
        "tres_fort" to "Très fort",
        "fort" to "Fort",
        "moyen" to "Moyen",
        "faible" to "Faible",
        "aucun" to "Aucun",
        "inconnu" to "Inconnu",
    )
    val ALIAS_ENJEU = mapOf("majeur" to "tres_fort") // référentiel ANA antérieur.

    /** Annexe 2 N2000, table HABITAT : id_et_cons (état de conservation). */
    val ETATS_CONSERVATION = listOf(
        "inconnu" to "Inconnu",
        "excellent" to "Excellent",
        "bon" to "Bon",
        "moyen" to "Moyen",
        "mauvais" to "Mauvais",
    )
    val ALIAS_ETAT = mapOf("nd" to "inconnu") // « Non déterminé » du référentiel ANA antérieur.

    /** Zone humide ANA : trois cas terrain (oui / non / à vérifier), plus une case à cocher. */
    val ZONES_HUMIDES = listOf("oui" to "Oui", "non" to "Non", "a_verifier" to "À vérifier")
    val ALIAS_ZONE_HUMIDE = mapOf("true" to "oui", "vrai" to "oui", "false" to "non", "faux" to "non")

    /** Annexe 2, table HABITAT : id_dynam (dynamique). */
    val DYNAMIQUES = listOf(
        "inconnue" to "Inconnue",
        "stable" to "Stable",
        "progressive_lente" to "Progressive lente",
        "regressive_lente" to "Régressive lente",
        "progressive_rapide" to "Progressive rapide",
        "regressive_rapide" to "Régressive rapide",
    )

    /** Annexe 2, table HABITAT : id_restaur (restauration) — ordre de l'annexe. */
    val RESTAURATIONS = listOf(
        "inconnu" to "Inconnu",
        "difficile" to "Difficile",
        "impossible" to "Impossible",
        "possible" to "Possible",
        "possible_avec_efforts" to "Possible avec efforts",
    )

    /** Annexe 2, table HABITAT : id_typi (typicité). */
    val TYPICITES = listOf(
        "inconnue" to "Inconnue",
        "bonne" to "Bonne",
        "moyenne" to "Moyenne",
        "mauvaise" to "Mauvaise",
    )

    /** Annexe 2, table GEOMETRIE : id_uv (unité végétale) — champ de la STATION. */
    val UNITES_VEGETALES = listOf(
        "non_complexe" to "Unité non complexe",
        "mosaique_non_definie" to "Mosaïque de type non défini",
        "mosaique_temporelle" to "Mosaïque temporelle",
        "mosaique_topographique" to "Mosaïque topographique",
        "mixte" to "Unité mixte",
    )

    /** Annexe 2, table GEOMETRIE : id_nat_obs (nature de l'observation). */
    val NATURES_OBSERVATION = listOf(
        "inconnu" to "Inconnu",
        "directe_avec_releve" to "Observation directe avec relevé phytosociologique",
        "directe_sans_releve" to "Observation directe sans relevé phytosociologique",
        "a_distance" to "Observation à distance",
        "photo_interpretation" to "Photo-interprétation",
        "autre" to "Autre",
    )

    /** Typologies de correspondance arbitrable : (clé HABREF `lb_nom_typo`, libellé, nom court). */
    val TYPOLOGIES_CORRESPONDANCE = listOf(
        Triple("CORINE_biotopes", "CORINE biotopes", "corine"),
        Triple("EUNIS", "EUNIS", "eunis"),
        Triple("Habitats_d'intérêt_communautaire", "Natura 2000", "n2000"),
        Triple("Cahiers_d'habitats", "Cahiers d'habitats", "cahiers"),
    )

    /** Sources d'une correspondance. `manuel` = arbitrage humain — une valeur hors liste est
     *  ÉCARTÉE plutôt que corrigée : inventer « manuel » ferait croire à une vérification. */
    val SOURCES_CORRESPONDANCE = setOf("catalogue", "habref", "manuel")

    /** Libellé d'un [code] dans un référentiel (code, libellé) — pour l'affichage UI. */
    fun libelle(items: List<Pair<String, String>>, code: String?, defaut: String = ""): String =
        items.firstOrNull { it.first == code }?.second ?: defaut

    // ── Description des clés du bloc (eval_fields.py). ────────────────────────────────────────
    private fun codesDe(items: List<Pair<String, String>>): Set<String> =
        items.mapTo(mutableSetOf()) { it.first }

    /** Codes fermés : clé → (codes valides, alias des codes hérités). */
    private val CODE_FIELDS: Map<String, Pair<Set<String>, Map<String, String>>> = mapOf(
        "statut" to (codesDe(STATUTS_VALIDATION) to emptyMap()),
        "enjeu" to (codesDe(NIVEAUX_ENJEU) to ALIAS_ENJEU),
        "etat_conservation" to (codesDe(ETATS_CONSERVATION) to ALIAS_ETAT),
        "dynamique" to (codesDe(DYNAMIQUES) to emptyMap()),
        "restauration" to (codesDe(RESTAURATIONS) to emptyMap()),
        "typicite" to (codesDe(TYPICITES) to emptyMap()),
        "unite_vegetale" to (codesDe(UNITES_VEGETALES) to emptyMap()),
        "nature_observation" to (codesDe(NATURES_OBSERVATION) to emptyMap()),
        "zone_humide" to (codesDe(ZONES_HUMIDES) to ALIAS_ZONE_HUMIDE),
    )
    private val TEXT_FIELDS = setOf("critere", "remarque")
    private val LIST_FIELDS = mapOf("pee" to 3) // plantes exotiques envahissantes : 3 taxons max.
    /** Entiers bornés : clé → (mini, maxi). `echelle` = échelle de numérisation N2000. */
    private val INT_FIELDS = mapOf("echelle" to (1 to 1_000_000))

    /** Bornes (mini, maxi) d'un champ entier borné, null si [cle] n'en est pas un — pour que
     *  l'UI BLOQUE une valeur hors bornes au lieu de la laisser effacer en silence par
     *  [nettoyer] (audit 2026-08-27). */
    fun borneEntier(cle: String): Pair<Int, Int>? = INT_FIELDS[cle]
    private val TYPOLOGIES: Set<String> = TYPOLOGIES_CORRESPONDANCE.mapTo(mutableSetOf()) { it.first }

    // ── Répartition des clés ÉDITABLES par niveau (champs.py, stockage EVAL). ─────────────────
    // `statut` (station), `recouvrement` (habitat, champ DOUBLE aligné sur recovery_percentage),
    // `determination` et `corresp` (arbitrages botaniste) ne sont PAS éditables dans GeoMys :
    // ils traversent tels quels.

    /** Champ éditable du bloc pour l'UI : [referentiel] non-null = code fermé (spinner) ;
     *  null = saisie libre ([entier] = EditText numérique, sinon texte multiligne). */
    data class ChampAnaEval(
        val cle: String,
        val libelle: String,
        val referentiel: List<Pair<String, String>>? = null,
        val entier: Boolean = false,
    )

    /** Clés portées par la STATION (bloc du `comment`) selon champs.py (niveau STATION, EVAL).
     *  `statut` est éditable ICI aussi (demande terrain 2026-08-26) : côté QGIS c'est une
     *  colonne locale re-fusionnée à la synchro, mais dans le bloc c'est une clé comme une
     *  autre — l'appli doit pouvoir faire passer une station de « brouillon » à « validé ». */
    val CHAMPS_STATION = listOf(
        ChampAnaEval("statut", "Statut de validation", STATUTS_VALIDATION),
        ChampAnaEval("enjeu", "Enjeu", NIVEAUX_ENJEU),
        ChampAnaEval("etat_conservation", "État de conservation", ETATS_CONSERVATION),
        ChampAnaEval("zone_humide", "Zone humide", ZONES_HUMIDES),
        ChampAnaEval("unite_vegetale", "Unité végétale", UNITES_VEGETALES),
        ChampAnaEval("nature_observation", "Nature de l'observation", NATURES_OBSERVATION),
        ChampAnaEval("echelle", "Échelle de numérisation", entier = true),
    )

    /** Clés portées par l'HABITAT (bloc du `technical_precision`) selon champs.py (niveau
     *  HABITAT, EVAL) — `pee` est géré à part par l'UI (3 champs). */
    val CHAMPS_HABITAT = listOf(
        ChampAnaEval("enjeu", "Enjeu", NIVEAUX_ENJEU),
        ChampAnaEval("etat_conservation", "État de conservation", ETATS_CONSERVATION),
        ChampAnaEval("typicite", "Typicité", TYPICITES),
        ChampAnaEval("dynamique", "Dynamique", DYNAMIQUES),
        ChampAnaEval("restauration", "Restauration", RESTAURATIONS),
        ChampAnaEval("critere", "Critère d'évaluation"),
        ChampAnaEval("remarque", "Remarque"),
    )

    // ── Décodage / encodage (eval_fields.py). ─────────────────────────────────────────────────

    /** Retire les BALISES du bloc d'un texte : ce sont NOS délimiteurs — une balise saisie ou
     *  collée par l'utilisateur ferait couper le bloc au mauvais endroit à la relecture. */
    private fun sansBalises(texte: String?): String =
        (texte ?: "").replace(EVAL_START, "").replace(EVAL_END, "")

    /** Contenu brut entre les balises, ou null s'il n'y a pas de bloc. */
    private fun blocBrut(texte: String?): String? =
        EVAL_RE.find(texte ?: "")?.groupValues?.get(1)?.trim()

    /** Texte humain seul (blocs complets retirés), pour l'affichage — strip_eval du python. */
    fun texteHumain(texte: String?): String = EVAL_RE.replace(texte ?: "", "").trim()

    /** Ancien format `clé=valeur | clé=valeur` → map. */
    private fun parserLegacy(raw: String): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for (part in raw.split("|")) {
            val idx = part.indexOf('=')
            if (idx < 0) continue
            val key = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (value.isNotEmpty()) result[key] = value
        }
        return result
    }

    /** Objet JSON [raw] → map Kotlin (récursif), null si [raw] n'est pas un objet lisible.
     *  NB : org.json est plus tolérant que json.loads (clés non citées acceptées) — sans effet
     *  pratique, la validation par clé écarte de toute façon ce qui n'est pas au format. */
    private fun parserJsonObjet(raw: String): Map<String, Any?>? = try {
        val o = JSONObject(raw)
        val m = LinkedHashMap<String, Any?>()
        for (k in o.keys()) m[k] = deJson(o.opt(k))
        m
    } catch (_: Exception) {
        null
    }

    private fun deJson(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val m = LinkedHashMap<String, Any?>()
            for (k in v.keys()) m[k] = deJson(v.opt(k))
            m
        }
        is JSONArray -> (0 until v.length()).map { deJson(v.opt(it)) }
        else -> v
    }

    /**
     * Extraire {clé: valeur} d'un champ libre — decode_eval du python. Map vide si aucun bloc.
     * Accepte le bloc JSON comme l'ancien `clé=valeur`. Les valeurs renvoyées sont DÉJÀ
     * normalisées (alias convertis, valeurs hors référentiel écartées) : rien à retraiter.
     */
    fun decoder(texte: String?): Map<String, Any> {
        val raw = blocBrut(texte) ?: return emptyMap()
        val data = parserJsonObjet(raw) ?: parserLegacy(raw)
        val result = LinkedHashMap<String, Any>()
        for ((k, v) in data) nettoyer(k, v)?.let { result[k] = it }
        return result
    }

    /**
     * Insérer/mettre à jour le bloc SANS écraser le texte libre — encode_eval du python.
     * Chaque valeur est validée ([nettoyer]) : clé inconnue, vide ou hors référentiel non
     * écrite. Clés triées : deux enregistrements d'une même saisie produisent le même texte.
     */
    fun encoder(texte: String?, valeurs: Map<String, Any?>): String {
        // texteHumain retire les blocs complets ; sansBalises, les balises orphelines qui
        // feraient dérailler la prochaine relecture.
        val humain = sansBalises(texteHumain(texte)).trim()
        val data = sortedMapOf<String, Any>()
        for ((k, v) in valeurs) nettoyer(k, v)?.let { data[k] = it }
        if (data.isEmpty()) return humain // rien à encoder → seul le texte humain subsiste.
        val bloc = "$EVAL_START ${jsonCanonique(data)} $EVAL_END"
        return if (humain.isEmpty()) bloc else "$humain\n\n$bloc"
    }

    /** Mettre à jour CERTAINES clés du bloc en conservant les autres — merge_eval du python.
     *  Une valeur null SUPPRIME la clé. */
    fun fusionner(texte: String?, valeurs: Map<String, Any?>): String {
        val data = decoder(texte).toMutableMap()
        for ((k, v) in valeurs) {
            if (v == null) data.remove(k) else data[k] = v
        }
        return encoder(texte, data)
    }

    // ── Validation par clé (_clean du python) — utilisée dans les DEUX sens. ──────────────────

    /** Valeur normalisée à écrire pour [cle], ou null si rien à écrire. */
    fun nettoyer(cle: String, valeur: Any?): Any? {
        if (cle == "zone_humide" && valeur is Boolean) {
            // Ancien format : case à cocher. True vaut « oui » ; False ne disait pas « non »,
            // seulement « pas coché » — donc rien.
            return if (valeur) "oui" else null
        }
        CODE_FIELDS[cle]?.let { (valides, alias) ->
            val brut = valeur as? String ?: return null
            val code = alias[brut] ?: brut
            return if (code in valides) code else null
        }
        if (cle == "recouvrement") return validerRecouvrement(valeur)
        INT_FIELDS[cle]?.let { (mini, maxi) ->
            if (valeur is Boolean) return null
            val nombre = when (valeur) {
                is Number -> valeur.toDouble()
                is String -> valeur.trim().toDoubleOrNull() ?: return null
                else -> return null
            }.toInt() // int(float(value)) du python : tronque.
            return if (nombre in mini..maxi) nombre else null
        }
        if (cle in TEXT_FIELDS) return texteValeur(valeur)
        LIST_FIELDS[cle]?.let { max ->
            val brute: List<Any?> = when (valeur) {
                is String -> listOf(valeur) // une valeur seule vaut une liste d'un élément.
                is List<*> -> valeur
                null -> emptyList()
                else -> return null
            }
            val items = brute.filterNotNull()
                .map { sansBalises(it.toString()).trim() }
                .filter { it.isNotEmpty() }
                .take(max)
            return items.ifEmpty { null }
        }
        if (cle == "determination") return nettoyerDetermination(valeur)
        if (cle == "corresp") return nettoyerCorresp(valeur)
        return null // clé inconnue : ignorée, le bloc reste normalisé.
    }

    /** Texte propre pour le bloc, ou null. Les balises sont retirées. */
    private fun texteValeur(valeur: Any?): String? =
        (valeur as? String)?.let { sansBalises(it).trim().ifEmpty { null } }

    /** Normaliser un pourcentage de recouvrement (0 < v ≤ 100) : Int si entier, Double sinon. */
    private fun validerRecouvrement(valeur: Any?): Any? {
        if (valeur == null || valeur is Boolean) return null
        val v = when (valeur) {
            is Number -> valeur.toDouble()
            is String -> valeur.trim().toDoubleOrNull() ?: return null
            else -> return null
        }
        if (!(v > 0 && v <= 100)) return null
        return if (v == v.toInt().toDouble()) v.toInt() else v
    }

    /** {'nom': …, 'ancre': …} — le nom fait foi, l'ancre est facultative (typologie connue). */
    private fun nettoyerDetermination(valeur: Any?): Map<String, String>? {
        if (valeur !is Map<*, *>) return null
        val nom = texteValeur(valeur["nom"]) ?: return null // sans nom, ne dit rien.
        val propre = LinkedHashMap<String, String>()
        propre["nom"] = nom
        val ancre = valeur["ancre"]
        if (ancre is String && ancre in TYPOLOGIES) propre["ancre"] = ancre
        return propre
    }

    /** {typologie: {'cd_hab': int, 'code'?: str, 'src'?: str}} — validé typologie par typologie.
     *  Le LIBELLÉ n'est jamais réécrit (il faisait déborder les 500 caractères du champ) ; le
     *  CODE reste accepté (parfois seule copie quand le catalogue ignore un cd_hab). */
    private fun nettoyerCorresp(valeur: Any?): Map<String, Map<String, Any>>? {
        if (valeur !is Map<*, *>) return null
        val propre = LinkedHashMap<String, Map<String, Any>>()
        for ((typologie, detail) in valeur) {
            if (typologie !is String || typologie !in TYPOLOGIES || detail !is Map<*, *>) continue
            val brut = detail["cd_hab"]
            if (brut is Boolean) continue
            val cdHab = when (brut) {
                is Number -> brut.toInt()
                is String -> brut.trim().toIntOrNull()
                else -> null
            } ?: continue
            if (cdHab <= 0) continue // c'est le cd_hab qui fait la correspondance.
            val entree = LinkedHashMap<String, Any>()
            entree["cd_hab"] = cdHab
            texteValeur(detail["code"])?.let { entree["code"] = it }
            val src = detail["src"]
            if (src is String && src in SOURCES_CORRESPONDANCE) entree["src"] = src
            propre[typologie] = entree
        }
        return propre.ifEmpty { null }
    }

    // ── Sérialisation canonique (json.dumps(sort_keys=True) du python). ───────────────────────

    /** JSON déterministe : clés triées récursivement — l'empreinte serveur ne change pas entre
     *  deux écritures d'une même saisie (sinon conflit détecté à tort). */
    private fun jsonCanonique(valeur: Any?): String = when (valeur) {
        null -> "null"
        is Map<*, *> -> valeur.entries
            .sortedBy { it.key.toString() }
            .joinToString(", ", "{", "}") { (k, v) ->
                "${JSONObject.quote(k.toString())}: ${jsonCanonique(v)}"
            }
        is List<*> -> valeur.joinToString(", ", "[", "]") { jsonCanonique(it) }
        is String -> JSONObject.quote(valeur)
        is Boolean -> valeur.toString()
        is Double -> JSONObject.numberToString(valeur)
        is Number -> valeur.toString()
        else -> JSONObject.quote(valeur.toString())
    }

    // ── Pont avec le modèle GeoMys (anaEvalJson). ─────────────────────────────────────────────
    // Le bloc est EXTRAIT de comment/technical_precision au parsing serveur (OccHabApi) — le
    // texte stocké redevient purement humain — et RE-FUSIONNÉ à l'envoi (OccHabUpload). Il
    // survit ainsi au flux des détails de session, qui réécrit `comment` à chaque sauvegarde.

    /** Contenu JSON NORMALISÉ du bloc porté par [texte] (les deux formats acceptés), ou null si
     *  aucun bloc EXPLOITABLE — bloc absent, illisible ou vide : le texte reste alors traité en
     *  standard, strictement inchangé. */
    fun extraireBlocJson(texte: String?): String? = versJson(decoder(texte))

    /** JSON canonique (clés triées, valeurs re-validées) d'une map de valeurs ; null si rien à
     *  écrire (le bloc disparaît alors du texte à l'envoi). */
    fun versJson(valeurs: Map<String, Any?>): String? {
        val data = sortedMapOf<String, Any>()
        for ((k, v) in valeurs) nettoyer(k, v)?.let { data[k] = it }
        return if (data.isEmpty()) null else jsonCanonique(data)
    }

    /** Map de valeurs (re-validées) depuis un `anaEvalJson` stocké ; vide si null/illisible. */
    fun depuisJson(json: String?): Map<String, Any> {
        if (json.isNullOrBlank()) return emptyMap()
        val data = parserJsonObjet(json) ?: return emptyMap()
        val result = LinkedHashMap<String, Any>()
        for ((k, v) in data) nettoyer(k, v)?.let { result[k] = it }
        return result
    }

    /** Texte à ENVOYER au serveur : la part humaine [texte] re-fusionnée avec le bloc
     *  [anaEvalJson]. INVARIANT : anaEvalJson null → comportement STANDARD strict, le texte
     *  repart TEL QUEL (null s'il est vide — même contrat que `takeIf { isNotBlank() }`). */
    fun avecBloc(texte: String?, anaEvalJson: String?): String? {
        if (anaEvalJson == null) return texte?.takeIf { it.isNotBlank() }
        return encoder(texte, depuisJson(anaEvalJson)).takeIf { it.isNotBlank() }
    }

    /** Recouvrement (%) porté par le bloc, ou null. Champ DOUBLE de champs.py : à la relecture
     *  le bloc fait foi (repli sur la colonne native `recovery_percentage` sans bloc). */
    fun recouvrementDuBloc(anaEvalJson: String?): Double? =
        (depuisJson(anaEvalJson)["recouvrement"] as? Number)?.toDouble()

    /** Réaligne la clé `recouvrement` du bloc sur la valeur NATIVE (recovery_percentage) éditée
     *  dans le formulaire habitat — champ DOUBLE : `ecrire()` de champs.py pose la valeur aux
     *  deux endroits. La clé n'est JAMAIS ajoutée si le bloc ne la portait pas (la colonne
     *  suffit, lue en repli) ; elle est retirée si le natif a été effacé (la conserver la
     *  ressusciterait côté QGIS, où le bloc fait foi). */
    fun avecRecouvrementNatif(anaEvalJson: String?, natif: Double?): String? {
        if (anaEvalJson == null) return null
        val data = depuisJson(anaEvalJson).toMutableMap()
        if ("recouvrement" !in data) return anaEvalJson
        if (natif == null) data.remove("recouvrement") else data["recouvrement"] = natif
        return versJson(data)
    }
}
