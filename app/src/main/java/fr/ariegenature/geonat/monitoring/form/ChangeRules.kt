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

/** Évaluateur pragmatique des règles `change` de gn_module_monitoring (auto-remplissage de
 *  champs dépendants). Le serveur les envoie sous forme d'un tableau de lignes JS :
 *
 *  ```json
 *  "change": [
 *    "({objForm, meta}) => {",
 *    "if (objForm.value.presence === 'Non') {",
 *    "objForm.patchValue({count_min: 0, count_max: 0})",
 *    "}",
 *    "}"
 *  ]
 *  ```
 *
 *  On ne peut pas exécuter du JS arbitraire côté Android — comme [HiddenExpr], on couvre les
 *  patterns observés : un bloc `if (objForm.value.X <op> 'Y') { objForm.patchValue({…}) }`
 *  (ops ===, ==, !==, !=, ou test truthy/falsy), et les `patchValue` inconditionnels au
 *  niveau racine. Ternaires et déclarations `const` (2-pass de gn_mobile_monitoring) ne sont
 *  pas gérés — les lignes non reconnues sont ignorées. */
object ChangeRules {

    /** Condition d'une règle, portant sur la valeur courante d'un champ [champ]. */
    data class Condition(val champ: String, val operateur: Op, val valeurAttendue: String?)

    enum class Op { EGAL, DIFFERENT, TRUTHY, FALSY }

    /** Une règle : applique [patch] (code → valeur) quand [condition] est vraie, ou toujours
     *  si [condition] est null. */
    data class Regle(val condition: Condition?, val patch: Map<String, Any?>)

    private val REGEX_PATCH = Regex("""objForm\.patchValue\(\s*\{(.*?)\}\s*\)""")
    private val REGEX_COND_COMPARAISON =
        Regex("""(?:objForm\.)?value(?:\.(\w+)|\[['"](\w+)['"]\])\s*(===|!==|==|!=)\s*(.+)""")
    private val REGEX_COND_TRUTHY =
        Regex("""(!?)\s*(?:objForm\.)?value(?:\.(\w+)|\[['"](\w+)['"]\])\s*""")

    /** Parse les lignes brutes en liste de règles ordonnées. Format multi-lignes attendu :
     *  une ligne `if (…) {`, une ligne `objForm.patchValue({…})`, une ligne `}`. */
    fun parser(lignes: List<String>): List<Regle> {
        if (lignes.isEmpty()) return emptyList()
        val regles = mutableListOf<Regle>()
        var conditionCourante: Condition? = null
        for (ligneBrute in lignes) {
            val ligne = ligneBrute.trim()
            if (ligne.isEmpty()) continue
            // `if (...)` : ouvre un bloc conditionnel (on extrait l'expression entre parenthèses).
            if (ligne.startsWith("if") && ligne.contains('(')) {
                conditionCourante = parserCondition(ligne.substringAfter('(').substringBeforeLast(')').trim())
            }
            // `objForm.patchValue({...})` : règle utilisant la condition courante (ou null).
            REGEX_PATCH.find(ligne)?.let { m ->
                regles.add(Regle(conditionCourante, parserPatch(m.groupValues[1])))
            }
            // Fin de bloc `}` (hors ligne `if`) → retour au niveau inconditionnel.
            if (ligne.endsWith("}") && !ligne.startsWith("if")) {
                conditionCourante = null
            }
        }
        return regles
    }

    private fun parserCondition(brut: String): Condition? {
        REGEX_COND_COMPARAISON.matchEntire(brut)?.let { m ->
            val champ = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val op = if (m.groupValues[3].startsWith("!")) Op.DIFFERENT else Op.EGAL
            val valeur = m.groupValues[4].trim().trim('\'', '"', ';').trim()
            return Condition(champ, op, valeur)
        }
        REGEX_COND_TRUTHY.matchEntire(brut)?.let { m ->
            val champ = m.groupValues[2].ifEmpty { m.groupValues[3] }
            if (champ.isEmpty()) return null
            val op = if (m.groupValues[1] == "!") Op.FALSY else Op.TRUTHY
            return Condition(champ, op, null)
        }
        return null
    }

    /** Parse le corps d'un objet `{a: 0, b: 'x', c: null, d: true}` en Map code → valeur typée. */
    private fun parserPatch(corps: String): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        if (corps.isBlank()) return out
        for (paire in corps.split(',')) {
            val idx = paire.indexOf(':')
            if (idx <= 0) continue
            val cle = paire.take(idx).trim().trim('\'', '"')
            if (cle.isEmpty()) continue
            out[cle] = parserLitteral(paire.substring(idx + 1).trim())
        }
        return out
    }

    private fun parserLitteral(brut: String): Any? {
        val s = brut.trim().trimEnd(';').trim()
        return when {
            s == "null" || s == "undefined" -> null
            s == "true" -> true
            s == "false" -> false
            s.startsWith('\'') || s.startsWith('"') -> s.trim('\'', '"')
            s.toIntOrNull() != null -> s.toInt()
            s.toDoubleOrNull() != null -> s.toDouble()
            else -> s // expression non littérale : on garde le texte brut
        }
    }

    /** Évalue toutes les règles contre les [valeurs] courantes et renvoie les champs à mettre
     *  à jour (les règles plus tardives surchargent les précédentes). */
    fun evaluer(regles: List<Regle>, valeurs: Map<String, Any?>): Map<String, Any?> {
        val maj = linkedMapOf<String, Any?>()
        for (regle in regles) {
            if (conditionVraie(regle.condition, valeurs)) maj.putAll(regle.patch)
        }
        return maj
    }

    private fun conditionVraie(condition: Condition?, valeurs: Map<String, Any?>): Boolean {
        if (condition == null) return true
        val courant = valeurs[condition.champ]
        return when (condition.operateur) {
            Op.EGAL -> egal(courant, condition.valeurAttendue)
            Op.DIFFERENT -> !egal(courant, condition.valeurAttendue)
            Op.TRUTHY -> truthy(courant)
            Op.FALSY -> !truthy(courant)
        }
    }

    private fun egal(courant: Any?, attendu: String?): Boolean {
        if (attendu == null) return courant == null
        return (courant?.toString() ?: "") == attendu
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && !v.equals("false", true) && v != "0"
        is Collection<*> -> v.isNotEmpty()
        else -> true
    }
}
