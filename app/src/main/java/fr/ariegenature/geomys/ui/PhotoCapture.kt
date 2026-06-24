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
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Prise de photo directe par l'appareil photo système (contrat ActivityResultContracts.TakePicture).
 *  Crée le fichier cible (sous `cacheDir/captures/`, couvert par `file_provider_paths.xml`) et son
 *  URI `content://` via le FileProvider de l'app — autorité `${applicationId}.provider`, donc
 *  correcte pour chaque flavor (github / play).
 *
 *  Ce fichier est TEMPORAIRE : la photo prise y est écrite en pleine résolution, puis [MediaImport]
 *  en fait une copie recompressée (2048 px / JPEG) dans `filesDir/medias/` exactement comme une
 *  photo de galerie. L'appelant supprime le temp une fois l'import terminé. */
object PhotoCapture {

    /** Retourne le fichier cache cible et son URI FileProvider pour une nouvelle capture. */
    fun nouvelleCible(context: Context): Pair<File, Uri> {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val fichier = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", fichier)
        return fichier to uri
    }
}
