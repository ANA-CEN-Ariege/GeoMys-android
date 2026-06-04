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

package fr.ariegenature.geomys.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File

/** Import des médias choisis au picker vers `filesDir/medias/` (copie locale qui survit au
 *  cycle de vie de l'Uri ACTION_GET_CONTENT). Partagé entre les flux monitoring
 *  (NouvelleVisiteFragment) et OCCTAX (DenombrementFragment) — c'était deux copies.
 *
 *  Les IMAGES sont RECOMPRESSÉES à l'import : redimensionnées à [COTE_MAX] px (côté long),
 *  orientation EXIF appliquée, réencodées JPEG q[QUALITE_JPEG]. Une photo d'appareil
 *  moderne (12-50 Mpx, 4-10 Mo) devient ~0,3-1 Mo : décisif pour l'upload en zone blanche
 *  (3G : 8 Mo ≈ 2-3 min → ~15 s), sans perte utile à l'identification naturaliste, et le
 *  stockage local ne gonfle plus au fil de la saison. Les autres types (audio…) et les
 *  images non décodables sont copiés tels quels. */
object MediaImport {

    private const val COTE_MAX = 2048
    private const val QUALITE_JPEG = 85

    /** Importe [source] et retourne l'URI `file://…` de la copie locale, ou null en échec
     *  (l'appelant informe l'utilisateur). [index] désambiguïse les fichiers importés dans
     *  la même milliseconde (multi-sélection). */
    fun importer(context: Context, source: Uri, defaultMime: String, index: Int = 0): String? {
        val mime = context.contentResolver.getType(source) ?: defaultMime
        val dir = File(context.filesDir, "medias").apply { mkdirs() }
        val horodatage = System.currentTimeMillis()
        return try {
            if (mime.startsWith("image/")) {
                comprimerImage(context, source, File(dir, "photo_${horodatage}_$index.jpg"))
                    ?.let { return it }
                // Image non décodable (format exotique) → repli copie brute ci-dessous.
            }
            val (prefixe, ext) = prefixeEtExtension(mime)
            val dest = File(dir, "${prefixe}_${horodatage}_$index.$ext")
            context.contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            dest.toURI().toString()
        } catch (e: Exception) {
            android.util.Log.w("MediaImport", "Import média échoué : $source", e)
            null
        }
    }

    /** Décode [source] sous-échantillonnée, redimensionne au plafond, applique l'orientation
     *  EXIF et réencode en JPEG vers [dest]. Null si l'image n'est pas décodable. */
    private fun comprimerImage(context: Context, source: Uri, dest: File): String? {
        // 1) Dimensions seules (pas d'allocation) pour calculer le sous-échantillonnage.
        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, bornes)
        } ?: return null
        if (bornes.outWidth <= 0 || bornes.outHeight <= 0) return null
        var inSampleSize = 1
        while (maxOf(bornes.outWidth, bornes.outHeight) / (inSampleSize * 2) >= COTE_MAX) {
            inSampleSize *= 2
        }
        // 2) Décodage sous-échantillonné (puissance de 2) puis redimensionnement exact.
        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        var bitmap = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val coteLong = maxOf(bitmap.width, bitmap.height)
        if (coteLong > COTE_MAX) {
            val ratio = COTE_MAX.toFloat() / coteLong
            val redim = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            if (redim !== bitmap) { bitmap.recycle(); bitmap = redim }
        }
        // 3) Orientation EXIF : les photos d'appareil sont souvent stockées pivotées +
        // marqueur — le réencodage JPEG perdrait le marqueur, on applique la rotation
        // aux pixels.
        val rotation = runCatching {
            context.contentResolver.openInputStream(source)?.use {
                when (ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (rotation != 0f) {
            val matrice = Matrix().apply { postRotate(rotation) }
            val pivote = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrice, true)
            if (pivote !== bitmap) { bitmap.recycle(); bitmap = pivote }
        }
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITE_JPEG, it) }
        bitmap.recycle()
        return dest.toURI().toString()
    }

    /** Préfixe de nom + extension de fichier pour les médias copiés bruts. */
    private fun prefixeEtExtension(mime: String): Pair<String, String> = when {
        mime.startsWith("image/") -> "photo" to when (mime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/heic", "image/heif" -> "heic"
            "image/webp" -> "webp"
            else -> mime.substringAfter("/").ifEmpty { "jpg" }
        }
        mime.startsWith("audio/") -> "audio" to when (mime) {
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> mime.substringAfter("/").ifEmpty { "mp3" }
        }
        else -> "media" to mime.substringAfter("/").ifEmpty { "bin" }
    }
}