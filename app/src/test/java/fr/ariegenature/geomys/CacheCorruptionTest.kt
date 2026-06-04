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
import fr.ariegenature.geomys.store.NomValeur
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.OutboxMonitoring
import fr.ariegenature.geomys.store.SaisieEnAttente
import fr.ariegenature.geomys.store.SortieStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Résilience des stores disque face à un cache CORROMPU : fichier/préférence tronqué en
 *  plein kill (JSON invalide) ou JSON parsable mais structurellement faux (entrée nulle,
 *  champ obligatoire manquant, enum inconnue — Gson ne valide pas la non-nullabilité
 *  Kotlin). Aucun de ces cas ne doit crasher ni au chargement, ni à l'usage : on repart
 *  sur un cache vide / on écarte les entrées invalides. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheCorruptionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        NomenclatureCache.init(context)
        NomenclatureCache.vider()
        OutboxMonitoring.init(context)
        OutboxMonitoring.vider()
    }

    // ── NomenclatureCache (SharedPreferences) ──────────────────────────────────────

    private fun corrompreNomenclatures(valeur: String) {
        NomenclatureCache.vider()  // memo mémoire à null → la prochaine lecture relit le disque
        context.getSharedPreferences("nomenclature_cache", Context.MODE_PRIVATE)
            .edit().putString("nom_cache_v1", valeur).putString("nom_defauts_v1", valeur).commit()
    }

    @Test
    fun nomenclatures_json_tronque_repart_sur_cache_vide() {
        corrompreNomenclatures("""{"SEXE":[{"id":1,"la""")  // kill en pleine écriture
        assertTrue(NomenclatureCache.get("SEXE").isEmpty())
        assertEquals(false, NomenclatureCache.estDisponible)
        assertEquals(null, NomenclatureCache.defautPour("SEXE"))
    }

    @Test
    fun nomenclatures_html_au_lieu_de_json_repart_sur_cache_vide() {
        corrompreNomenclatures("<html>quota disque plein</html>")
        assertTrue(NomenclatureCache.get("SEXE").isEmpty())
        // Et le cache reste utilisable en écriture après la corruption.
        NomenclatureCache.setAll(mapOf("SEXE" to listOf(NomValeur(1, "mâle"))))
        assertEquals(1, NomenclatureCache.get("SEXE").size)
    }

    // ── OutboxMonitoring (fichier filesDir/monitoring_outbox.json) ─────────────────

    private fun ecrireOutbox(contenu: String) {
        File(context.filesDir, "monitoring_outbox.json").writeText(contenu)
        OutboxMonitoring.init(context)  // démarrage à froid simulé : relecture du disque
    }

    @Test
    fun outbox_json_tronque_repart_sur_file_vide() {
        ecrireOutbox("""[{"uuid":"u1","moduleCode":"STO""")  // kill en pleine écriture
        assertTrue(OutboxMonitoring.tout().isEmpty())
        assertEquals(0, OutboxMonitoring.countEnAttente())
    }

    @Test
    fun outbox_entree_nulle_ou_incomplete_est_ecartee_sans_crash() {
        // JSON parsable mais invalide : entrée null, entrée sans uuid ni valeurs, état inconnu.
        ecrireOutbox(
            """[
                null,
                {"moduleCode":"STOC"},
                {"uuid":"u-etat","moduleCode":"STOC","objectType":"visite","valeursJson":"{}","etat":"PAS_UN_ETAT"},
                {"uuid":"u-ok","moduleCode":"STOC","objectType":"visite","valeursJson":"{}","etat":"PENDING"}
            ]"""
        )
        val tout = OutboxMonitoring.tout()
        assertEquals("seule l'entrée complète doit survivre", 1, tout.size)
        assertEquals("u-ok", tout.single().uuid)
        // Et l'usage aval ne crash pas (parcours d'arbre, compteur, mise à jour).
        assertEquals(1, OutboxMonitoring.countEnAttente())
        assertTrue(OutboxMonitoring.descendants("u-ok").isEmpty())
        OutboxMonitoring.mettreAJour("u-ok") { it.copy(etat = SaisieEnAttente.Etat.ERROR) }
        assertEquals(SaisieEnAttente.Etat.ERROR, OutboxMonitoring.tout().single().etat)
    }

    @Test
    fun outbox_brouillon_d_une_version_anterieure_charge_avec_listes_vides() {
        // Brouillon écrit AVANT la 0.10.4 : mediaPathsLocal / champsTexteLibre absents du
        // JSON. Gson instancie sans constructeur → ces listes non-nullables arrivaient à
        // null et crashaient au premier copy() (mettreAJour) ou mediasLocaux().
        ecrireOutbox(
            """[{"uuid":"u-legacy","moduleCode":"STOC","objectType":"visite",
                 "valeursJson":"{}","etat":"PENDING","mediaPathLocal":"file:///x.jpg"}]"""
        )
        val s = OutboxMonitoring.tout().single()
        assertTrue(s.nomsChampsSchema.isEmpty())
        assertTrue(s.champsTexteLibre.isEmpty())
        assertEquals("repli legacy mono-fichier", listOf("file:///x.jpg"), s.mediasLocaux())
        // copy() (utilisé par toutes les transitions d'état) ne crashe plus.
        OutboxMonitoring.mettreAJour("u-legacy") { it.copy(etat = SaisieEnAttente.Etat.SENDING) }
        assertEquals(SaisieEnAttente.Etat.SENDING, OutboxMonitoring.tout().single().etat)
    }

    // ── SortieStore (SharedPreferences) ────────────────────────────────────────────

    private fun ecrireSorties(contenu: String) {
        context.getSharedPreferences("sorties_store", Context.MODE_PRIVATE)
            .edit().putString("sorties_sauvegardees", contenu).commit()
    }

    @Test
    fun sorties_json_tronque_repart_sur_liste_vide() {
        ecrireSorties("""[{"id":"s1","observatio""")
        assertTrue(SortieStore(context).charger().isEmpty())
    }

    @Test
    fun sorties_entree_nulle_ou_incomplete_est_ecartee_ou_auto_reparee() {
        // Sortie a tous ses paramètres par défaut → Kotlin génère un constructeur no-arg que
        // Gson UTILISE : une entrée sans id s'auto-répare (id régénéré, listes vides) au lieu
        // d'arriver avec des champs null. Seuls null et les champs explicitement null tombent.
        ecrireSorties("""[null, {"date":123}, {"id":"s-ok","observations":null}]""")
        val sorties = SortieStore(context).charger()
        assertEquals("entrée nulle écartée, sans-id auto-réparée, observations:null écartée",
            1, sorties.size)
        val reparee = sorties.single()
        assertEquals(123L, reparee.date)
        assertTrue("id régénéré par le constructeur par défaut", reparee.id.isNotEmpty())
        // Champs collection absents du JSON → utilisables (non-null) grâce aux défauts.
        assertTrue(reparee.observations.isEmpty())
        assertTrue(reparee.pointsParcours.isEmpty())
    }
}