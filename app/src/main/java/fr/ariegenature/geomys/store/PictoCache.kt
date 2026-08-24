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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cache DISQUE des pictogrammes de protocole monitoring (une image `<module_code>.jpg`).
 *
 * Les images viennent de la CONVENTION serveur `<base>/api/media/monitorings/<code>/img.jpg`
 * (comme le web du module Suivi), INDÉPENDANTE de `module_picto` (souvent au défaut
 * « fa-puzzle-piece »). Objectif : affichage **instantané, persistant et HORS-LIGNE**, sans
 * re-télécharger à chaque ouverture de l'écran Suivis.
 *
 * Cycle de vie calqué sur les autres caches : [init] dans l'Application, méthodes sans Context.
 */
object PictoCache {
    private lateinit var dossier: File

    fun init(context: Context) {
        dossier = File(context.filesDir, "monitoring/pictos").apply { mkdirs() }
    }

    /** Init pour les tests JVM (pas de Context) : pointe le cache sur un dossier arbitraire.
     *  Même contrat que [init] (le dossier est créé). */
    @androidx.annotation.VisibleForTesting
    fun initPourTests(dossierTest: File) {
        dossier = dossierTest.apply { mkdirs() }
    }

    private fun fichier(moduleCode: String): File =
        File(dossier, moduleCode.replace('/', '_') + ".jpg")

    /** Fichier local du picto s'il est en cache (présent et non vide), sinon null. */
    fun fichierLocal(moduleCode: String): File? =
        fichier(moduleCode).takeIf { it.isFile && it.length() > 0 }

    /** URL du picto : image explicite portée par `module_picto` si c'en est une (URL/chemin/
     *  fichier image), sinon la convention monitoring `<base>/api/media/monitorings/<code>/img.jpg`. */
    fun urlPicto(base: String, moduleCode: String, modulePicto: String?): String {
        val b = base.trim().trimEnd('/')
        val p = modulePicto?.trim().orEmpty()
        if (p.startsWith("http://", true) || p.startsWith("https://", true)) return p
        val estImage = p.startsWith("/") ||
            Regex(".+\\.(png|jpe?g|gif|webp|svg)$", RegexOption.IGNORE_CASE).matches(p)
        if (estImage) return "$b${if (p.startsWith("/")) p else "/$p"}"
        return "$b/api/media/monitorings/$moduleCode/img.jpg"
    }

    /** Télécharge le picto depuis [url] et l'écrit sur disque (tmp + rename atomique). Renvoie le
     *  fichier en cas de succès, null sinon (404, réseau…). BLOQUANT → appeler hors thread UI. */
    fun telecharger(moduleCode: String, url: String): File? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        if (conn.responseCode != 200) {
            conn.disconnect(); null
        } else {
            val octets = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (octets.isEmpty() || !estImageValide(octets)) null else {
                // Recrée le dossier si besoin : MonitoringCache.vider() (changement d'identité
                // serveur, « Vider le cache ») supprimait le sous-dossier pictos/ quand il était
                // VIDE (1ʳᵉ configuration) → toutes les écritures échouaient ensuite en silence,
                // plus aucun picto ne s'affichait (bug terrain 2026-08-24).
                dossier.mkdirs()
                val cible = fichier(moduleCode)
                val tmp = File(dossier, cible.name + ".tmp")
                tmp.writeBytes(octets)
                // Rename atomique uniquement : le repli copyTo laissait une fenêtre « fichier
                // tronqué » (kill pendant la copie) qui serait resservie hors-ligne à jamais.
                // Un rename qui échoue = pas de mise à jour (l'ancienne image, si présente,
                // reste servie ; sinon repli emoji côté UI) — dégradation sûre.
                if (!tmp.renameTo(cible)) { tmp.delete(); null } else cible
            }
        }
    } catch (_: Exception) { null }

    /** Vrai si [octets] est décodable en image. Un serveur qui répond 200 avec une page HTML
     *  (portail captif, proxy, page d'erreur) ne doit PAS être persisté : le fichier invalide
     *  serait resservi hors-ligne indéfiniment (audit 2026-08-23). Décodage bounds-only,
     *  quasi gratuit (pas d'allocation bitmap). */
    private fun estImageValide(octets: ByteArray): Boolean {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(octets, 0, octets.size, opts)
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    /** Fichier local s'il existe (offline + instantané), sinon téléchargement + enregistrement.
     *  BLOQUANT → appeler hors thread UI. Renvoie null si aucune image (404) pour ce protocole. */
    fun fichierOuTelecharger(base: String, moduleCode: String, modulePicto: String?): File? =
        fichierLocal(moduleCode) ?: telecharger(moduleCode, urlPicto(base, moduleCode, modulePicto))

    /** Prefetch (rafraîchissement) des pictos de [modules] (`module_code` → `module_picto`) pour
     *  l'usage HORS-LIGNE. Best-effort : un échec par module n'interrompt pas les autres. Re-écrit
     *  les images existantes (rafraîchissement au « Recharger les données »). BLOQUANT. */
    fun prefetch(base: String, modules: List<Pair<String, String?>>) {
        modules.forEach { (code, picto) ->
            runCatching { telecharger(code, urlPicto(base, code, picto)) }
        }
    }

    /** Vide tout le cache des pictos (« Vider le cache » / changement d'identité serveur). */
    fun vider() {
        if (::dossier.isInitialized) runCatching { dossier.listFiles()?.forEach { it.delete() } }
    }
}
