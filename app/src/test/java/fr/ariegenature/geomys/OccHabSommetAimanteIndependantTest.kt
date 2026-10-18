package fr.ariegenature.geomys

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Un geste d'édition ne doit modifier QUE la station en cours — déplacer un sommet AIMANTÉ sur
 * celui d'une station voisine (donc confondu avec lui) comme insérer un sommet sur une arête
 * qu'elles partagent (demande utilisateur 2026-10-18). L'aimantage sert à POSER un sommet exactement sur un autre ; il ne marie pas les
 * deux polygones. C'est la révision de la topologie partagée du 2026-08-30, dans le même
 * mouvement que « Annuler » la veille.
 *
 * Le geste vit dans un listener de drag osmdroid, hors d'atteinte d'un test unitaire : la garde
 * porte donc sur le SITE D'APPEL (cf. PurgeRechargementApresMajTest), et tombe si la propagation
 * est rétablie au relâcher d'un sommet.
 */
class OccHabSommetAimanteIndependantTest {

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
    fun le_drag_d_un_sommet_ne_propage_rien_aux_stations_voisines() {
        val editeur = lireCodeSource("app/src/main/java/fr/ariegenature/geomys/ui/EditeurGeometrie.kt")
        assertFalse(
            "AUCUN geste d'édition ne doit se répercuter sur une station voisine : ni le " +
                "relâcher d'un sommet aimanté, ni l'insertion d'un sommet sur une arête partagée.",
            editeur.contains("propager"),
        )
        val fragment = lireCodeSource("app/src/main/java/fr/ariegenature/geomys/ui/OccHabCarteFragment.kt")
        assertFalse("Plus aucune propagation côté fragment non plus.", fragment.contains("propager"))
    }

    @Test
    fun aucun_utilitaire_de_topologie_partagee_ne_subsiste() {
        // Ces deux fonctions répercutaient un geste chez le voisin ; sans appelant, les laisser
        // laisserait croire que la topologie est encore partagée.
        val topo = lireCodeSource("app/src/main/java/fr/ariegenature/geomys/util/TopologiePolygone.kt")
        assertFalse(topo.contains("deplacerSommetsConfondus"))
        assertFalse(topo.contains("insererSurArete"))
    }
}
