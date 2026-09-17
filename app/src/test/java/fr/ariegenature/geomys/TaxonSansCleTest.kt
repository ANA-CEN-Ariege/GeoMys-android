/*
 * GeoMys-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geomys

import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.store.TaxRefEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * LES TAXONS QUI N'ONT AUCUNE CLÉ DANS LE CACHE (audit 2026-09-17, constat C5).
 *
 * Le cache principal est indexé par NOM : une clé désigne un seul `cd_nom`. Un taxon dont le nom
 * scientifique ET tous les noms français sont déjà pris par d'autres n'y a donc AUCUNE entrée —
 * 260 sur les 100 468 taxons de l'appareil de référence. Il devenait invisible partout : pas
 * proposé, pas résolvable, pas affichable.
 *
 * Cas réel reproduit ici : le genre *Pieris* existe deux fois, chez les papillons (196270,
 * Schrank) et chez les plantes (196271, D.Don — l'andromède). La clé « pieris » revient au plus
 * petit cd_nom, donc au papillon ; et le nom français « Piéris » de la plante se normalise
 * EXACTEMENT en cette clé, déjà posée par la passe scientifique. La plante perd tout.
 *
 * Le correctif : la synchro persiste désormais l'index complet cd_nom → nom scientifique, comme
 * elle le faisait déjà pour les noms français. Le cache reste indexé par nom pour la recherche,
 * mais plus aucun taxon n'y perd son identité.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxonSansCleTest {

    private val papillon = 196270
    private val plante = 196271

    @Before
    fun setup() {
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
        TaxRefCache.vider()
        // Le cache tel que la synchro le produit : « pieris » est allé au papillon, la plante
        // n'a aucune clé.
        TaxRefCache.remplacerTout(
            mapOf(TaxRefCache.normaliser("Pieris") to TaxRefEntry(papillon, "Pieris", null))
        )
        TaxRefCache.ajouterVerns(mapOf(plante to listOf("Piéris")))
        TaxRefCache.ajouterListesParCdNom(mapOf(papillon to listOf(100), plante to listOf(100, 108)))
        TaxRefCache.setIndexParTaxon(
            mapOf(Taxon.INSECTE to listOf(papillon), Taxon.PLANTE to listOf(plante))
        )
    }

    @Test
    fun sans_l_index_le_taxon_est_introuvable() {
        // État d'un cache écrit avant le correctif : l'index n'existe pas, rien ne change.
        assertNull(TaxRefCache.entreesParCdNom()[plante])
        assertEquals(emptyMap<Int, String>(), TaxRefCache.sciNomsParCdNom())
    }

    @Test
    fun avec_l_index_le_taxon_retrouve_son_identite() {
        TaxRefCache.ajouterSciNoms(mapOf(papillon to "Pieris", plante to "Pieris"))
        val e = TaxRefCache.entreesParCdNom()[plante]
        assertNotNull("le taxon sans clé doit exister par son cd_nom", e)
        assertEquals("Pieris", e?.sciNom)
        assertEquals("Piéris", e?.nomFrOriginal)
        // Et le taxon qui détient la clé n'est pas altéré.
        assertEquals(papillon, TaxRefCache.get("Pieris")?.cdNom)
    }

    @Test
    fun il_redevient_resolvable_dans_son_groupe() {
        TaxRefCache.ajouterSciNoms(mapOf(papillon to "Pieris", plante to "Pieris"))
        // Saisie en groupe PLANTE : « Piéris » doit donner l'andromède, pas le papillon. Avant,
        // le repli sautait ce taxon (`parCdNom[cd] ?: continue`) et rendait l'entrée globale.
        assertEquals(plante, TaxRefCache.get("Piéris", setOf(plante))?.cdNom)
        // Contre-épreuve : en groupe INSECTE, c'est toujours le papillon.
        assertEquals(papillon, TaxRefCache.get("Pieris", setOf(papillon))?.cdNom)
    }

    @Test
    fun il_redevient_proposable_dans_les_deux_modes() {
        TaxRefCache.ajouterSciNoms(mapOf(papillon to "Pieris", plante to "Pieris"))
        val fr = TaxRefLocal.getSuggestionsTaxon(Taxon.PLANTE, scientifique = false, idListeFiltre = 100)
        assertEquals(plante, fr.first { it.nom == "Piéris" }.cdNom)
        assertEquals("Pieris", fr.first { it.nom == "Piéris" }.secondaire)
        val sci = TaxRefLocal.getSuggestionsTaxon(Taxon.PLANTE, scientifique = true, idListeFiltre = 100)
        assertEquals(plante, sci.first { it.nom == "Pieris" }.cdNom)
    }

    @Test
    fun il_redevient_proposable_dans_un_protocole_monitoring() {
        TaxRefCache.ajouterSciNoms(mapOf(papillon to "Pieris", plante to "Pieris"))
        val props = TaxRefCache.propositionsListe(108)
        assertEquals(plante, props.first { it.nom == "Piéris" }.cdNom)
        assertEquals(plante, TaxRefCache.getDansListe("Piéris", 108)?.cdNom)
    }

    @Test
    fun l_index_est_bien_ecrit_sur_le_disque() {
        // Le mémo masquerait une non-écriture : on lit le fichier lui-même, c'est lui qui sera
        // relu au prochain démarrage.
        TaxRefCache.ajouterSciNoms(mapOf(plante to "Pieris"))
        val fichier = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "taxref/sci_v1.json",
        )
        assertTrue("l'index doit être persisté", fichier.exists())
        assertTrue(fichier.readText().contains("\"$plante\":\"Pieris\""))
    }

    @Test
    fun vider_le_cache_efface_aussi_l_index() {
        TaxRefCache.ajouterSciNoms(mapOf(plante to "Pieris"))
        TaxRefCache.vider()
        assertEquals(emptyMap<Int, String>(), TaxRefCache.sciNomsParCdNom())
    }

    /** SITE D'APPEL : la synchro doit écrire cet index, sinon le correctif ne sert à rien. */
    @Test
    fun la_synchro_persiste_l_index_des_noms_scientifiques() {
        val src = File("src/main/java/fr/ariegenature/geomys/network/GeoNatureSync.kt").readText()
        assertTrue("GeoNatureSync doit appeler ajouterSciNoms(lbNomParCd)",
            src.contains("TaxRefCache.ajouterSciNoms(lbNomParCd)"))
    }
}
