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

/** Scanners « niveau 0 » partagés par [HiddenExpr] et [ChangeRules] : découpe et recherche
 *  d'opérateurs hors parenthèses/crochets/ACCOLADES/quotes. Factorisé pour que les deux
 *  moteurs restent rigoureusement alignés — ils avaient déjà divergé (ChangeRules comptait
 *  les accolades dans la profondeur, HiddenExpr non). */
internal object ExpressionScanner {

    /** Découpe [expr] sur [separateur] au niveau 0. Null si le séparateur n'apparaît pas
     *  au niveau 0 (les morceaux vides sont écartés). */
    fun decouperNiveauZero(expr: String, separateur: String): List<String>? {
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
                c == '(' || c == '[' || c == '{' -> profondeur++
                c == ')' || c == ']' || c == '}' -> profondeur--
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

    /** Position du premier [op] au niveau 0 à partir de [depuis], ou null. Garde-fous de
     *  collision pour les opérateurs de comparaison : `==` ne matche pas au milieu de
     *  `===`/`!==`/`<=`/`>=`, ni `>`/`<` le début de `>=`/`<=` — sans effet pour les cibles
     *  non ambiguës (`?`, `:`, `,`). L'appelant teste les opérateurs longs d'abord. */
    fun indexNiveauZero(expr: String, op: String, depuis: Int = 0): Int? {
        var profondeur = 0
        var quote: Char? = null
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' || c == '[' || c == '{' -> profondeur++
                c == ')' || c == ']' || c == '}' -> profondeur--
                profondeur == 0 && i >= depuis && expr.startsWith(op, i) -> {
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
    fun parenthesesEquilibrees(s: String): Boolean {
        var profondeur = 0
        for (c in s) {
            if (c == '(') profondeur++
            if (c == ')') { profondeur--; if (profondeur < 0) return false }
        }
        return profondeur == 0
    }
}