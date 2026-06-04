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

/** Évaluateur minimaliste des expressions d'affichage conditionnel envoyées par
 *  gn_module_monitoring dans la clé `hidden`/`display` du schéma. Le serveur utilise une
 *  syntaxe Angular template (`${champ}`, `${champ} === 'val'`, etc.). On ne peut pas
 *  évaluer du JS arbitraire côté Android — on couvre les patterns observés sur les
 *  protocoles courants. Les expressions non reconnues sont traitées comme `false`
 *  (champ visible) et tracées dans le log pour adaptation ultérieure.
 *
 *  Conventions :
 *  - "Truthy" pour un Boolean = `true`, pour une String = non vide et != "false"/"0",
 *    pour un Number ≠ 0, pour une Collection = non vide.
 *  - Retourne `true` quand le champ doit être **masqué** (sémantique de la clé `hidden`). */
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
        // Normalisation : gn_module_monitoring envoie des lambdas JS du genre
        // `({value}) => !value.habitat_input` — on extrait la partie après `=>` et on
        // remplace `value.champ` par `${champ}` pour la traiter avec les patterns standard.
        val expr = normaliser(expression.trim())
        val resultat = evaluer(expr, valeurs)
        if (resultat == null) {
            android.util.Log.w("HiddenExpr",
                "Expression non reconnue, champ traité comme visible : $expression")
            return false
        }
        return resultat
    }

    /** Évaluation récursive d'une expression normalisée (`${champ}`, comparaisons, négation,
     *  parenthèses englobantes — ex. `!(${num_passage} == 2)`, schéma Point écoute avifaune).
     *  Retourne null quand la forme n'est pas reconnue (l'appelant affichera le champ). */
    private fun evaluer(exprBrut: String, valeurs: Map<String, Any?>): Boolean? {
        var expr = exprBrut.trim()
        // Parenthèses englobantes : `(X)` → X, répété tant qu'elles enveloppent TOUT le corps
        // (le contrôle d'équilibre évite de casser `(${a} == 1) && (${b} == 2)`).
        while (expr.length >= 2 && expr.startsWith("(") && expr.endsWith(")") &&
            parenthesesEquilibrees(expr.substring(1, expr.length - 1))
        ) {
            expr = expr.substring(1, expr.length - 1).trim()
        }

        // Négation `!X` (et `not X`) → inverse de l'évaluation interne.
        if (expr.startsWith("!") && !expr.startsWith("!=")) {
            return evaluer(expr.substring(1), valeurs)?.let { !it }
        }
        Regex("""^not\s+(.+)$""", RegexOption.IGNORE_CASE).matchEntire(expr)?.let { m ->
            return evaluer(m.groupValues[1], valeurs)?.let { !it }
        }

        // Pattern : `${champ} ==|=== 'val'` → vrai si champ vaut val.
        Regex("""^\$\{(\w+)\}\s*={2,3}\s*['"]?([^'"]*)['"]?$""").matchEntire(expr)?.let { m ->
            val v = valeurs[m.groupValues[1]]?.toString() ?: ""
            return v == m.groupValues[2]
        }

        // Pattern : `${champ} !=|!== 'val'` → vrai si champ DIFFÈRE de val.
        Regex("""^\$\{(\w+)\}\s*!=={0,1}\s*['"]?([^'"]*)['"]?$""").matchEntire(expr)?.let { m ->
            val v = valeurs[m.groupValues[1]]?.toString() ?: ""
            return v != m.groupValues[2]
        }

        // Pattern : `${champ}` seul → truthiness du champ.
        Regex("""^\$\{(\w+)\}$""").matchEntire(expr)?.let { m ->
            return truthy(valeurs[m.groupValues[1]])
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

    /** Reconnaît plusieurs formats serveur et les ramène à `${champ}` :
     *  - Lambda fléchée : `({value}) => corps`  → corps avec value.X → ${X}
     *  - `(value) => corps`                    → idem
     *  - `function(value) { return corps; }`    → idem
     *  - Forme déjà standard `${...}`           → inchangée. */
    internal fun normaliser(expr: String): String {
        var corps = expr
        // Extrait le corps des lambdas fléchées : `({value}) => corps`, `(value) => corps`,
        // `value => corps`. On supprime aussi le `return` final éventuel.
        val lambda = Regex(
            """^\s*(?:\(\s*\{?\s*value\s*\}?\s*\)|value)\s*=>\s*(.+?)\s*$""",
            RegexOption.DOT_MATCHES_ALL,
        ).matchEntire(expr)
        if (lambda != null) corps = lambda.groupValues[1]
        // function(value) { return X; }
        Regex(
            """^\s*function\s*\(\s*\{?\s*value\s*\}?\s*\)\s*\{\s*return\s+(.+?)\s*;?\s*\}\s*$""",
            RegexOption.DOT_MATCHES_ALL,
        ).matchEntire(corps)?.let { corps = it.groupValues[1] }

        // Remplace `value.champ` (déstructuration) ou `value["champ"]` par `${champ}`.
        corps = corps.replace(Regex("""value\.(\w+)""")) { "\${${it.groupValues[1]}}" }
        corps = corps.replace(Regex("""value\[['"](\w+)['"]\]""")) { "\${${it.groupValues[1]}}" }
        return corps.trim()
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
