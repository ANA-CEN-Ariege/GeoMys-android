package fr.ariegenature.geomys

import fr.ariegenature.geomys.monitoring.form.HiddenExpr
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenExprTest {

    // ─── Expressions simples ${champ} ────────────────────────────────────────

    @Test
    fun expression_vide_ou_null_ne_masque_jamais() {
        assertFalse(HiddenExpr.masquer(null, emptyMap()))
        assertFalse(HiddenExpr.masquer("", emptyMap()))
        assertFalse(HiddenExpr.masquer("   ", emptyMap()))
    }

    @Test
    fun champ_truthy_masque() {
        assertTrue(HiddenExpr.masquer("\${actif}", mapOf("actif" to true)))
        assertTrue(HiddenExpr.masquer("\${actif}", mapOf("actif" to "oui")))
        assertTrue(HiddenExpr.masquer("\${actif}", mapOf("actif" to 1)))
    }

    @Test
    fun champ_falsy_ne_masque_pas() {
        assertFalse(HiddenExpr.masquer("\${actif}", mapOf("actif" to false)))
        assertFalse(HiddenExpr.masquer("\${actif}", mapOf("actif" to "")))
        assertFalse(HiddenExpr.masquer("\${actif}", mapOf("actif" to 0)))
        assertFalse(HiddenExpr.masquer("\${actif}", mapOf("actif" to null)))
        assertFalse(HiddenExpr.masquer("\${actif}", emptyMap()))
    }

    // ─── Négation !${champ} ──────────────────────────────────────────────────

    @Test
    fun negation_champ_falsy_masque() {
        assertTrue(HiddenExpr.masquer("!\${actif}", mapOf("actif" to false)))
        assertTrue(HiddenExpr.masquer("!\${actif}", mapOf("actif" to "")))
        assertTrue(HiddenExpr.masquer("!\${actif}", emptyMap()))
    }

    @Test
    fun negation_champ_truthy_ne_masque_pas() {
        assertFalse(HiddenExpr.masquer("!\${actif}", mapOf("actif" to true)))
        assertFalse(HiddenExpr.masquer("!\${actif}", mapOf("actif" to "oui")))
    }

    // ─── Comparaisons === et !== ─────────────────────────────────────────────

    @Test
    fun egalite_stricte_avec_valeur_attendue() {
        val expr = "\${statut} === 'valide'"
        assertTrue(HiddenExpr.masquer(expr, mapOf("statut" to "valide")))
        assertFalse(HiddenExpr.masquer(expr, mapOf("statut" to "brouillon")))
        assertFalse(HiddenExpr.masquer(expr, emptyMap()))
    }

    @Test
    fun inegalite_stricte_avec_valeur_attendue() {
        val expr = "\${statut} !== 'valide'"
        assertFalse(HiddenExpr.masquer(expr, mapOf("statut" to "valide")))
        assertTrue(HiddenExpr.masquer(expr, mapOf("statut" to "brouillon")))
        assertTrue(HiddenExpr.masquer(expr, emptyMap()))
    }

    @Test
    fun egalite_non_stricte_double_egal_accepte() {
        // gn_module_monitoring déclare parfois `==` au lieu de `===`.
        assertTrue(HiddenExpr.masquer("\${statut} == 'valide'", mapOf("statut" to "valide")))
    }

    // ─── Lambda fléchée gn_module_monitoring ─────────────────────────────────

    @Test
    fun lambda_destructuree_value_champ() {
        // Forme la plus courante côté schéma serveur monitoring.
        val expr = "({value}) => !value.habitat_input"
        assertTrue(HiddenExpr.masquer(expr, mapOf("habitat_input" to "")))
        assertFalse(HiddenExpr.masquer(expr, mapOf("habitat_input" to "Lande")))
    }

    @Test
    fun lambda_parametre_value_simple() {
        val expr = "(value) => value.absent"
        assertTrue(HiddenExpr.masquer(expr, mapOf("absent" to true)))
        assertFalse(HiddenExpr.masquer(expr, mapOf("absent" to false)))
    }

    @Test
    fun lambda_avec_comparaison_egalite() {
        val expr = "({value}) => value.statut === 'inactif'"
        assertTrue(HiddenExpr.masquer(expr, mapOf("statut" to "inactif")))
        assertFalse(HiddenExpr.masquer(expr, mapOf("statut" to "actif")))
    }

    // ─── Notation crochets value["champ"] ────────────────────────────────────

    @Test
    fun notation_crochets_supportee() {
        val expr = "({value}) => value[\"presence\"]"
        assertTrue(HiddenExpr.masquer(expr, mapOf("presence" to true)))
        assertFalse(HiddenExpr.masquer(expr, mapOf("presence" to false)))
    }

    // ─── Fallback : expression non reconnue ──────────────────────────────────

    @Test
    fun expression_non_reconnue_ne_masque_pas_et_log() {
        // Une expression complexe inconnue ne doit pas crasher — fallback "visible".
        assertFalse(HiddenExpr.masquer("complexExpression(value)", emptyMap()))
        assertFalse(HiddenExpr.masquer("Math.max(value.a, value.b) > 10", emptyMap()))
    }

    // ─── Normalisation interne ───────────────────────────────────────────────

    @Test
    fun normaliser_lambda_extrait_corps() {
        val brut = "({value}) => !value.actif"
        // Après normalisation : `!\${actif}`
        val norm = HiddenExpr.normaliser(brut)
        assertTrue("Attendu '!\${actif}', reçu '$norm'", norm == "!\${actif}")
    }

    @Test
    fun normaliser_value_crochets_transforme() {
        val brut = "value[\"champ\"]"
        val norm = HiddenExpr.normaliser(brut)
        assertTrue("Attendu '\${champ}', reçu '$norm'", norm == "\${champ}")
    }

    // ─── Négation parenthésée (schéma Point écoute avifaune) ────────────────

    @Test
    fun negation_parenthesee_d_une_comparaison() {
        // Expression RÉELLE du protocole « Point écoute avifaune » : les champs de
        // description de végétation ne sont visibles qu'au passage 2. Avant ce support,
        // l'expression était « non reconnue » → champs toujours visibles (bug terrain).
        val expr = "({value}) => !(value.num_passage == 2)"
        // num_passage non encore choisi → masqué (parité web : !(undefined == 2) = true).
        assertTrue(HiddenExpr.masquer(expr, emptyMap()))
        assertTrue(HiddenExpr.masquer(expr, mapOf("num_passage" to "")))
        // Passage 1 → masqué.
        assertTrue(HiddenExpr.masquer(expr, mapOf("num_passage" to "1")))
        // Passage 2 → visible.
        assertFalse(HiddenExpr.masquer(expr, mapOf("num_passage" to "2")))
    }

    @Test
    fun negation_parenthesee_triple_egal_et_parentheses_simples() {
        assertTrue(HiddenExpr.masquer("({value}) => !(value.statut === 'actif')",
            mapOf("statut" to "inactif")))
        assertFalse(HiddenExpr.masquer("({value}) => !(value.statut === 'actif')",
            mapOf("statut" to "actif")))
        // Parenthèses englobantes sans négation.
        assertTrue(HiddenExpr.masquer("({value}) => (value.statut == 'actif')",
            mapOf("statut" to "actif")))
        // Double négation parenthésée — tordu mais doit rester cohérent.
        assertTrue(HiddenExpr.masquer("({value}) => !(!(value.statut == 'actif'))",
            mapOf("statut" to "actif")))
    }

    @Test
    fun evaluer_booleen_pour_le_required_dynamique() {
        // Expression `required` RÉELLE du protocole Point écoute avifaune : champ requis
        // seulement au passage 2. Même grammaire que hidden, via l'alias evaluerBooleen.
        val expr = "({value}) => value.num_passage == 2"
        assertTrue(HiddenExpr.evaluerBooleen(expr, mapOf("num_passage" to "2")))
        assertFalse(HiddenExpr.evaluerBooleen(expr, mapOf("num_passage" to "1")))
        assertFalse(HiddenExpr.evaluerBooleen(expr, emptyMap()))
        // Grammaire inconnue → false : on ne bloque pas la saisie sur une expression
        // qu'on ne sait pas évaluer.
        assertFalse(HiddenExpr.evaluerBooleen("({value}) => value.a + value.b > 3", emptyMap()))
    }

    @Test
    fun expression_meta_reste_non_reconnue_donc_visible() {
        // Les expressions sur `meta` (contexte applicatif web) ne sont pas évaluables ici :
        // fallback « visible », sans crash. Le masquage dataset-unique est géré ailleurs
        // (auto-sélection + masquage quand une seule option, cf. enrichirAvecOptions).
        assertFalse(HiddenExpr.masquer(
            "({meta}) => meta.dataset && Object.keys(meta.dataset).length == 1", emptyMap()))
    }
}
