package fr.ariegenature.geonat

import fr.ariegenature.geonat.monitoring.form.ChangeRules
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
}
