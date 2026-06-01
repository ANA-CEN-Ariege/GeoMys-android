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

/** Évaluateur pragmatique des bornes numériques `min`/`max` envoyées par
 *  gn_module_monitoring. Inspiré de `_resolveNumericBound` côté gn_mobile_monitoring Flutter
 *  ([dynamic_form_builder.dart:1287]) : on accepte trois formes pour une borne :
 *
 *  - un littéral numérique (`0`, `100`, `3.14`) ;
 *  - une string parsable (`"0"`, `"3.14"`) ;
 *  - une lambda JS qui pointe vers un autre champ du formulaire :
 *    `({value}) => value.<champ>` ou `(value) => value.<champ>` ou `value.<champ>`.
 *
 *  Comme [HiddenExpr] et [ChangeRules], on ne tente PAS d'évaluer du JS arbitraire — les
 *  expressions non reconnues retournent null (= "borne absente", pas de contrainte).
 *
 *  Cas d'usage typique : un couple `count_min` / `count_max` où le schéma déclare
 *  `count_min.max = "({value}) => value.count_max"` pour forcer la cohérence min ≤ max. */
object ValidationExpr {

    /** Résout la borne [brut] contre les [valeurs] courantes du formulaire.
     *  @return la borne sous forme Double, ou null si elle ne s'applique pas (entrée
     *          absente, expression non reconnue, ou champ référencé vide / non numérique). */
    fun resoudreBorne(brut: String?, valeurs: Map<String, Any?>): Double? {
        if (brut.isNullOrBlank()) return null
        val s = brut.trim()
        // Littéral direct : 0, -3.14, 100, etc.
        s.toDoubleOrNull()?.let { return it }
        // Expression arrow → on extrait `value.<champ>` (ou `value["<champ>"]`) et on lit
        // la valeur courante du champ référencé dans le formulaire.
        val champRef = extraireChampReference(s) ?: return null
        return numerique(valeurs[champRef])
    }

    /** Reconnaît les variantes vues sur les protocoles GeoNature et renvoie le nom du champ
     *  référencé, ou null si la forme n'est pas comprise. */
    internal fun extraireChampReference(expr: String): String? {
        // `({value}) => value.<champ>` ou `(value) => value.<champ>`
        Regex("""^\(\s*\{?\s*value\s*\}?\s*\)\s*=>\s*value\.(\w+)\s*$""")
            .matchEntire(expr)?.let { return it.groupValues[1] }
        // `value => value.<champ>` (sans parenthèses)
        Regex("""^value\s*=>\s*value\.(\w+)\s*$""")
            .matchEntire(expr)?.let { return it.groupValues[1] }
        // `({value}) => value["<champ>"]` (notation crochets, plus rare)
        Regex("""^\(\s*\{?\s*value\s*\}?\s*\)\s*=>\s*value\[\s*['"](\w+)['"]\s*\]\s*$""")
            .matchEntire(expr)?.let { return it.groupValues[1] }
        // Forme nue `value.<champ>` (occasionnellement déclarée sans la lambda).
        Regex("""^value\.(\w+)\s*$""")
            .matchEntire(expr)?.let { return it.groupValues[1] }
        android.util.Log.w("ValidationExpr",
            "Borne non reconnue, contrainte ignorée : $expr")
        return null
    }

    /** Convertit une valeur de formulaire en Double si possible. Cohérent avec la sémantique
     *  de [FormulaireRenderer.lireValeurs] qui peut renvoyer Int (NUMBER), String (TEXT) ou
     *  null (champ vide). */
    private fun numerique(v: Any?): Double? = when (v) {
        null -> null
        is Number -> v.toDouble()
        is String -> v.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        else -> null
    }

    /** Une violation de contrainte sur un champ. [type] qualifie laquelle des deux bornes
     *  a été franchie, pour pouvoir afficher un message contextualisé. */
    data class Violation(val type: Type, val borne: Double) {
        enum class Type { TROP_PETIT, TROP_GRAND }
    }

    /** Évalue les bornes contre une [valeurCourante] (sortie de lireValeurs). Renvoie une
     *  Violation si la valeur est non vide et hors bornes, null sinon (vide ou conforme).
     *  Un champ vide ne déclenche pas de violation : c'est `obligatoire` qui s'en occupe. */
    fun violation(
        valeurCourante: Any?,
        minBrut: String?,
        maxBrut: String?,
        valeursFormulaire: Map<String, Any?>,
    ): Violation? {
        val v = numerique(valeurCourante) ?: return null
        resoudreBorne(minBrut, valeursFormulaire)?.let { borne ->
            if (v < borne) return Violation(Violation.Type.TROP_PETIT, borne)
        }
        resoudreBorne(maxBrut, valeursFormulaire)?.let { borne ->
            if (v > borne) return Violation(Violation.Type.TROP_GRAND, borne)
        }
        return null
    }
}
