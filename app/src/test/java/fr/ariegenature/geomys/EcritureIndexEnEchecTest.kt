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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.TaxRefCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * UN INDEX DE TAXONS QUI NE S'ÉCRIT PAS DOIT FAIRE ÉCHOUER LA SYNCHRO (audit 2026-09-18, T8).
 *
 * `ecrireFichier` avalait toute exception : disque plein, et la synchro se déclarait réussie avec
 * un référentiel troué. Le coût n'a rien de théorique — sans `listes_v1.json`, `cdNomsDansListe`
 * rend un ensemble vide et la saisie monitoring refuse TOUS les taxons du protocole ; sans
 * `verns_v1.json`, le mode « noms français » ne propose plus rien. Le cache principal, lui, était
 * déjà contrôlé (audit 2026-08-27) : les index annexes suivent la même règle.
 *
 * L'échec est provoqué ici en occupant le chemin du fichier temporaire par un RÉPERTOIRE : plus
 * fidèle qu'un jeu sur les permissions, et déterministe sur toutes les machines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EcritureIndexEnEchecTest {

    private lateinit var dirTaxref: File

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        TaxRefCache.init(ctx)
        TaxRefCache.vider()
        dirTaxref = File(ctx.filesDir, "taxref")
    }

    /** Rend l'écriture de [fichier] impossible. À rappeler avant chaque écriture : le code de
     *  production nettoie le temporaire derrière lui, ce qui libère le chemin. */
    private fun bloquer(fichier: String) {
        File(dirTaxref, "$fichier.tmp").mkdirs()
    }

    @Test
    fun index_vernaculaire_non_ecrit_est_signale_et_rien_nest_memorise() {
        bloquer("verns_v1.json")
        assertFalse(TaxRefCache.ajouterVerns(mapOf(4319 to listOf("Gobemouche gris"))))
        assertTrue(
            "le mémo ne doit pas prétendre détenir ce que le disque n'a pas",
            TaxRefCache.vernsParCdNom().isEmpty(),
        )
    }

    @Test
    fun index_scientifique_non_ecrit_est_signale() {
        bloquer("sci_v1.json")
        assertFalse(TaxRefCache.ajouterSciNoms(mapOf(4319 to "Muscicapa striata")))
    }

    @Test
    fun groupes_non_ecrits_sont_signales_et_rien_nest_memorise() {
        bloquer("groupes_v1.json")
        assertFalse(TaxRefCache.ajouterGroupes(mapOf(4319 to "Oiseaux")))
        assertTrue(TaxRefCache.tousLesGroupes().isEmpty())
    }

    /** Les deux fichiers sont indépendants : celui qui passe est conservé, et l'appel rend compte
     *  de l'échec de l'autre. */
    @Test
    fun un_seul_des_deux_fichiers_en_echec_suffit_a_signaler_lechec() {
        bloquer("regnes_v1.json")
        assertFalse(
            TaxRefCache.ajouterGroupes1etRegnes(mapOf(54468 to "Insectes"), mapOf(54468 to "Animalia")),
        )
        assertEquals(mapOf("54468" to "Insectes"), TaxRefCache.tousLesGroupes1())
        assertTrue(TaxRefCache.tousLesRegnes().isEmpty())
    }

    @Test
    fun appartenance_aux_listes_non_ecrite_est_signalee() {
        bloquer("listes_v1.json")
        assertFalse(TaxRefCache.ajouterListesParCdNom(mapOf(4319 to listOf(100))))
        assertTrue(
            "sans cet index, la saisie refuserait tous les taxons de la liste",
            TaxRefCache.cdNomsDansListe(100).isEmpty(),
        )
    }

    @Test
    fun index_par_groupe_non_ecrit_est_signale_et_rien_nest_memorise() {
        bloquer("index_taxon_v1.json")
        assertFalse(TaxRefCache.setIndexParTaxon(mapOf(Taxon.OISEAU to listOf(4319))))
        assertNull(TaxRefCache.indexParTaxon(Taxon.OISEAU))
    }

    /** CONTRE-ÉPREUVE indispensable : sans blocage, tout doit réussir — sinon les tests ci-dessus
     *  passeraient avec une implémentation qui renvoie false en toute circonstance. */
    @Test
    fun sans_blocage_toutes_les_ecritures_reussissent() {
        assertTrue(TaxRefCache.ajouterVerns(mapOf(4319 to listOf("Gobemouche gris"))))
        assertTrue(TaxRefCache.ajouterSciNoms(mapOf(4319 to "Muscicapa striata")))
        assertTrue(TaxRefCache.ajouterGroupes(mapOf(4319 to "Oiseaux")))
        assertTrue(TaxRefCache.ajouterGroupes1etRegnes(mapOf(4319 to "Oiseaux"), mapOf(4319 to "Animalia")))
        assertTrue(TaxRefCache.ajouterListesParCdNom(mapOf(4319 to listOf(100))))
        assertTrue(TaxRefCache.setIndexParTaxon(mapOf(Taxon.OISEAU to listOf(4319))))
        assertEquals(listOf(4319), TaxRefCache.indexParTaxon(Taxon.OISEAU))
    }

    /** Rien à écrire n'est pas un échec : la synchro appelle ces deux-là même sans donnée. */
    @Test
    fun une_ecriture_vide_nest_pas_un_echec() {
        assertTrue(TaxRefCache.ajouterSciNoms(emptyMap()))
        assertTrue(TaxRefCache.ajouterListesParCdNom(emptyMap()))
    }

    // ── Garde sur le SITE D'APPEL ──

    /** Remonte jusqu'au fichier et rend son CODE seul, commentaires retirés. */
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

    /**
     * Les tests ci-dessus prouvent que les écritures RENDENT COMPTE de leur échec — pas que la
     * synchro l'écoute. Or c'est là que se tenait le défaut : `synchroniserTaxRef` ignorait six
     * valeurs de retour. Cette fonction n'est pas testable unitairement (réseau, pagination,
     * coroutines) : on garde donc le site d'appel par une lecture de sa source.
     */
    @Test
    fun la_synchro_controle_chaque_ecriture_dindex_et_ressort_en_echec() {
        val src = lireCodeSource("app/src/main/java/fr/ariegenature/geomys/network/GeoNatureSync.kt")
        for (fn in listOf(
            "ajouterVerns", "ajouterSciNoms", "ajouterGroupes", "ajouterGroupes1etRegnes",
            "ajouterListesParCdNom", "setIndexParTaxon",
        )) {
            val appels = Regex(Regex.escape("TaxRefCache.$fn(")).findAll(src).toList()
            assertTrue("appel de $fn introuvable dans la synchro", appels.isNotEmpty())
            assertTrue(
                "TaxRefCache.$fn est appelée sans que son échec soit examiné : un index de taxons " +
                    "non écrit laisserait la synchro se déclarer réussie avec un référentiel troué " +
                    "(audit 2026-09-18, T8).",
                appels.all { it.range.first > 0 && src[it.range.first - 1] == '!' },
            )
        }
        assertTrue(
            "la synchro doit ressortir en ÉCHEC quand un index n'a pas pu être écrit, sans poser " +
                "la version du cache — sinon l'appli se croit configurée.",
            src.contains("indexEnEchec.isNotEmpty()") && src.contains("return@withContext Pair(0,"),
        )
    }
}
