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
import fr.ariegenature.geomys.store.MbtilesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Import d'une carte hors-ligne `.mbtiles` ([MbtilesStore]) : validation SQLite (table `tiles`),
 *  métadonnées (zooms, format, nom), nom de fichier assaini, rejet propre d'un fichier invalide. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MbtilesStoreTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        MbtilesStore.liste(ctx).forEach { it.delete() }
    }

    private fun creerMbtiles(nom: String, avecTiles: Boolean): File {
        val f = File(tmp.root, nom)
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            db.execSQL("INSERT INTO metadata VALUES ('name','Ariège'),('minzoom','8'),('maxzoom','14'),('format','jpg')")
            if (avecTiles) {
                db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
                db.execSQL("INSERT INTO tiles VALUES (8, 1, 1, x'00'), (14, 2, 2, x'00')")
            }
        }
        return f
    }

    @Test
    fun fichier_valide_importe_nom_assaini_et_infos_lues() {
        val src = creerMbtiles("Carte Ariège.mbtiles", avecTiles = true)
        val dest = MbtilesStore.importer(ctx, Uri.fromFile(src))
        assertNotNull(dest)
        assertEquals("Carte_Ari_ge.mbtiles", dest!!.name)
        assertTrue(dest.exists() && dest.length() > 0)
        val info = MbtilesStore.info(dest)
        assertEquals(8, info.minZoom)
        assertEquals(14, info.maxZoom)
        assertEquals("jpg", info.format)
        assertEquals("Ariège", info.nom)
        assertEquals("Ariège", MbtilesStore.nomAffichage(dest))
        assertEquals(listOf(dest.name), MbtilesStore.liste(ctx).map { it.name })
        assertTrue(MbtilesStore.supprimer(dest))
        assertTrue(MbtilesStore.liste(ctx).isEmpty())
    }

    @Test
    fun fichier_sans_table_tiles_rejete_et_nettoye() {
        val src = creerMbtiles("pas_une_carte.mbtiles", avecTiles = false)
        assertNull(MbtilesStore.importer(ctx, Uri.fromFile(src)))
        assertFalse("aucun résidu dans le dossier", File(MbtilesStore.dossier(ctx), "pas_une_carte.mbtiles").exists())
        // Fichier quelconque (pas SQLite) : rejeté aussi.
        val texte = File(tmp.root, "notes.mbtiles").apply { writeText("ceci n'est pas une base") }
        assertNull(MbtilesStore.importer(ctx, Uri.fromFile(texte)))
    }

    @Test
    fun zooms_deduits_des_tuiles_sans_metadonnees() {
        val f = File(tmp.root, "brut.mbtiles")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            db.execSQL("INSERT INTO tiles VALUES (10, 1, 1, x'00'), (12, 2, 2, x'00')")
        }
        val info = MbtilesStore.info(f)
        assertEquals(10, info.minZoom)
        assertEquals(12, info.maxZoom)
        assertEquals("png", info.format)
        assertNull(info.nom)
        assertEquals("brut", MbtilesStore.nomAffichage(f))
    }
}
