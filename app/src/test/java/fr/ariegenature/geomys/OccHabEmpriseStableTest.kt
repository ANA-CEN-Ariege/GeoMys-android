package fr.ariegenature.geomys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * L'emprise de la carte OccHab ne saute JAMAIS sur l'ensemble des stations enregistrées : on
 * ouvre l'écran sur sa localisation, et « Ajouter une station » doit l'y laisser (demande
 * utilisateur 2026-10-18). La carte revient alors du formulaire des infos obligatoires, donc
 * d'une recréation de vue — et c'est là qu'elle se recadrait sur toutes les stations.
 *
 * Règle gardée ici : on ne cadre que sur la géométrie EN COURS d'édition (réédition d'un relevé,
 * demande de la veille) ; sinon on repose l'emprise mémorisée, sans jamais se replier sur les
 * stations de session. Cadrage non testable autrement (osmdroid + cycle de vue) : garde sur le
 * site d'appel, cf. PurgeRechargementApresMajTest.
 */
class OccHabEmpriseStableTest {

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

    @Test
    fun le_chargement_des_stations_serveur_ne_touche_jamais_a_l_emprise() {
        // Chargement ASYNCHRONE : il arrive après coup et écrasait la vue. Choisir le jeu de
        // données dans « Ajouter une station » le déclenche — c'est par là que l'emprise sautait
        // sur l'ensemble des stations alors qu'on venait d'ouvrir OccHab sur sa localisation.
        val overlay = lireCodeSource("app/src/main/java/fr/ariegenature/geomys/ui/StationsServeurOverlay.kt")
        assertFalse(
            "Les stations serveur se dessinent sans recadrer la carte : l'emprise ne doit " +
                "changer que sur un geste de l'utilisateur.",
            overlay.contains("cadrerSur"),
        )
    }

    @Test
    fun le_cadrage_ne_se_replie_jamais_sur_les_stations_de_session() {
        val src = lireCodeSource("app/src/main/java/fr/ariegenature/geomys/ui/OccHabCarteFragment.kt")
        assertFalse(
            "Cadrer sur les stations de session comme REPLI faisait sauter l'emprise à chaque " +
                "« Ajouter une station » : sans géométrie en cours, on garde l'emprise mémorisée.",
            src.contains("ifEmpty { ptsSession }"),
        )
        assertEquals(
            "Les deux chemins d'ouverture (consultation et création) doivent reposer l'emprise " +
                "mémorisée par le même appel.",
            2, Regex("""!restaurerCadrageMemorise\(\)""").findAll(src).count(),
        )
    }
}
