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

/** Évaluateur des expressions conditionnelles envoyées par gn_module_monitoring dans les
 *  clés `hidden`/`display`/`required` du schéma (lambdas JS `({value}) => …`). On ne peut
 *  pas évaluer du JS arbitraire côté Android — on couvre la grammaire observée sur
 *  l'ensemble des protocoles de l'instance (audit des 33 schémas, 2026-06) :
 *
 *  - champ seul / négation / parenthèses : `!value.x`, `!(value.x == 2)`
 *  - comparaisons : `==`, `===`, `!=`, `!==`, `>`, `<`, `>=`, `<=` (champ, littéral
 *    quoté, nombre, true/false, null de chaque côté)
 *  - combinaisons : `&&`, `||` (précédence JS : && lie plus fort)
 *  - `['a','b',null].includes(value.x)`
 *  - chemins imbriqués aplatis : `value.cd_nom.cd_nom` → valeur du champ `cd_nom`
 *    (les valeurs de formulaire côté app sont scalaires — le cd_nom du taxon, etc.)
 *
 *  Les expressions hors grammaire (typiquement `meta.nomenclatures[...]`) sont traitées
 *  comme `false` (champ visible / non requis) et tracées dans le log. La propagation est
 *  stricte : un sous-terme non reconnu rend l'expression entière non reconnue — on préfère
 *  montrer un champ en trop que d'en cacher un à tort.
 *
 *  Conventions :
 *  - "Truthy" pour un Boolean = `true`, pour une String = non vide et != "false"/"0",
 *    pour un Number ≠ 0, pour une Collection = non vide.
 *  - [masquer] retourne `true` quand le champ doit être **masqué** (sémantique `hidden`). */
object HiddenExpr {

    /** Évalue une expression booléenne quelconque du schéma contre les valeurs courantes —
     *  même grammaire que `hidden`, utilisée aussi pour le `required` dynamique
     *  (`({value}) => value.num_passage == 2`). Expression non reconnue → false
     *  (champ non requis : on ne bloque pas la saisie sur une grammaire inconnue). */
    fun evaluerBooleen(expression: String?, valeurs: Map<String, Any?>): Boolean =
        masquer(expression, valeurs)

    /** Évalue l'expression sur l'ensemble des valeurs courantes du formulaire.
     *  @return true → masquer le champ, false → l'afficher. */
    fun masquer(expression: String?, valeurs: Map<String, Any?>): Boolean {
        if (expression.isNullOrBlank()) return false
        val expr = normaliser(expression.trim())
        val resultat = evaluer(expr, valeurs)
        if (resultat == null) {
            android.util.Log.w("HiddenExpr",
                "Expression non reconnue, champ traité comme visible : $expression")
            return false
        }
        return resultat
    }

    /** Reconnaît plusieurs formats serveur et les ramène à `${champ}` :
     *  - Lambda fléchée : `({value}) => corps`, `({value, meta}) => corps`, `(value) => corps`
     *  - `function(value) { return corps; }`
     *  - Chemins imbriqués `value.a.b` → `${a}` (valeurs scalaires côté app)
     *  - Forme déjà standard `${...}` → inchangée. */
    internal fun normaliser(expr: String): String {
        var corps = expr
        // Extrait le corps des lambdas fléchées, y compris à paramètres destructurés
        // multiples : `({value}) =>`, `({value, meta}) =>`, `({meta, value}) =>`, `(value) =>`,
        // `value =>`. On supprime aussi le `return` final éventuel.
        val lambda = Regex(
            """^\s*(?:\(\s*\{[^}]*\}\s*\)|\(\s*value\s*\)|value)\s*=>\s*(.+?)\s*$""",
            RegexOption.DOT_MATCHES_ALL,
        ).matchEntire(expr)
        if (lambda != null) corps = lambda.groupValues[1]
        // Corps en bloc : `{ return X; }`
        Regex("""^\{\s*return\s+(.+?)\s*;?\s*\}$""", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(corps.trim())?.let { corps = it.groupValues[1] }
        // function(value) { return X; }
        Regex(
            """^\s*function\s*\(\s*\{?\s*value\s*\}?\s*\)\s*\{\s*return\s+(.+?)\s*;?\s*\}\s*$""",
            RegexOption.DOT_MATCHES_ALL,
        ).matchEntire(corps)?.let { corps = it.groupValues[1] }

        // Idiome de générateur de protocole : `(null || undefined)` ≡ null (petite chouette
        // de montagne : `value.cd_nom != (null || undefined) ? … : …`).
        corps = corps.replace(Regex("""\(\s*null\s*\|\|\s*undefined\s*\)"""), "null")
        // `meta.nomenclatures[value.X].cd_nomenclature` (avec ou sans le garde `|| {}`) →
        // `${X__cd}` : le cd_nomenclature de l'option sélectionnée du champ X, exposé par le
        // renderer (cf. FormulaireRenderer.valeursPourExpressions). Protocoles loutre /
        // blaireau / Camphi : sexe, stade de vie… visibles/requis selon la technique d'obs.
        // Doit passer AVANT les remplacements `value.X` génériques.
        corps = corps.replace(
            Regex("""\(\s*meta\.nomenclatures\[\s*value\.(\w+)\s*\]\s*\|\|\s*\{\s*\}\s*\)\s*\.cd_nomenclature"""),
        ) { "\${${it.groupValues[1]}__cd}" }
        corps = corps.replace(
            Regex("""meta\.nomenclatures\[\s*value\.(\w+)\s*\]\s*\.cd_nomenclature"""),
        ) { "\${${it.groupValues[1]}__cd}" }
        // Chemins imbriqués `value.cd_nom.cd_nom` → ${cd_nom} : les valeurs du formulaire
        // côté app sont scalaires (le champ taxon PORTE le cd_nom), là où le web manipule
        // un objet. Doit passer AVANT le remplacement simple.
        corps = corps.replace(Regex("""value\.(\w+)(?:\.\w+)+""")) { "\${${it.groupValues[1]}}" }
        // Remplace `value.champ` ou `value["champ"]` par `${champ}`.
        corps = corps.replace(Regex("""value\.(\w+)""")) { "\${${it.groupValues[1]}}" }
        corps = corps.replace(Regex("""value\[['"](\w+)['"]\]""")) { "\${${it.groupValues[1]}}" }
        return corps.trim()
    }

    /** Évaluation récursive d'une expression normalisée. Null = forme non reconnue. */
    private fun evaluer(exprBrut: String, valeurs: Map<String, Any?>): Boolean? {
        var expr = exprBrut.trim()
        if (expr.isEmpty()) return null
        // Parenthèses englobantes : `(X)` → X, répété tant qu'elles enveloppent TOUT le corps.
        while (expr.length >= 2 && expr.startsWith("(") && expr.endsWith(")") &&
            parenthesesEquilibrees(expr.substring(1, expr.length - 1))
        ) {
            expr = expr.substring(1, expr.length - 1).trim()
        }

        // Ternaire `cond ? a : b` — précédence la plus basse en JS, donc testé en premier
        // (petite chouette de montagne : `value.cd_nom != null ? value.cd_nom != 3507 : true`).
        val idxQuestion = indexOperateurNiveauZero(expr, "?")
        if (idxQuestion != null) {
            val idxDeuxPoints = indexOperateurNiveauZero(expr, ":", depuis = idxQuestion + 1)
            if (idxDeuxPoints != null) {
                val condition = evaluer(expr.substring(0, idxQuestion), valeurs) ?: return null
                val branche = if (condition) expr.substring(idxQuestion + 1, idxDeuxPoints)
                else expr.substring(idxDeuxPoints + 1)
                return evaluer(branche, valeurs)
            }
        }

        // `||` puis `&&` (précédence JS : && lie plus fort que ||, donc on scinde sur ||
        // d'abord). Découpe au niveau 0 uniquement (hors parenthèses/crochets/quotes).
        decouperNiveauZero(expr, "||")?.let { parties ->
            val evals = parties.map { evaluer(it, valeurs) ?: return null }
            return evals.any { it }
        }
        decouperNiveauZero(expr, "&&")?.let { parties ->
            val evals = parties.map { evaluer(it, valeurs) ?: return null }
            return evals.all { it }
        }

        // Négation `!X` (et `not X`).
        if (expr.startsWith("!") && !expr.startsWith("!=")) {
            return evaluer(expr.substring(1), valeurs)?.let { !it }
        }
        Regex("""^not\s+(.+)$""", RegexOption.IGNORE_CASE).matchEntire(expr)?.let { m ->
            return evaluer(m.groupValues[1], valeurs)?.let { !it }
        }

        // `[litt, litt, …].includes(${champ})` — items quotés, numériques ou null. NB : un
        // item `null` matche un champ vide (les contrôles Angular non remplis valent null,
        // c'est l'idiome utilisé par les protocoles pour « caché tant que non choisi »).
        Regex("""^\[(.*)\]\s*\.includes\(\s*\$\{(\w+)\}\s*\)$""").matchEntire(expr)?.let { m ->
            val brutItems = m.groupValues[1]
            val morceaux = decouperNiveauZero(brutItems, ",") ?: listOf(brutItems)
            val items = morceaux.map { resoudreOperande(it.trim(), valeurs) ?: return null }
            val v = valeurs[m.groupValues[2]]
            return items.any { item ->
                when {
                    item.valeur == null -> v == null || v.toString().isEmpty()
                    else -> egalite(Operande(v, true), item)
                }
            }
        }

        // Comparaison binaire au niveau 0 : ===, !==, ==, !=, >=, <=, >, <.
        for (op in listOf("===", "!==", "==", "!=", ">=", "<=", ">", "<")) {
            val idx = indexOperateurNiveauZero(expr, op) ?: continue
            val gauche = resoudreOperande(expr.substring(0, idx).trim(), valeurs) ?: return null
            val droite = resoudreOperande(expr.substring(idx + op.length).trim(), valeurs) ?: return null
            return when (op) {
                "===", "==" -> egalite(gauche, droite)
                "!==", "!=" -> !egalite(gauche, droite)
                else -> {
                    val g = gauche.valeur?.toString()?.toDoubleOrNull() ?: return null
                    val d = droite.valeur?.toString()?.toDoubleOrNull() ?: return null
                    when (op) {
                        ">=" -> g >= d
                        "<=" -> g <= d
                        ">" -> g > d
                        else -> g < d
                    }
                }
            }
        }

        // Termes simples : `${champ}` (truthiness), littéraux booléens.
        Regex("""^\$\{(\w+)\}$""").matchEntire(expr)?.let { m ->
            return truthy(valeurs[m.groupValues[1]])
        }
        if (expr.equals("true", ignoreCase = true)) return true
        if (expr.equals("false", ignoreCase = true)) return false

        return null
    }

    /** Opérande résolu : valeur + marqueur « reconnu ». */
    private data class Operande(val valeur: Any?, val connu: Boolean)

    /** `${champ}` → valeur du formulaire ; `'litt'`/"litt" → String ; nombre → Double ;
     *  true/false/null → littéraux. Null (Kotlin) = non reconnu. */
    private fun resoudreOperande(brut: String, valeurs: Map<String, Any?>): Operande? {
        val s = brut.trim()
        Regex("""^\$\{(\w+)\}$""").matchEntire(s)?.let { return Operande(valeurs[it.groupValues[1]], true) }
        Regex("""^'([^']*)'$""").matchEntire(s)?.let { return Operande(it.groupValues[1], true) }
        Regex("""^"([^"]*)"$""").matchEntire(s)?.let { return Operande(it.groupValues[1], true) }
        s.toDoubleOrNull()?.let { return Operande(it, true) }
        if (s == "null" || s == "undefined") return Operande(null, true)
        if (s.equals("true", ignoreCase = true)) return Operande(true, true)
        if (s.equals("false", ignoreCase = true)) return Operande(false, true)
        return null
    }

    /** Égalité « à la JS == » : numérique quand les deux côtés sont numérisables
     *  ("2" == 2.0), sinon comparaison de chaînes ; null ne vaut que null/"". */
    private fun egalite(a: Operande, b: Operande): Boolean {
        val va = a.valeur
        val vb = b.valeur
        if (va == null || vb == null) {
            return (va == null || va.toString().isEmpty()) && (vb == null || vb.toString().isEmpty())
        }
        val na = va.toString().toDoubleOrNull()
        val nb = vb.toString().toDoubleOrNull()
        if (na != null && nb != null) return na == nb
        return va.toString() == vb.toString()
    }

    /** Découpe [expr] sur [separateur] au niveau 0 (hors parenthèses, crochets et quotes).
     *  Retourne null si le séparateur n'apparaît pas au niveau 0. */
    private fun decouperNiveauZero(expr: String, separateur: String): List<String>? {
        val parties = mutableListOf<String>()
        var profondeur = 0
        var quote: Char? = null
        var debut = 0
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' || c == '[' -> profondeur++
                c == ')' || c == ']' -> profondeur--
                profondeur == 0 && expr.startsWith(separateur, i) -> {
                    parties.add(expr.substring(debut, i))
                    i += separateur.length
                    debut = i
                    continue
                }
            }
            i++
        }
        if (parties.isEmpty()) return null
        parties.add(expr.substring(debut))
        return parties.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Position du premier [op] au niveau 0 à partir de [depuis], ou null. Évite de
     *  confondre `>` avec `>=` et `!` de `!=` avec une négation : l'appelant teste les
     *  opérateurs longs d'abord. */
    private fun indexOperateurNiveauZero(expr: String, op: String, depuis: Int = 0): Int? {
        var profondeur = 0
        var quote: Char? = null
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' || c == '[' -> profondeur++
                c == ')' || c == ']' -> profondeur--
                profondeur == 0 && i >= depuis && expr.startsWith(op, i) -> {
                    // `==` ne doit pas matcher au milieu de `===`/`!==`, ni `>` celui de `>=`.
                    val avant = expr.getOrNull(i - 1)
                    val apres = expr.getOrNull(i + op.length)
                    val collisionAvant = (op == "==" && (avant == '=' || avant == '!' || avant == '<' || avant == '>'))
                    val collisionApres = ((op == "==" || op == ">" || op == "<") && apres == '=')
                    if (!collisionAvant && !collisionApres) return i
                }
            }
            i++
        }
        return null
    }

    /** Les parenthèses de [s] s'équilibrent-elles sans jamais passer en négatif ? */
    private fun parenthesesEquilibrees(s: String): Boolean {
        var profondeur = 0
        for (c in s) {
            if (c == '(') profondeur++
            if (c == ')') { profondeur--; if (profondeur < 0) return false }
        }
        return profondeur == 0
    }

    /** Sémantique JS-like de la vérité : `false`, `""`, `0`, `null`, listes vides = falsy. */
    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && !v.equals("false", ignoreCase = true) && v != "0"
        is Collection<*> -> v.isNotEmpty()
        else -> true
    }
}