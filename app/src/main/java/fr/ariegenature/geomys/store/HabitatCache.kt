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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geomys.network.HabitatSuggestion
import java.io.File

/**
 * Cache local de la liste COMPLÈTE des habitats HABREF, téléchargée à la synchro (« Recharger les
 * données »), pour que le champ habitat fonctionne **hors ligne** et plus vite (recherche locale).
 *
 * L'endpoint `GET /api/habref/habitats/autocomplete?limit=<grand>` (sans `search_name`) renvoie tout
 * le référentiel (~29 000 habitats, ~5 Mo sur l'ANA) — on le récupère une fois et on le stocke en
 * fichier (trop gros pour SharedPreferences). La recherche se fait ensuite en mémoire (contains
 * insensible aux accents/casse). HABREF étant national, le cache vaut quel que soit le serveur.
 * Cf. [[details-releve-champs-standards]].
 */
object HabitatCache {

    private const val FICHIER = "habitats_v1.json"

    private lateinit var dir: File
    private val gson = Gson()

    @Volatile private var mem: List<HabitatSuggestion>? = null
    // Index de recherche : libellé normalisé (accents/casse retirés) précalculé UNE fois par entrée.
    // Sans lui, chaque frappe renormalisait les ~29 000 libellés (+ une regex recompilée par entrée),
    // d'où l'autocomplétion très lente. Aligné sur `mem` (reconstruit à chaque remplacement).
    @Volatile private var index: List<Pair<HabitatSuggestion, String>>? = null

    fun init(context: Context) { dir = context.filesDir }

    private fun fichier() = File(dir, FICHIER)

    /** Remplace tout le cache (chemin de synchro). Écriture atomique tmp+rename. */
    fun remplacerTout(habitats: List<HabitatSuggestion>) {
        if (!::dir.isInitialized) return
        try {
            val tmp = File(dir, "$FICHIER.tmp")
            tmp.writeText(gson.toJson(habitats))
            val cible = fichier()
            if (!tmp.renameTo(cible)) { cible.delete(); tmp.renameTo(cible) }
            mem = habitats
            index = null
        } catch (_: Exception) { /* cache best-effort */ }
    }

    private fun charger(): List<HabitatSuggestion> {
        mem?.let { return it }
        if (!::dir.isInitialized) return emptyList()
        val f = fichier()
        if (!f.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<HabitatSuggestion>>() {}.type
            (gson.fromJson<List<HabitatSuggestion>>(f.readText(), type) ?: emptyList()).also { mem = it }
        } catch (_: Exception) { emptyList() }
    }

    /** Index normalisé, construit paresseusement une seule fois (coûteux : ~29 000 normalisations). */
    private fun indexer(): List<Pair<HabitatSuggestion, String>> {
        index?.let { return it }
        return charger().map { it to normaliser(it.libelle) }.also { index = it }
    }

    /** Vrai si la liste complète a été téléchargée (→ recherche locale possible, y compris hors-ligne). */
    val estDisponible: Boolean get() = charger().isNotEmpty()

    val count: Int get() = charger().size

    /** Recherche locale : *contains* insensible aux accents/casse sur le libellé, priorité aux
     *  libellés qui COMMENCENT par le terme, puis tri alphabétique. Les libellés sont normalisés
     *  une fois pour toutes (cf. [indexer]) — la frappe ne fait plus que filtrer/trier. */
    fun rechercher(terme: String, limite: Int = 20): List<HabitatSuggestion> {
        val t = normaliser(terme)
        if (t.length < 2) return emptyList()
        val (debut, autres) = indexer().asSequence()
            .filter { it.second.contains(t) }
            .partition { it.second.startsWith(t) }
        return (debut.sortedBy { it.second } + autres.sortedBy { it.second })
            .asSequence().map { it.first }.take(limite).toList()
    }

    fun vider() {
        if (::dir.isInitialized) runCatching { fichier().delete() }
        mem = null
        index = null
    }

    private val accentsMn = "\\p{Mn}+".toRegex()

    private fun normaliser(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(accentsMn, "")
            .lowercase()
            .trim()
}
