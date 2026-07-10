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

package fr.ariegenature.geomys.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Gère les fonds de carte HORS-LIGNE au format **MBTiles** (conteneur SQLite de tuiles)
 *  importés par l'utilisateur. Les fichiers sont copiés dans `filesDir/mbtiles/` pour rester
 *  disponibles indépendamment de l'Uri d'origine (qui peut expirer). osmdroid sait ensuite les
 *  servir via `OfflineTileProvider` (cf. ui/MapUtils `appliquerFond`). */
object MbtilesStore {

    /** Métadonnées utiles d'un fichier MBTiles (lues dans la table `metadata` / `tiles`). */
    data class Info(
        val minZoom: Int,
        val maxZoom: Int,
        /** "png" ou "jpg" (pour l'extension de la source osmdroid ; le décodage auto-détecte). */
        val format: String,
        /** Nom déclaré dans le fichier (metadata.name), sinon null. */
        val nom: String?,
    )

    fun dossier(context: Context): File =
        File(context.filesDir, "mbtiles").apply { if (!exists()) mkdirs() }

    /** Fichiers .mbtiles importés, triés par nom. */
    fun liste(context: Context): List<File> =
        dossier(context).listFiles { f -> f.isFile && f.name.endsWith(".mbtiles", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Copie le fichier pointé par [uri] dans le dossier MBTiles de l'appli. Retourne le
     *  fichier local, ou null en cas d'échec (lecture impossible, pas un .mbtiles valide). */
    fun importer(context: Context, uri: Uri): File? {
        val nomBrut = nomDepuisUri(context, uri) ?: "carte_${System.currentTimeMillis()}.mbtiles"
        val nom = nettoyerNom(nomBrut)
        val dest = File(dossier(context), nom)
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                dest.outputStream().use { input.copyTo(it) }
            }
            // Validation : le fichier doit être un MBTiles ouvrable (sinon on ne le garde pas).
            if (!estMbtilesValide(dest)) { dest.delete(); null } else dest
        } catch (e: Exception) {
            android.util.Log.w("MbtilesStore", "import échoué : ${e.message}", e)
            dest.delete()
            null
        }
    }

    fun supprimer(fichier: File): Boolean = runCatching { fichier.delete() }.getOrDefault(false)

    /** Nom lisible : le `metadata.name` du fichier s'il existe, sinon le nom de fichier sans
     *  l'extension. */
    fun nomAffichage(fichier: File): String =
        info(fichier).nom?.takeIf { it.isNotBlank() } ?: fichier.name.removeSuffix(".mbtiles")

    /** Lit les métadonnées du fichier. Valeurs de repli si absentes/illisibles : zoom 0–20,
     *  format png (osmdroid décode png ET jpg quel que soit ce champ, il ne sert qu'à
     *  l'extension de la source). */
    fun info(fichier: File): Info {
        var min = 0; var max = 20; var format = "png"; var nom: String? = null
        ouvrir(fichier)?.use { db ->
            runCatching {
                db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                    while (c.moveToNext()) {
                        when (c.getString(0)?.lowercase()) {
                            "minzoom" -> c.getString(1)?.toIntOrNull()?.let { min = it }
                            "maxzoom" -> c.getString(1)?.toIntOrNull()?.let { max = it }
                            "format"  -> c.getString(1)?.let { format = if (it.startsWith("jpg") || it.startsWith("jpeg")) "jpg" else "png" }
                            "name"    -> nom = c.getString(1)
                        }
                    }
                }
            }
            // Repli si metadata sans zoom : min/max depuis la table des tuiles.
            if (min == 0 && max == 20) runCatching {
                db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) { min = c.getInt(0); max = c.getInt(1) }
                }
            }
        }
        if (max < min) max = min
        return Info(min, max, format, nom)
    }

    // ── interne ──────────────────────────────────────────────────────────────────────────

    private fun estMbtilesValide(fichier: File): Boolean =
        ouvrir(fichier)?.use { db ->
            runCatching {
                db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='tiles'", null)
                    .use { it.moveToFirst() && it.getInt(0) > 0 }
            }.getOrDefault(false)
        } ?: false

    private fun ouvrir(fichier: File): SQLiteDatabase? = runCatching {
        SQLiteDatabase.openDatabase(fichier.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }.getOrNull()

    private fun nomDepuisUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    /** Nom de fichier sûr, extension .mbtiles garantie. */
    private fun nettoyerNom(nom: String): String {
        val base = nom.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (base.endsWith(".mbtiles", ignoreCase = true)) base else "$base.mbtiles"
    }
}
