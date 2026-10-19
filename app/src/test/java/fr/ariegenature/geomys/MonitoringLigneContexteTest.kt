package fr.ariegenature.geomys

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * La ligne « Sur : <site> — Protocole : <module> » doit survivre à « Enregistrer + suivante ».
 * Le même TextView (`tvContexte`) porte DEUX choses : cette ligne de contexte et les messages
 * d'envoi. La réinitialisation du formulaire le vidait pour purger les messages, et emportait le
 * contexte avec — l'observateur enchaînait ses saisies sans plus voir sur quel site il était
 * (constat 2026-10-19).
 *
 * Vue Android : garde sur le SITE D'APPEL (cf. PurgeRechargementApresMajTest).
 */
class MonitoringLigneContexteTest {

    private fun source(chemin: String): List<String> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val f = File(dir, chemin)
            if (f.exists()) return f.readLines()
            dir = dir.parentFile
        }
        throw AssertionError("Source introuvable : $chemin")
    }

    @Test
    fun la_reinitialisation_du_formulaire_repose_la_ligne_de_contexte() {
        val lignes = source("app/src/main/java/fr/ariegenature/geomys/ui/NouvelleVisiteFragment.kt")
        val debut = lignes.indexOfFirst { it.contains("private fun reinitialiserFormulaire()") }
        assertTrue("reinitialiserFormulaire introuvable", debut >= 0)
        val fin = lignes.drop(debut + 1).indexOfFirst { it == "    }" } + debut + 1
        val corps = lignes.subList(debut, fin + 1).joinToString("\n")

        assertTrue(
            "reinitialiserFormulaire vide tvContexte (purge des messages d'envoi) : elle doit " +
                "REPOSER la ligne « Sur : … — Protocole : … », sinon elle disparaît après chaque " +
                "« Enregistrer + suivante ».",
            corps.contains("afficherLigneContexte()"),
        )
        assertTrue(
            "le contexte doit être reposé, pas simplement effacé",
            !corps.contains("tvContexte.text = \"\"") || corps.contains("afficherLigneContexte()"),
        )
    }
}
