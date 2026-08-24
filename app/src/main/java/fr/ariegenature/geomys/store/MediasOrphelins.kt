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
import java.io.File

/**
 * Balayage des MÉDIAS ORPHELINS de `filesDir/medias/` (photos/audio importés par MediaImport,
 * 0,3-1 Mo pièce). AUCUN chemin ne supprimait ces fichiers après envoi ou suppression d'une
 * saisie (audit 2026-08-23, M-N2) : sur une saison de terrain, des centaines de Mo de médias
 * déjà envoyés ou dont la saisie a été supprimée s'accumulaient sur l'appareil.
 *
 * Stratégie : plutôt que de purger à chaque suppression (en devant vérifier qu'aucune AUTRE
 * entrée ne référence le même fichier), un BALAYAGE au démarrage supprime les fichiers que
 * plus aucun store ne référence — couvre d'un coup tous les chemins d'abandon (envoi réussi
 * puis purge, suppression de saisie, crash entre import et enregistrement).
 *
 * Garde d'ÂGE (48 h) : un média tout juste importé peut n'être référencé que par l'état d'un
 * formulaire EN COURS (valeurs non encore écrites dans un store — ex. photo ajoutée à une
 * visite pas encore enregistrée). On ne touche donc qu'aux fichiers vieux de plus de 48 h,
 * fenêtre largement suffisante pour terminer une saisie entamée.
 */
object MediasOrphelins {

    private const val AGE_MINIMUM_MS = 48L * 3600 * 1000

    /** Lance le balayage (BLOQUANT — appeler hors thread principal, cf. GeoMysApplication).
     *  Best-effort : toute erreur est avalée, le balayage retentera au prochain démarrage.
     *  Renvoie le nombre de fichiers supprimés (log / tests). */
    fun purger(context: Context): Int {
        return try {
            val dossier = File(context.filesDir, "medias")
            val fichiers = dossier.listFiles()?.filter { it.isFile } ?: return 0
            if (fichiers.isEmpty()) return 0

            // Ensemble des NOMS de fichiers référencés par les stores. Comparaison par nom :
            // les références mélangent URIs file:///… et chemins absolus, mais les noms générés
            // par MediaImport (horodatage+aléa) sont uniques dans le dossier.
            val references = HashSet<String>()
            fun referencer(uri: String?) {
                val nom = uri?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return
                references.add(nom)
            }
            // Occtax : médias portés par les observations (dénombrement 0 + additionnels).
            SortieStore(context).charger().forEach { sortie ->
                sortie.observations.forEach { obs ->
                    obs.mediaUrisCounting0.forEach { referencer(it) }
                    obs.denombrementsAdditionnels.forEach { d -> d.mediaUris.forEach { referencer(it) } }
                }
            }
            // Monitoring : médias des saisies en attente (multi-pj + champ legacy mono).
            OutboxMonitoring.tout().forEach { saisie ->
                saisie.mediasLocaux().forEach { referencer(it) }
            }

            val seuil = System.currentTimeMillis() - AGE_MINIMUM_MS
            var supprimes = 0
            fichiers.forEach { f ->
                if (f.name !in references && f.lastModified() < seuil && f.delete()) supprimes++
            }
            if (supprimes > 0) {
                android.util.Log.i("MediasOrphelins", "$supprimes média(s) orphelin(s) purgé(s)")
            }
            supprimes
        } catch (_: Exception) { 0 }
    }
}
