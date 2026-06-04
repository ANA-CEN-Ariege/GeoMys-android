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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.ui.MediaImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** [MediaImport] : les images du picker sont recompressées à l'import (plafond 2048 px,
 *  JPEG) — une photo d'appareil de 4000×3000 ne doit plus partir telle quelle (4-8 Mo)
 *  vers gn_commons. Les autres types sont copiés tels quels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Écrit une « photo » PNG de [largeur]×[hauteur] dans le cache et retourne son Uri. */
    private fun photoTest(largeur: Int, hauteur: Int): Uri {
        val f = File(context.cacheDir, "src_${largeur}x$hauteur.png")
        val bmp = Bitmap.createBitmap(largeur, hauteur, Bitmap.Config.ARGB_8888)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return Uri.fromFile(f)
    }

    @Test
    fun une_grande_photo_est_redimensionnee_et_reencodee_jpeg() {
        val uri = MediaImport.importer(context, photoTest(4000, 3000), "image/png")
        assertNotNull("import doit réussir", uri)
        val fichier = File(Uri.parse(uri!!).path!!)
        assertTrue("le fichier importé existe", fichier.exists())
        assertTrue("réencodé en JPEG : ${fichier.name}", fichier.name.endsWith(".jpg"))
        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(fichier.absolutePath, bornes)
        assertTrue("côté long plafonné à 2048 (reçu ${bornes.outWidth}×${bornes.outHeight})",
            maxOf(bornes.outWidth, bornes.outHeight) <= 2048)
        // Proportions conservées (4:3).
        assertEquals(4f / 3f, bornes.outWidth.toFloat() / bornes.outHeight, 0.01f)
    }

    @Test
    fun une_petite_photo_n_est_pas_agrandie() {
        val uri = MediaImport.importer(context, photoTest(800, 600), "image/png")
        assertNotNull(uri)
        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(File(Uri.parse(uri!!).path!!).absolutePath, bornes)
        assertEquals(800, bornes.outWidth)
        assertEquals(600, bornes.outHeight)
    }

    @Test
    fun un_audio_est_copie_tel_quel() {
        val f = File(context.cacheDir, "chant.mp3").apply { writeBytes(ByteArray(1234) { 7 }) }
        val uri = MediaImport.importer(context, Uri.fromFile(f), "audio/mpeg")
        assertNotNull(uri)
        val copie = File(Uri.parse(uri!!).path!!)
        assertTrue("préfixe audio_ et extension mp3 : ${copie.name}",
            copie.name.startsWith("audio_") && copie.name.endsWith(".mp3"))
        assertEquals("copie binaire à l'identique", 1234L, copie.length())
    }
}