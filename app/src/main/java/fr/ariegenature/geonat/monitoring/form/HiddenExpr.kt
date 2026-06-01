/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat.monitoring.form

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

    /** Évalue l'expression sur l'ensemble des valeurs courantes du formulaire.
     *  @return true → masquer le champ, false → l'afficher. */
    fun masquer(expression: String?, valeurs: Map<String, Any?>): Boolean {
        if (expression.isNullOrBlank()) return false
        // Normalisation : gn_module_monitoring envoie des lambdas JS du genre
        // `({value}) => !value.habitat_input` — on extrait la partie après `=>` et on
        // remplace `value.champ` par `${champ}` pour la traiter avec les patterns standard.
        val expr = normaliser(expression.trim())

        // Pattern : `!${champ}` ou `not ${champ}` → masque si champ est falsy.
        Regex("""^!\s*\$\{(\w+)\}$""").matchEntire(expr)?.let { m ->
            return !truthy(valeurs[m.groupValues[1]])
        }
        Regex("""^not\s+\$\{(\w+)\}$""", RegexOption.IGNORE_CASE).matchEntire(expr)?.let { m ->
            return !truthy(valeurs[m.groupValues[1]])
        }

        // Pattern : `${champ}` seul → masque si champ est truthy.
        Regex("""^\$\{(\w+)\}$""").matchEntire(expr)?.let { m ->
            return truthy(valeurs[m.groupValues[1]])
        }

        // Pattern : `${champ} ==|=== 'val'` → masque si champ vaut val.
        Regex("""^\$\{(\w+)\}\s*={2,3}\s*['"]?([^'"]*)['"]?$""").matchEntire(expr)?.let { m ->
            val v = valeurs[m.groupValues[1]]?.toString() ?: ""
            return v == m.groupValues[2]
        }

        // Pattern : `${champ} !=|!== 'val'` → masque si champ DIFFÈRE de val.
        Regex("""^\$\{(\w+)\}\s*!=={0,1}\s*['"]?([^'"]*)['"]?$""").matchEntire(expr)?.let { m ->
            val v = valeurs[m.groupValues[1]]?.toString() ?: ""
            return v != m.groupValues[2]
        }

        android.util.Log.w("HiddenExpr",
            "Expression non reconnue, champ traité comme visible : $expression")
        return false
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
