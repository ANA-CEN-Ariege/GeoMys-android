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

package fr.ariegenature.geomys.monitoring.form

/** Moteur des règles `change` de gn_module_monitoring (auto-remplissage de champs
 *  dépendants). Le serveur les envoie sous forme d'un script JS en tableau de lignes :
 *
 *  ```js
 *  ({objForm, meta}) => {
 *    const nb_total = (objForm.value.nb_before_rep + objForm.value.nb_repasse);
 *    objForm.patchValue({nb_total});
 *    if (objForm.value.presence === 'Non') {
 *      objForm.patchValue({count_min : 0, count_max : 0}, {emitEvent : false})
 *    }
 *    (objForm.value.hulotte != 'Oui' ? objForm.patchValue({nb_hulotte}) : '');
 *  }
 *  ```
 *
 *  On ne peut pas exécuter du JS arbitraire côté Android — on couvre la grammaire observée
 *  sur l'ensemble des protocoles de l'instance (audit des 33 schémas, 2026-06) :
 *
 *  - `const NOM = expr;` : littéraux, `value.x`, sommes/concaténations `+`, ternaires
 *  - `if (COND) { objForm.patchValue({…}) }` : condition déléguée à [HiddenExpr]
 *    (comparaisons, &&/||, !!, meta.nomenclatures[…].cd_nomenclature)
 *  - ternaire-instruction : `(COND ? objForm.patchValue({…}) : '');`
 *  - garde « dirty » : `if (!objForm.controls.X.dirty)` — réécrite en `value.X__dirty`,
 *    drapeau fourni par le renderer (champ modifié par l'utilisateur → on ne l'écrase plus)
 *  - valeurs de patch : littéraux, identifiants `const`, `value.x`, expressions `+`,
 *    raccourci `{nb_total}`, objet taxon `{cd_nom: 914450, …}` (réduit à son cd_nom —
 *    les valeurs de formulaire côté app sont scalaires)
 *
 *  Les instructions hors grammaire sont ignorées (pas de patch erroné). */
object ChangeRules {

    /** Une instruction du script, dans l'ordre du source. */
    sealed interface Instruction

    /** `const NOM = expr;` — évaluée à l'exécution, stockée dans l'environnement local. */
    data class Affectation(val nom: String, val expression: String) : Instruction

    /** `objForm.patchValue({…})` gardé par [condition] (null = inconditionnel). Les valeurs
     *  du patch sont des expressions brutes, évaluées à l'exécution. */
    data class Patch(val condition: String?, val patch: Map<String, String>) : Instruction

    /** Type public opaque (compat renderer/tests) : une instruction ordonnée du script. */
    data class Regle(val instruction: Instruction)

    /** Parse les lignes brutes en instructions ordonnées. */
    fun parser(lignes: List<String>): List<Regle> {
        if (lignes.isEmpty()) return emptyList()
        var script = lignes.joinToString("\n").trim()
        // Déballe la lambda englobante `({objForm, meta}) => { … }` si présente.
        Regex("""^\(\s*\{[^}]*\}\s*\)\s*=>\s*\{(.*)\}$""", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(script)?.let { script = it.groupValues[1] }
        // Normalisations : `objForm.value.X` → `value.X` ; `objForm.controls.X.dirty` →
        // `value.X__dirty` (drapeau « modifié par l'utilisateur » fourni par le renderer).
        script = script.replace("objForm.value.", "value.")
        script = script.replace(Regex("""objForm\.controls\.(\w+)\.dirty""")) {
            "value.${it.groupValues[1]}__dirty"
        }

        val instructions = mutableListOf<Regle>()
        var conditionBloc: String? = null
        for (brute in script.lines()) {
            val ligne = brute.trim().trimEnd(';').trim()
            if (ligne.isEmpty()) continue
            // `const NOM = EXPR`
            Regex("""^const\s+(\w+)\s*=\s*(.+)$""").matchEntire(ligne)?.let { m ->
                instructions.add(Regle(Affectation(m.groupValues[1], m.groupValues[2].trim())))
                null
            }?.let { continue }
            // `if (COND) {` : ouvre un bloc conditionnel.
            if (ligne.startsWith("if") && ligne.contains('(')) {
                conditionBloc = ligne.substringAfter('(').substringBeforeLast(')').trim()
                    .removeSuffix("{").trim().ifEmpty { null }
                continue
            }
            // `objForm.patchValue({…})` — éventuellement en ternaire-instruction
            // `(COND ? objForm.patchValue({…}) : '')`.
            val idxPatch = ligne.indexOf("patchValue")
            if (idxPatch >= 0) {
                val corps = extraireObjetEquilibre(ligne, ligne.indexOf('(', idxPatch)) ?: continue
                val conditionInline = conditionTernaireInstruction(ligne, idxPatch)
                val condition = conditionInline ?: conditionBloc
                val patch = parserPatch(corps)
                if (patch.isNotEmpty()) instructions.add(Regle(Patch(condition, patch)))
                continue
            }
            // Fin de bloc `}` → retour au niveau inconditionnel.
            if (ligne == "}" || ligne.endsWith("}")) conditionBloc = null
        }
        return instructions
    }

    /** Pour une ligne `(COND ? objForm.patchValue({…}) : …)`, extrait COND ; null sinon. */
    private fun conditionTernaireInstruction(ligne: String, idxPatch: Int): String? {
        var l = ligne
        // Retire les parenthèses englobantes éventuelles.
        while (l.length >= 2 && l.startsWith("(") && l.endsWith(")") && ExpressionScanner.parenthesesEquilibrees(l.substring(1, l.length - 1))) {
            l = l.substring(1, l.length - 1).trim()
        }
        val idxQ = ExpressionScanner.indexNiveauZero(l, "?") ?: return null
        if (idxQ > l.indexOf("patchValue").takeIf { it >= 0 }!!) return null  // `?` après le patch → pas un ternaire-garde
        return l.substring(0, idxQ).trim().ifEmpty { null }
    }

    /** Extrait le corps du premier objet `{…}` ÉQUILIBRÉ à partir de [depuis] (gère les
     *  objets imbriqués type taxon et les quotes). Retourne l'intérieur sans les accolades. */
    private fun extraireObjetEquilibre(texte: String, depuis: Int): String? {
        if (depuis < 0) return null
        val debut = texte.indexOf('{', depuis).takeIf { it >= 0 } ?: return null
        var profondeur = 0
        var quote: Char? = null
        for (i in debut until texte.length) {
            val c = texte[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '{' -> profondeur++
                c == '}' -> {
                    profondeur--
                    if (profondeur == 0) return texte.substring(debut + 1, i)
                }
            }
        }
        return null
    }

    /** Parse le corps d'un objet patch en Map code → EXPRESSION brute. Gère le raccourci
     *  `{nb_total}` (clé = expression = identifiant) et les valeurs imbriquées. */
    private fun parserPatch(corps: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (corps.isBlank()) return out
        for (paire in ExpressionScanner.decouperNiveauZero(corps, ",") ?: listOf(corps)) {
            val idx = ExpressionScanner.indexNiveauZero(paire, ":")
            if (idx == null) {
                // Raccourci ES6 `{nb_total}` : la valeur est la variable du même nom.
                val nom = paire.trim().trim('\'', '"')
                if (Regex("""^\w+$""").matches(nom)) out[nom] = nom
                continue
            }
            val cle = paire.take(idx).trim().trim('\'', '"')
            if (cle.isNotEmpty()) out[cle] = paire.substring(idx + 1).trim()
        }
        return out
    }

    /** Évalue toutes les instructions contre les [valeurs] courantes (qui peuvent inclure les
     *  clés enrichies `X__cd` / `X__dirty` du renderer) et renvoie les champs à mettre à jour.
     *  Les patchs précédents sont visibles des conditions suivantes (parité patchValue web). */
    fun evaluer(regles: List<Regle>, valeurs: Map<String, Any?>): Map<String, Any?> {
        if (regles.isEmpty()) return emptyMap()
        val env = mutableMapOf<String, Any?>()
        val maj = linkedMapOf<String, Any?>()
        fun contexte(): Map<String, Any?> = valeurs + maj
        for (regle in regles) {
            when (val ins = regle.instruction) {
                is Affectation -> env[ins.nom] = evaluerValeur(ins.expression, contexte(), env)
                is Patch -> {
                    val ok = ins.condition == null ||
                        HiddenExpr.evaluerBooleen(ins.condition, contexte())
                    if (ok) ins.patch.forEach { (code, expr) ->
                        maj[code] = evaluerValeur(expr, contexte(), env)
                    }
                }
            }
        }
        // Les clés enrichies ne sont pas des champs : on ne les patche jamais.
        return maj.filterKeys { !it.endsWith("__cd") && !it.endsWith("__dirty") }
    }

    /** Évalue une expression de VALEUR : littéraux, `value.x`, identifiant d'environnement,
     *  somme/concaténation `+` (numérique si tous les termes le sont, concat sinon — null
     *  neutre), ternaire, objet taxon réduit à son cd_nom. Null si hors grammaire. */
    private fun evaluerValeur(brut: String, lecture: Map<String, Any?>, env: Map<String, Any?>): Any? {
        var e = brut.trim().trimEnd(';').trim()
        while (e.length >= 2 && e.startsWith("(") && e.endsWith(")") && ExpressionScanner.parenthesesEquilibrees(e.substring(1, e.length - 1))) {
            e = e.substring(1, e.length - 1).trim()
        }
        if (e.isEmpty()) return null

        // Ternaire valeur : `cond ? a : b`.
        ExpressionScanner.indexNiveauZero(e, "?")?.let { idxQ ->
            ExpressionScanner.indexNiveauZero(e, ":", depuis = idxQ + 1)?.let { idxC ->
                val condition = HiddenExpr.evaluerBooleen(e.substring(0, idxQ), lecture)
                val branche = if (condition) e.substring(idxQ + 1, idxC) else e.substring(idxC + 1)
                return evaluerValeur(branche, lecture, env)
            }
        }

        // Somme / concaténation `+` : numérique si tous les termes le sont, concat sinon.
        ExpressionScanner.decouperNiveauZero(e, "+")?.let { parties ->
            val termes = parties.map { evaluerValeur(it, lecture, env) }
            val nombres = termes.map { it?.toString()?.toDoubleOrNull() ?: if (it == null) 0.0 else null }
            if (nombres.none { it == null }) {
                val somme = nombres.filterNotNull().sum()
                return if (somme % 1.0 == 0.0) somme.toInt() else somme
            }
            return termes.joinToString("") { t ->
                when (t) {
                    null -> ""
                    is Double -> if (t % 1.0 == 0.0) t.toInt().toString() else t.toString()
                    else -> t.toString()
                }
            }
        }

        // Littéraux.
        if (e == "null" || e == "undefined") return null
        if (e.equals("true", ignoreCase = true)) return true
        if (e.equals("false", ignoreCase = true)) return false
        Regex("""^'([^']*)'$""").matchEntire(e)?.let { return it.groupValues[1] }
        Regex("""^"([^"]*)"$""").matchEntire(e)?.let { return it.groupValues[1] }
        e.toIntOrNull()?.let { return it }
        e.toDoubleOrNull()?.let { return it }

        // `value.x` (chemins imbriqués aplatis : `value.a.b` → champ a).
        Regex("""^value\.(\w+)(?:\.\w+)*$""").matchEntire(e)?.let { return lecture[it.groupValues[1]] }

        // Objet `{…}` : cas taxon — la valeur utile côté app est le cd_nom scalaire.
        if (e.startsWith("{") && e.endsWith("}")) {
            val corps = e.substring(1, e.length - 1)
            for (paire in ExpressionScanner.decouperNiveauZero(corps, ",") ?: listOf(corps)) {
                val idx = ExpressionScanner.indexNiveauZero(paire, ":") ?: continue
                val cle = paire.take(idx).trim().trim('\'', '"')
                if (cle == "cd_nom") return evaluerValeur(paire.substring(idx + 1), lecture, env)
            }
            return null
        }

        // Identifiant d'environnement (const précédente).
        if (Regex("""^\w+$""").matches(e)) return env[e]

        return null
    }

}
