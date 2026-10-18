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
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.model.OccHabSaisie
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.store.MbtilesStore
import fr.ariegenature.geomys.store.OccHabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * DEUX DÉFAUTS DU §4 DU PLAN DE PARITÉ iOS, instruits et vérifiés côté Android (2026-10-17).
 *
 * A12 — OccHab : un échec survenu AVANT l'émission (login impossible hors-ligne, connexion
 *       refusée) effaçait l'incertitude posée par un envoi précédent resté sans réponse. L'essai
 *       suivant re-POSTait donc sans vérifier par uuid, et le module OccHab n'ayant aucune
 *       contrainte d'unicité, une seconde station apparaissait sur GeoNature.
 *
 * MBTiles — l'import copiait le fichier reçu directement sur sa destination AVANT de le valider :
 *       réimporter une copie corrompue écrasait la carte en place, puis la validation échouée la
 *       supprimait. Perdre une carte valide en tentant de la remplacer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefautsPariteLot2Test {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        MbtilesStore.dossier(ctx).listFiles()?.forEach { it.delete() }
    }

    // ── A12 : l'incertitude survit à un échec qui ne prouve rien ─────────────

    private fun storeAvecStationIncertaine(): Pair<OccHabStore, Pair<String, String>> {
        val store = OccHabStore(ctx)
        store.charger().forEach { store.supprimer(it.id) }
        // `upsertStation` crée la saisie à la première station (chemin réel de la saisie OccHab).
        val saisieId = "saisie-test"
        val station = OccHabStation(envoiIncertain = true)
        store.upsertStation(saisieId, station)
        return store to (saisieId to station.id)
    }

    private fun OccHabStore.station(saisieId: String, stationId: String) =
        charger().first { it.id == saisieId }.stations.first { it.id == stationId }

    @Test
    fun un_echec_avant_emission_ne_leve_pas_lincertitude() {
        val (store, ids) = storeAvecStationIncertaine()
        store.marquerStationErreurSansConclure(ids.first, ids.second, "Pas de réseau")
        val s = store.station(ids.first, ids.second)
        assertTrue(
            "l'incertitude d'un envoi précédent doit survivre : sans elle, l'essai suivant " +
                "re-POSTe sans vérifier par uuid et crée une seconde station",
            s.envoiIncertain,
        )
        assertEquals("Pas de réseau", s.derniereErreurEnvoi)
    }

    /** CONTRE-ÉPREUVE : un rejet FRANC du serveur (4xx) lève bien l'incertitude — sinon chaque
     *  envoi ultérieur paierait une vérification par uuid inutile. */
    @Test
    fun un_rejet_franc_leve_bien_lincertitude() {
        val (store, ids) = storeAvecStationIncertaine()
        store.marquerStationErreur(ids.first, ids.second, "400 Bad Request")
        assertFalse(store.station(ids.first, ids.second).envoiIncertain)
    }

    /** GARDE SUR LE SITE D'APPEL : c'est le `catch (Exception)` d'EnvoiOccHab qui portait le
     *  défaut — il appelait le marquage qui conclut à un échec net. */
    @Test
    fun l_envoi_occhab_ne_conclut_rien_sur_une_exception_non_typee() {
        val src = File("src/main/java/fr/ariegenature/geomys/network/EnvoiOccHab.kt").readText()
        val catchGenerique = src.substringAfterLast("catch (e: Exception)")
        assertTrue(
            "le catch non typé doit employer marquerStationErreurSansConclure : une erreur AVANT " +
                "émission ne dit rien de ce que le serveur a fait.",
            catchGenerique.contains("marquerStationErreurSansConclure"),
        )
    }

    // ── MBTiles : valider avant d'écraser ───────────────────────────────────

    /** Fabrique un MBTiles minimal mais VALIDE (table `tiles`). */
    private fun creerMbtiles(nom: String): File {
        val f = File(MbtilesStore.dossier(ctx), nom)
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            db.execSQL("INSERT INTO metadata VALUES ('name', '$nom')")
        }
        return f
    }

    /** Un fichier « corrompu » : du texte, que SQLite refusera d'ouvrir. */
    private fun uriVersFichierCorrompu(): Uri {
        val src = File(ctx.cacheDir, "carte.mbtiles")
        src.writeText("ceci n'est pas une base SQLite")
        return Uri.fromFile(src)
    }

    @Test
    fun reimporter_une_copie_corrompue_ne_detruit_pas_la_carte_en_place() {
        val enPlace = creerMbtiles("carte.mbtiles")
        val tailleAvant = enPlace.length()

        val resultat = MbtilesStore.importer(ctx, uriVersFichierCorrompu())

        assertNull("un fichier invalide ne doit pas être importé", resultat)
        assertTrue("la carte valide doit être TOUJOURS là", enPlace.exists())
        assertEquals("et intacte", tailleAvant, enPlace.length())
        assertTrue(
            "elle doit rester utilisable",
            MbtilesStore.liste(ctx).any { it.name == "carte.mbtiles" },
        )
    }

    /** CONTRE-ÉPREUVE : un MBTiles valide doit bien remplacer celui en place. */
    @Test
    fun un_mbtiles_valide_remplace_bien_celui_en_place() {
        creerMbtiles("carte.mbtiles")
        val remplacant = creerMbtiles("remplacant.mbtiles").let { f ->
            val copie = File(ctx.cacheDir, "carte.mbtiles")
            f.copyTo(copie, overwrite = true)
            Uri.fromFile(copie)
        }
        val resultat = MbtilesStore.importer(ctx, remplacant)
        assertNotNull("un fichier valide doit être importé", resultat)
        assertEquals("carte.mbtiles", resultat!!.name)
        assertTrue(MbtilesStore.liste(ctx).any { it.name == "carte.mbtiles" })
    }

    /** Aucun fichier temporaire ne doit rester derrière, succès comme échec. */
    @Test
    fun l_import_ne_laisse_aucun_temporaire() {
        creerMbtiles("carte.mbtiles")
        MbtilesStore.importer(ctx, uriVersFichierCorrompu())
        assertTrue(
            "pas de .tmp résiduel — requêtes : ${MbtilesStore.dossier(ctx).listFiles()?.map { it.name }}",
            MbtilesStore.dossier(ctx).listFiles()?.none { it.name.endsWith(".tmp") } ?: true,
        )
    }

    // ── Mono et multi ne communiquent pas ───────────────────────────────────

    /**
     * DÉCISION PRODUIT (2026-10-17) : on choisit son type de saisie à l'accueil, et on n'en change
     * plus. Un appui long sur le bouton Observation de la carte multi ouvrait l'écran mono SUR LA
     * MÊME SESSION — même ViewModel, même sortie, mêmes observations — si bien que « Terminer »
     * depuis l'écran mono clôturait le relevé multi en cours. Le geste n'était documenté nulle
     * part et n'avait aucun chemin retour. Cette garde empêche qu'une passerelle revienne.
     */
    @Test
    fun aucune_passerelle_entre_saisie_mono_et_multi() {
        val trace = File("src/main/java/fr/ariegenature/geomys/ui/TraceFragment.kt").readText()
        val mono = File("src/main/java/fr/ariegenature/geomys/ui/SaisieRapideFragment.kt").readText()
        val navGraph = File("src/main/res/navigation/nav_graph.xml").readText()

        assertFalse(
            "la carte multi ne doit pas ouvrir la saisie mono-taxons",
            trace.contains("saisieRapideFragment") || trace.contains("action_trace_to_saisie_rapide"),
        )
        assertFalse(
            "la saisie mono ne doit pas ouvrir la carte multi",
            mono.contains("action_saisie_rapide_to_trace") || mono.contains("traceFragment"),
        )
        assertFalse(
            "l'action de navigation de la carte vers la saisie mono ne doit plus exister",
            navGraph.contains("action_trace_to_saisie_rapide"),
        )
        // Les deux points d'entrée légitimes, depuis l'accueil, doivent eux rester en place.
        val accueil = File("src/main/java/fr/ariegenature/geomys/ui/AccueilFragment.kt").readText()
        assertTrue(
            "l'accueil doit toujours ouvrir la saisie mono",
            accueil.contains("action_accueil_to_saisie_rapide"),
        )
    }
}
