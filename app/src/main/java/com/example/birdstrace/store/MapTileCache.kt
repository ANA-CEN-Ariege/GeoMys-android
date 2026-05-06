package com.example.birdstrace.store

import android.content.Context
import android.util.Log
import org.osmdroid.config.Configuration
import java.io.File

object MapTileCache {

    private const val PREFS_KEY = "derniere_purge_tuiles"
    private const val DUREE_SEMAINE_MS = 7L * 24 * 3600 * 1000
    private const val AGE_MAX_MS = 30L * 24 * 3600 * 1000
    private const val CACHE_MAX_BYTES = 100L * 1024 * 1024  // 100 Mo

    fun configurer(context: Context) {
        val cacheDir = File(context.cacheDir, "MapTiles")
        cacheDir.mkdirs()
        Configuration.getInstance().osmdroidTileCache = cacheDir
        Configuration.getInstance().tileFileSystemCacheMaxBytes = CACHE_MAX_BYTES
        Configuration.getInstance().tileFileSystemCacheTrimBytes = CACHE_MAX_BYTES * 3 / 4
    }

    fun purgerSiNecessaire(context: Context) {
        val prefs = context.getSharedPreferences("GeoNat_prefs", Context.MODE_PRIVATE)
        val dernierePurge = prefs.getLong(PREFS_KEY, 0L)
        if (System.currentTimeMillis() - dernierePurge < DUREE_SEMAINE_MS) return

        Thread {
            val cacheDir = Configuration.getInstance().osmdroidTileCache
            if (!cacheDir.exists()) return@Thread

            val limite = System.currentTimeMillis() - AGE_MAX_MS
            var nbPurgees = 0
            var octetsLiberes = 0L

            cacheDir.walkTopDown()
                .filter { it.isFile && it.lastModified() < limite }
                .forEach { fichier ->
                    octetsLiberes += fichier.length()
                    if (fichier.delete()) nbPurgees++
                }

            if (nbPurgees > 0) {
                Log.d("TuileCache", "$nbPurgees tuiles purgées (${octetsLiberes / 1024} Ko libérés)")
            }

            prefs.edit().putLong(PREFS_KEY, System.currentTimeMillis()).apply()
        }.start()
    }
}
