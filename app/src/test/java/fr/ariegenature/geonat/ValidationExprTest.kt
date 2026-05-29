package fr.ariegenature.geonat

import fr.ariegenature.geonat.monitoring.form.ValidationExpr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValidationExprTest {

    // ─── Résolution de bornes ────────────────────────────────────────────────

    @Test
    fun litteral_int_resolu_en_double() {
        assertEquals(0.0, ValidationExpr.resoudreBorne("0", emptyMap()))
        assertEquals(100.0, ValidationExpr.resoudreBorne("100", emptyMap()))
        assertEquals(-3.0, ValidationExpr.resoudreBorne("-3", emptyMap()))
    }

    @Test
    fun litteral_float_resolu_en_double() {
        assertEquals(3.14, ValidationExpr.resoudreBorne("3.14", emptyMap()))
        assertEquals(-0.5, ValidationExpr.resoudreBorne("-0.5", emptyMap()))
    }

    @Test
    fun borne_vide_ou_null_renvoie_null() {
        assertNull(ValidationExpr.resoudreBorne(null, emptyMap()))
        assertNull(ValidationExpr.resoudreBorne("", emptyMap()))
        assertNull(ValidationExpr.resoudreBorne("   ", emptyMap()))
    }

    @Test
    fun lambda_destructuree_pointe_autre_champ() {
        val expr = "({value}) => value.count_max"
        assertEquals(42.0, ValidationExpr.resoudreBorne(expr, mapOf("count_max" to 42)))
        assertEquals(5.0, ValidationExpr.resoudreBorne(expr, mapOf("count_max" to "5")))
    }

    @Test
    fun lambda_simple_value_egal_value_point_champ() {
        val expr = "(value) => value.count_max"
        assertEquals(10.0, ValidationExpr.resoudreBorne(expr, mapOf("count_max" to 10)))
    }

    @Test
    fun lambda_sans_parens_supportee() {
        val expr = "value => value.count_max"
        assertEquals(7.0, ValidationExpr.resoudreBorne(expr, mapOf("count_max" to 7)))
    }

    @Test
    fun notation_crochets_supportee() {
        val expr = "({value}) => value[\"count_max\"]"
        assertEquals(8.0, ValidationExpr.resoudreBorne(expr, mapOf("count_max" to 8)))
    }

    @Test
    fun forme_nue_value_point_champ() {
        val expr = "value.count_max"
        assertEquals(3.0, ValidationExpr.resoudreBorne(expr, mapOf("count_max" to 3)))
    }

    @Test
    fun champ_reference_vide_renvoie_null() {
        val expr = "({value}) => value.count_max"
        assertNull(ValidationExpr.resoudreBorne(expr, emptyMap()))
        assertNull(ValidationExpr.resoudreBorne(expr, mapOf("count_max" to null)))
        assertNull(ValidationExpr.resoudreBorne(expr, mapOf("count_max" to "")))
        // Texte non numérique → ignoré, pas d'exception.
        assertNull(ValidationExpr.resoudreBorne(expr, mapOf("count_max" to "abc")))
    }

    @Test
    fun expression_non_reconnue_renvoie_null() {
        // Ne doit pas crasher — fallback silencieux + log.
        assertNull(ValidationExpr.resoudreBorne("Math.max(a, b)", emptyMap()))
        assertNull(ValidationExpr.resoudreBorne("randomFn()", emptyMap()))
    }

    // ─── Détection de violation ──────────────────────────────────────────────

    @Test
    fun valeur_vide_pas_de_violation() {
        // C'est `obligatoire` qui gère le vide, pas la validation min/max.
        assertNull(ValidationExpr.violation(null, "0", "100", emptyMap()))
        assertNull(ValidationExpr.violation("", "0", "100", emptyMap()))
    }

    @Test
    fun valeur_conforme_pas_de_violation() {
        assertNull(ValidationExpr.violation(50, "0", "100", emptyMap()))
        assertNull(ValidationExpr.violation(0, "0", "100", emptyMap()))
        assertNull(ValidationExpr.violation(100, "0", "100", emptyMap()))
    }

    @Test
    fun valeur_trop_petite_signale_borne_min() {
        val v = ValidationExpr.violation(-5, "0", "100", emptyMap())
        assertEquals(ValidationExpr.Violation.Type.TROP_PETIT, v?.type)
        assertEquals(0.0, v?.borne)
    }

    @Test
    fun valeur_trop_grande_signale_borne_max() {
        val v = ValidationExpr.violation(200, "0", "100", emptyMap())
        assertEquals(ValidationExpr.Violation.Type.TROP_GRAND, v?.type)
        assertEquals(100.0, v?.borne)
    }

    @Test
    fun violation_avec_borne_dynamique() {
        // count_min ≤ count_max enforcé via une borne `max` qui pointe vers count_max.
        val maxExpr = "({value}) => value.count_max"
        val ctx = mapOf("count_max" to 10)
        assertNull(ValidationExpr.violation(5, "0", maxExpr, ctx))
        val viol = ValidationExpr.violation(15, "0", maxExpr, ctx)
        assertEquals(ValidationExpr.Violation.Type.TROP_GRAND, viol?.type)
        assertEquals(10.0, viol?.borne)
    }

    @Test
    fun pas_de_min_pas_de_max_jamais_de_violation() {
        assertNull(ValidationExpr.violation(42, null, null, emptyMap()))
        assertNull(ValidationExpr.violation(42, "", "", emptyMap()))
    }

    @Test
    fun valeur_string_numerique_acceptee() {
        // FormulaireRenderer.lireValeurs peut retourner une String pour les TEXT mêmes
        // numériques — ValidationExpr doit la tolérer pour les bornes scalaires.
        assertNull(ValidationExpr.violation("50", "0", "100", emptyMap()))
        val v = ValidationExpr.violation("200", "0", "100", emptyMap())
        assertEquals(ValidationExpr.Violation.Type.TROP_GRAND, v?.type)
    }
}
