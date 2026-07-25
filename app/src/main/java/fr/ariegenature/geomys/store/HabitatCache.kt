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
 * Cache local d'une liste HABREF téléchargée à la synchro, pour que le champ habitat fonctionne
 * **hors ligne** et plus vite (recherche locale en mémoire, *contains* insensible aux accents/casse).
 *
 * L'endpoint `GET /api/habref/habitats/autocomplete?limit=<grand>[&id_list=<liste>]` renvoie tout le
 * référentiel (ou la liste restreinte) — récupéré une fois et stocké en fichier (trop gros pour
 * SharedPreferences). Une INSTANCE par périmètre : la liste diffère selon le module
 * (`OCCTAX.ID_LIST_HABITAT` vs `OCCHAB.ID_LIST_HABITAT`). Cf. [[details-releve-champs-standards]],
 * [[occhab-module]].
 */
class HabitatCacheStore(
    private val nomFichier: String,
    /** true (OccHab) = recherche IDENTIQUE au serveur (`ilike` : insensible à la casse mais
     *  SENSIBLE aux accents — « Foret » ne matche pas « Forêts ») pour que la liste locale soit
     *  la même que le web. false (Occtax, historique) = insensible aux accents aussi. */
    private val sensibleAuxAccents: Boolean = false,
) {

    private lateinit var dir: File
    private val gson = Gson()

    @Volatile private var mem: List<HabitatSuggestion>? = null
    // Index de recherche : libellé normalisé (accents/casse retirés) précalculé UNE fois par entrée
    // (sinon chaque frappe renormaliserait des dizaines de milliers de libellés). Aligné sur `mem`.
    @Volatile private var index: List<Pair<HabitatSuggestion, String>>? = null

    fun init(context: Context) { dir = context.filesDir }

    private fun fichier() = File(dir, nomFichier)

    /** Remplace tout le cache (chemin de synchro). Écriture atomique tmp+rename. */
    fun remplacerTout(habitats: List<HabitatSuggestion>) {
        if (!::dir.isInitialized) return
        try {
            val tmp = File(dir, "$nomFichier.tmp")
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

    private fun indexer(): List<Pair<HabitatSuggestion, String>> {
        index?.let { return it }
        return charger().map { it to cleRecherche(it.libelle) }.also { index = it }
    }

    /** Clé de comparaison : minuscules+trim, avec ou sans retrait des accents selon le mode. */
    private fun cleRecherche(s: String): String =
        if (sensibleAuxAccents) s.lowercase().trim() else normaliser(s)

    /** Vrai si la liste a été téléchargée (→ recherche locale possible, y compris hors-ligne). */
    val estDisponible: Boolean get() = charger().isNotEmpty()

    val count: Int get() = charger().size

    /** Recherche locale : *contains* insensible à la casse (et aux accents seulement si
     *  `!sensibleAuxAccents`), priorité aux libellés qui COMMENCENT par le terme, puis tri
     *  alphabétique. En mode sensible aux accents, résultats identiques au `ilike` serveur. */
    fun rechercher(terme: String, limite: Int = 20): List<HabitatSuggestion> {
        val t = cleRecherche(terme)
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

/** Accès commun aux caches HABREF (Occtax / OccHab) — pour pouvoir choisir le cache selon le
 *  contexte dans [fr.ariegenature.geomys.network.HabitatService]. */
interface HabitatCacheAcces {
    val estDisponible: Boolean
    fun rechercher(terme: String, limite: Int = 20): List<HabitatSuggestion>
}

/** Cache HABREF du module Occtax (`OCCTAX.ID_LIST_HABITAT`, ou tout HABREF si absent). */
object HabitatCache : HabitatCacheAcces {
    private val store = HabitatCacheStore("habitats_v1.json")
    fun init(context: Context) = store.init(context)
    fun remplacerTout(habitats: List<HabitatSuggestion>) = store.remplacerTout(habitats)
    override val estDisponible: Boolean get() = store.estDisponible
    val count: Int get() = store.count
    override fun rechercher(terme: String, limite: Int) = store.rechercher(terme, limite)
    fun vider() = store.vider()
}

/** Cache HABREF DÉDIÉ au module OccHab (`OCCHAB.ID_LIST_HABITAT`) — liste distincte d'Occtax,
 *  recherche SENSIBLE aux accents (comme le `ilike` serveur) pour que l'autocomplétion hors-ligne
 *  renvoie exactement les MÊMES valeurs que le formulaire web. */
object HabitatCacheOccHab : HabitatCacheAcces {
    private val store = HabitatCacheStore("habitats_occhab_v1.json", sensibleAuxAccents = true)
    fun init(context: Context) = store.init(context)
    fun remplacerTout(habitats: List<HabitatSuggestion>) = store.remplacerTout(habitats)
    override val estDisponible: Boolean get() = store.estDisponible
    val count: Int get() = store.count
    override fun rechercher(terme: String, limite: Int) = store.rechercher(terme, limite)
    fun vider() = store.vider()
}
