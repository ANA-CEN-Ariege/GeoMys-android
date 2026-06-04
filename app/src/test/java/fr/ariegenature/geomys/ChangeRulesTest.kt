package fr.ariegenature.geomys

import fr.ariegenature.geomys.monitoring.form.ChangeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeRulesTest {

    /** Pattern de référence gn_module_monitoring : if conditionnel + patchValue. */
    private val reglesPresence = listOf(
        "({objForm, meta}) => {",
        "if (objForm.value.presence === 'Non') {",
        "objForm.patchValue({count_min: 0, count_max: 0})",
        "}",
        "}",
    )

    @Test
    fun condition_vraie_applique_le_patch() {
        val regles = ChangeRules.parser(reglesPresence)
        val maj = ChangeRules.evaluer(regles, mapOf("presence" to "Non"))
        assertEquals(mapOf<String, Any?>("count_min" to 0, "count_max" to 0), maj)
    }

    @Test
    fun condition_fausse_ne_touche_rien() {
        val regles = ChangeRules.parser(reglesPresence)
        val maj = ChangeRules.evaluer(regles, mapOf("presence" to "Oui"))
        assertTrue(maj.isEmpty())
    }

    @Test
    fun patch_inconditionnel_toujours_applique() {
        val regles = ChangeRules.parser(
            listOf(
                "({objForm, meta}) => {",
                "objForm.patchValue({observers_txt: 'auto'})",
                "}",
            ),
        )
        val maj = ChangeRules.evaluer(regles, emptyMap())
        assertEquals(mapOf<String, Any?>("observers_txt" to "auto"), maj)
    }

    @Test
    fun operateur_different_et_litteraux_typés() {
        val regles = ChangeRules.parser(
            listOf(
                "({objForm, meta}) => {",
                "if (objForm.value.statut !== 'valide') {",
                "objForm.patchValue({actif: false, score: null, note: 3.5})",
                "}",
                "}",
            ),
        )
        val maj = ChangeRules.evaluer(regles, mapOf("statut" to "brouillon"))
        assertEquals(false, maj["actif"])
        assertTrue(maj.containsKey("score") && maj["score"] == null)
        assertEquals(3.5, maj["note"])
    }

    @Test
    fun test_truthy_sur_champ_booleen() {
        val regles = ChangeRules.parser(
            listOf(
                "({objForm}) => {",
                "if (objForm.value.absent) {",
                "objForm.patchValue({count_min: 0})",
                "}",
                "}",
            ),
        )
        assertEquals(mapOf<String, Any?>("count_min" to 0), ChangeRules.evaluer(regles, mapOf("absent" to true)))
        assertTrue(ChangeRules.evaluer(regles, mapOf("absent" to false)).isEmpty())
    }

    @Test
    fun aucune_regle_sur_change_vide() {
        assertTrue(ChangeRules.parser(emptyList()).isEmpty())
    }

    // ─── Scripts RÉELS de l'instance (audit des 33 protocoles, 2026-06) ──────

    private val scriptPopAmphibien = listOf(
        "({objForm, meta}) => {",
        "if (objForm.value.presence === 'Non') {",
        "objForm.patchValue({id_nomenclature_typ_denbr : null, count_min : 0, count_max : 0, " +
            "id_nomenclature_sex : null, id_nomenclature_stade: null, cd_nom : {'cd_nom': 914450, " +
            "'lb_nom': 'Amphibia', 'nom_valide': 'Amphibia', 'nom_vern' : 'Amphibiens, batraciens'}}, {emitEvent : false})",
        "}",
        "if (objForm.value.presence === 'Oui' && objForm.value.count_min === 0) {",
        "objForm.patchValue({count_min : null, count_max : null}, {emitEvent : false})",
        "}",
        "if (!!objForm.value.count_min && objForm.value.count_max < objForm.value.count_min) {",
        "objForm.patchValue({count_max : objForm.value.count_min}, {emitEvent : false})",
        "}",
        "if (!!objForm.value.count_min && meta.nomenclatures[objForm.value.id_nomenclature_typ_denbr].cd_nomenclature === 'Co' " +
            "&& objForm.value.count_max !== objForm.value.count_min) {",
        "objForm.patchValue({count_max : objForm.value.count_min}, {emitEvent : false})",
        "}",
        "}",
    )

    @Test
    fun pop_amphibien_presence_non_reset_et_taxon_generique() {
        val maj = ChangeRules.evaluer(ChangeRules.parser(scriptPopAmphibien), mapOf("presence" to "Non"))
        assertEquals(0, maj["count_min"])
        assertEquals(0, maj["count_max"])
        assertTrue(maj.containsKey("id_nomenclature_typ_denbr") && maj["id_nomenclature_typ_denbr"] == null)
        assertTrue(maj.containsKey("id_nomenclature_sex") && maj["id_nomenclature_sex"] == null)
        assertEquals("objet taxon réduit à son cd_nom (valeur scalaire côté app)", 914450, maj["cd_nom"])
    }

    @Test
    fun pop_amphibien_presence_oui_et_zero_vide_les_comptages() {
        val maj = ChangeRules.evaluer(ChangeRules.parser(scriptPopAmphibien),
            mapOf("presence" to "Oui", "count_min" to 0))
        assertTrue(maj.containsKey("count_min") && maj["count_min"] == null)
        assertTrue(maj.containsKey("count_max") && maj["count_max"] == null)
    }

    @Test
    fun pop_amphibien_count_max_recale_sur_min() {
        // count_max < count_min → recalé (comparaison champ-contre-champ).
        val maj = ChangeRules.evaluer(ChangeRules.parser(scriptPopAmphibien),
            mapOf("presence" to "Oui", "count_min" to 5, "count_max" to 3))
        assertEquals(5, maj["count_max"])
    }

    @Test
    fun pop_amphibien_comptage_exact_via_cd_nomenclature() {
        // Type de dénombrement « Compté » (cd 'Co', exposé via la clé enrichie __cd du
        // renderer) → count_max forcé = count_min même si max > min.
        val maj = ChangeRules.evaluer(ChangeRules.parser(scriptPopAmphibien),
            mapOf("presence" to "Oui", "count_min" to 4, "count_max" to 9,
                "id_nomenclature_typ_denbr" to "321", "id_nomenclature_typ_denbr__cd" to "Co"))
        assertEquals(4, maj["count_max"])
    }

    private val scriptChouette = listOf(
        "({objForm, meta}) => {",
        "const nb_passereau = null;",
        "const chev_chant = null;",
        "const sexe = null;",
        "const nb_total = (objForm.value.nb_before_rep + objForm.value.nb_repasse);",
        "const nb_hulotte = 0;",
        "objForm.patchValue({nb_total});",
        "(objForm.value.cd_nom != (null || undefined) && objForm.value.cd_nom != 3507 ? objForm.patchValue({nb_passereau}) : '');",
        "(objForm.value.cd_nom != (null || undefined) && objForm.value.cd_nom != 3507 ? objForm.patchValue({chev_chant}) : '');",
        "(objForm.value.cd_nom != (null || undefined) && objForm.value.cd_nom != 3507 ? objForm.patchValue({sexe}) : '');",
        "(objForm.value.hulotte != 'Oui' ? objForm.patchValue({nb_hulotte}) : '');",
        "}",
    )

    @Test
    fun chouette_somme_auto_calculee_et_resets_selon_taxon() {
        // Autre taxon que la Chevêchette (3507) → champs Chevêchette remis à null ;
        // nb_total = somme, recalculée en permanence (pas de garde dirty dans ce script).
        val maj = ChangeRules.evaluer(ChangeRules.parser(scriptChouette),
            mapOf("nb_before_rep" to 2, "nb_repasse" to 3, "cd_nom" to 4001, "hulotte" to "Non"))
        assertEquals("nb_total = 2 + 3", 5, maj["nb_total"])
        assertTrue(maj.containsKey("nb_passereau") && maj["nb_passereau"] == null)
        assertTrue(maj.containsKey("chev_chant") && maj["chev_chant"] == null)
        assertEquals(0, maj["nb_hulotte"])
    }

    @Test
    fun chouette_pas_de_reset_pour_la_chevechette() {
        val maj = ChangeRules.evaluer(ChangeRules.parser(scriptChouette),
            mapOf("nb_before_rep" to 1, "nb_repasse" to 0, "cd_nom" to 3507, "hulotte" to "Oui"))
        assertEquals(1, maj["nb_total"])
        assertTrue("champs Chevêchette conservés", !maj.containsKey("nb_passereau"))
        assertTrue(!maj.containsKey("nb_hulotte"))
    }

    private val scriptFlore = listOf(
        "({objForm, meta}) => {",
        "const base_site_name = 'T' + (objForm.value.num_transect) + 'Q' + (objForm.value.num_placette);",
        "if (!objForm.controls.base_site_name.dirty) {",
        "objForm.patchValue({base_site_name})",
        "}",
        "}",
    )

    @Test
    fun flore_nom_de_site_concatene_tant_que_non_modifie() {
        // Concaténation + garde dirty (drapeau __dirty fourni par le renderer).
        val regles = ChangeRules.parser(scriptFlore)
        val maj = ChangeRules.evaluer(regles,
            mapOf("num_transect" to 3, "num_placette" to 12, "base_site_name__dirty" to false))
        assertEquals("T3Q12", maj["base_site_name"])
        // L'utilisateur a édité le nom à la main → on ne l'écrase plus.
        assertTrue(ChangeRules.evaluer(regles,
            mapOf("num_transect" to 3, "num_placette" to 12, "base_site_name__dirty" to true)).isEmpty())
    }

    @Test
    fun phytosocio_surface_choisie_par_ternaire() {
        val script = listOf(
            "({objForm, meta}) => {",
            "const surf_releve = (objForm.value.type_placette == 'C' ? objForm.value.surf_releve_c : objForm.value.surf_releve_q)",
            "objForm.patchValue({surf_releve})",
            "}",
        )
        val regles = ChangeRules.parser(script)
        assertEquals(25, ChangeRules.evaluer(regles,
            mapOf("type_placette" to "C", "surf_releve_c" to 25, "surf_releve_q" to 100))["surf_releve"])
        assertEquals(100, ChangeRules.evaluer(regles,
            mapOf("type_placette" to "Q", "surf_releve_c" to 25, "surf_releve_q" to 100))["surf_releve"])
    }

    @Test
    fun test_indi_constante_avec_garde_dirty() {
        val script = listOf(
            "({objForm, meta}) => {",
            "const cd_nom = 97152",
            "if (!objForm.controls.cd_nom.dirty) {",
            "objForm.patchValue({cd_nom: cd_nom})",
            "}",
            "}",
        )
        val regles = ChangeRules.parser(script)
        assertEquals(97152, ChangeRules.evaluer(regles, mapOf("cd_nom__dirty" to false))["cd_nom"])
        assertTrue(ChangeRules.evaluer(regles, mapOf("cd_nom__dirty" to true)).isEmpty())
    }
}
