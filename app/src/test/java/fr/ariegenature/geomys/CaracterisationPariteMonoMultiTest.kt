package fr.ariegenature.geomys

import fr.ariegenature.geomys.store.OcctaxFieldsConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L'écran Caractérisation est partagé par la saisie MONO-taxon et la saisie MULTI-taxons : ce
 * qu'il renvoie doit être repris des DEUX côtés. En mono, la naturalité et les champs pilotés par
 * form_fields (statut de la source, floutage, preuves) étaient saisis puis PERDUS au retour —
 * l'écran les renvoyait, personne ne les écoutait (constat 2026-10-19).
 *
 * Échange d'écran à écran, donc hors d'atteinte d'un test unitaire : garde sur le site d'appel
 * (cf. PurgeRechargementApresMajTest), à partir du registre réel des champs Occtax.
 */
class CaracterisationPariteMonoMultiTest {

    private fun lireCodeSource(chemin: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val f = File(dir, chemin)
            if (f.exists()) {
                return f.readLines()
                    .filterNot { val l = it.trim(); l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") }
                    .joinToString("\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("Source introuvable : $chemin")
    }

    private val mono get() = lireCodeSource("app/src/main/java/fr/ariegenature/geomys/ui/SaisieRapideFragment.kt")
    private val multi get() = lireCodeSource("app/src/main/java/fr/ariegenature/geomys/ui/SaisieObservationFragment.kt")

    @Test
    fun les_deux_saisies_reprennent_tous_les_champs_d_occurrence_de_la_caracterisation() {
        val mono = mono
        val multi = multi
        val champs = OcctaxFieldsConfig.REGISTRE
            .filter { it.niveau == OcctaxFieldsConfig.Niveau.INFORMATION && it.formFieldKey.isEmpty() }
        assertTrue("registre vide : le test ne garde plus rien", champs.size >= 8)
        for (champ in champs) {
            // La clé savedStateHandle est aussi le nom du champ porteur : les deux écrans la
            // citent forcément s'ils la reprennent.
            assertTrue(
                "SaisieRapideFragment (mono) ne reprend pas « ${champ.label} » (${champ.svKey}) " +
                    "au retour de Caractérisation : la valeur saisie est perdue.",
                mono.contains("\"${champ.svKey}\""),
            )
            assertTrue(
                "SaisieObservationFragment (multi) ne reprend pas « ${champ.label} » (${champ.svKey}).",
                multi.contains("\"${champ.svKey}\""),
            )
        }
    }

    @Test
    fun les_deux_saisies_reprennent_les_champs_pilotes_par_form_fields() {
        // Statut de la source, floutage, preuve numérique / non numérique : regroupés dans une
        // seule clé, champsOccExtra, pour ne pas multiplier les champs à travers tout le code.
        for ((nom, src) in listOf("mono" to mono, "multi" to multi)) {
            assertTrue(
                "La saisie $nom ne reprend pas occExtraJson : les champs pilotés par form_fields " +
                    "(statut de la source, floutage, preuves) sont perdus au retour.",
                src.contains("\"occExtraJson\""),
            )
            assertTrue(
                "La saisie $nom ne renvoie pas occExtraJson à Caractérisation : les valeurs déjà " +
                    "saisies ne seraient pas pré-remplies au second passage.",
                src.contains("putString(\"occExtraJson\"") || src.contains("putString(\"occExtraJson\","),
            )
        }
    }
}
