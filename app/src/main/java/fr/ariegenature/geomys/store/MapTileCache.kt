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
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

object MapTileCache {

    private const val PREFS_KEY = "derniere_purge_tuiles"
    private const val DUREE_SEMAINE_MS = 7L * 24 * 3600 * 1000
    private const val AGE_MAX_MS = 30L * 24 * 3600 * 1000
    /** Plafond du cache des tuiles : 1 Go. Couvre 2-3 zones complètes (200 km² × 4 fonds)
     *  avant que la purge LRU automatique d'osmdroid ne kicke. À ~1 % d'un téléphone 128 Go,
     *  ça reste raisonnable même sur stockage modeste. */
    const val CACHE_MAX_BYTES = 1024L * 1024 * 1024
    /** Seuil cible après purge LRU automatique : 800 Mo. Quand le cache atteint
     *  [CACHE_MAX_BYTES], osmdroid supprime les tuiles les plus anciennes jusqu'à
     *  redescendre sous ce seuil. */
    const val CACHE_TRIM_BYTES = 800L * 1024 * 1024

    fun configurer(context: Context) {
        val cacheDir = File(context.cacheDir, "MapTiles")
        cacheDir.mkdirs()
        Configuration.getInstance().osmdroidTileCache = cacheDir
        Configuration.getInstance().tileFileSystemCacheMaxBytes = CACHE_MAX_BYTES
        Configuration.getInstance().tileFileSystemCacheTrimBytes = CACHE_TRIM_BYTES
    }

    /** Taille actuelle du cache des tuiles en octets. Inclut la SQLite + les fichiers
     *  bruts qu'osmdroid peut écrire à côté selon la version. Best-effort : retourne 0 si
     *  le dossier n'existe pas encore (premier lancement). */
    fun tailleActuelleBytes(): Long {
        val racine = Configuration.getInstance().osmdroidTileCache ?: return 0L
        if (!racine.exists()) return 0L
        return runCatching {
            racine.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /** Vide réellement le cache des tuiles. osmdroid stocke les tuiles dans une SQLite
     *  (`cache.db`) ouverte par le tileProvider — un `deleteRecursively()` ne suffit pas
     *  car le handle reste actif. On passe par [SqlTileWriter.purgeCache] qui exécute un
     *  DELETE FROM tiles sur la DB, suivi d'un VACUUM. */
    fun viderCache(): Boolean {
        val writer = runCatching {
            org.osmdroid.tileprovider.modules.SqlTileWriter()
        }.getOrNull() ?: return false
        val ok = runCatching { writer.purgeCache() }.getOrDefault(false)
        runCatching { writer.onDetach() }
        // Pour les fonds qui ont pu écrire en dehors de la SQLite (rare, mais possible
        // selon la version d'osmdroid), suppression best-effort des fichiers résiduels.
        runCatching {
            val racine = Configuration.getInstance().osmdroidTileCache
            if (racine != null && racine.exists()) {
                racine.walkBottomUp()
                    .filter { it.isFile && !it.name.endsWith(".db") }
                    .forEach { it.delete() }
            }
        }
        return ok
    }

    /** Handler explicite : sans lui, une exception non interceptée dans `scope.launch`
     *  remonte à `Thread.UncaughtExceptionHandler` et crashe l'app. */
    private val swallowExceptions = CoroutineExceptionHandler { _, e ->
        Log.w("TuileCache", "Purge échouée : ${e.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + swallowExceptions)

    fun purgerSiNecessaire(context: Context) {
        val prefs = context.getSharedPreferences("GeoMys_prefs", Context.MODE_PRIVATE)
        val dernierePurge = prefs.getLong(PREFS_KEY, 0L)
        if (System.currentTimeMillis() - dernierePurge < DUREE_SEMAINE_MS) return

        scope.launch {
            val cacheDir = Configuration.getInstance().osmdroidTileCache
            if (!cacheDir.exists()) return@launch

            val limite = System.currentTimeMillis() - AGE_MAX_MS
            var nbPurgees = 0
            var octetsLiberes = 0L

            cacheDir.walkTopDown()
                // ⚠ Exclut la base SQLite d'osmdroid (cache.db + journaux -wal/-shm/-journal) :
                // elle contient les zones TÉLÉCHARGÉES pour le hors-ligne, et sa simple
                // consultation ne rafraîchit pas son mtime — la purge par âge supprimait toute
                // la cartographie offline d'un naturaliste qui n'avait rien re-téléchargé
                // depuis 30 jours. Sa taille est déjà bornée par la LRU osmdroid (1 Go/800 Mo),
                // même filtre que viderCache().
                .filter { it.isFile && it.lastModified() < limite && !it.name.contains(".db") }
                .forEach { fichier ->
                    octetsLiberes += fichier.length()
                    if (fichier.delete()) nbPurgees++
                }

            if (nbPurgees > 0) {
                Log.d("TuileCache", "$nbPurgees tuiles purgées (${octetsLiberes / 1024} Ko libérés)")
            }

            prefs.edit().putLong(PREFS_KEY, System.currentTimeMillis()).apply()
        }
    }
}
