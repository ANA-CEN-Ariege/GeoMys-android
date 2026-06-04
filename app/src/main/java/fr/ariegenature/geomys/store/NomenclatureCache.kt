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
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class TaxrefRestriction(val regne: String, val group2Inpn: String)
data class NomValeur(val id: Int, val label: String, val taxref: List<TaxrefRestriction> = emptyList())

object NomenclatureCache {
    private const val KEY = "nom_cache_v1"
    private const val KEY_DEFAUTS = "nom_defauts_v1"
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    // @Volatile : écrits par le thread de sync, lus par le thread UI à la saisie (visibilité
    // inter-thread de la référence, cohérent avec TaxRefCache).
    @Volatile private var mem: Map<String, List<NomValeur>>? = null
    @Volatile private var memDefauts: Map<String, String>? = null

    // Valeurs group2_inpn correspondant aux poissons dans TaxRef v16 et v17 (même liste iOS)
    val GROUP2_POISSONS: Set<String> = setOf(
        "Poissons osseux", "Poissons cartilagineux", "Agnathes", "Poissons lamproies", "Poissons",
        "Actinoptérygiens", "Chondrichtyens", "Myxines", "Cœlacanthes",
        "Dipneustes", "Sarcoptérygiens", "Actinistiens",
        "Poissons marins", "Poissons d'eau douce"
    )

    val GROUPES_FONGE: Set<String> = setOf(
        "Champignons", "Basidiomycètes", "Ascomycètes", "Myxomycètes",
        "Chytridiomycètes", "Zygomycètes", "Glomeromycètes"
    )

    // Flore : group1_inpn utilisés par GeoNature pour les plantes
    val GROUPES1_FLORE: Set<String> = setOf("Phanérogames", "Ptéridophytes", "Bryophytes")

    // Groupes botaniques connus dans INPN/TaxRef (même liste que l'app iOS)
    val GROUPES_BOTANIQUES: Set<String> = setOf(
        "Angiospermes", "Gymnospermes", "Ptéridophytes",
        "Mousses", "Hépatiques et anthocérotes",
        "Lichens", "Chlorophytes et charophytes", "Rhodophytes",
        "Trachéophytes", "Bryophytes", "Plantes"
    )

    // Intersection des groupes botaniques connus avec ceux présents dans le cache TaxRef.
    // Fallback sur tous les groupes botaniques si aucun n'est encore synchronisé.
    fun groupesBotaniquesConnus(): Set<String> {
        val connus = TaxRefCache.comptesGroupes.keys.toSet()
        val intersection = connus.intersect(GROUPES_BOTANIQUES)
        return if (intersection.isEmpty()) GROUPES_BOTANIQUES else intersection
    }

    // Déduit le regno (Plantae / Animalia / Fungi…) depuis le group2_inpn.
    fun regno(pourGroupe: String): String = when {
        GROUPES_BOTANIQUES.contains(pourGroupe) -> "Plantae"
        GROUPES_FONGE.contains(pourGroupe)      -> "Fungi"
        else -> "Animalia"
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("nomenclature_cache", Context.MODE_PRIVATE)
    }

    fun setAll(data: Map<String, List<NomValeur>>) {
        prefs.edit().putString(KEY, gson.toJson(data)).apply()
        mem = data
    }

    fun get(type: String): List<NomValeur> = charger()[type] ?: emptyList()

    val estDisponible: Boolean get() = charger().isNotEmpty()

    // Filtre pour un ensemble de groupes + regno — même logique que l'app iOS.
    // Règles (GeoNature utilise "all" comme joker) :
    //   regno="all"                          → universel, s'applique à tout
    //   regno=X, group2_inpn="all"           → tout le royaume X
    //   group2_inpn=G (insensible casse)     → groupe exact
    // Taxref vide = universel. Groupes vides = inconnu → retourne tout.
    fun filtrerPourGroupes(type: String, groupes: Set<String>, regno: String = ""): List<NomValeur> {
        val groupesLower = groupes.map { it.lowercase() }.toSet()
        return get(type).filter { v ->
            when {
                v.taxref.isEmpty() -> true
                groupes.isEmpty()  -> true
                else -> v.taxref.any { r ->
                    r.regne.equals("all", ignoreCase = true) ||
                    (r.regne.equals(regno, ignoreCase = true) && r.group2Inpn.equals("all", ignoreCase = true)) ||
                    groupesLower.contains(r.group2Inpn.lowercase())
                }
            }
        }
    }

    fun resume(): String {
        val data = charger()
        if (data.isEmpty()) return ""
        return data.entries.joinToString(" | ") { (type, vals) ->
            val nbTaxref = vals.count { it.taxref.isNotEmpty() }
            "$type:${vals.size}val/${nbTaxref}taxref"
        }
    }

    val count: Int get() = charger().values.sumOf { it.size }

    /** Stocke la map des défauts par mnémonique pour un module donné (résultat de
     *  `GET /api/<module>/defaultNomenclatures`). Clé = mnémonique du type (STATUT_OBS…),
     *  valeur = id_nomenclature par défaut (String pour matcher les codes utilisés
     *  partout dans l'UI). */
    fun setDefauts(map: Map<String, String>) {
        prefs.edit().putString(KEY_DEFAUTS, gson.toJson(map)).apply()
        memDefauts = map
    }

    /** Id_nomenclature par défaut pour un mnémonique, ou null si non configuré côté serveur. */
    fun defautPour(mnemonique: String): String? = chargerDefauts()[mnemonique]

    fun tousLesDefauts(): Map<String, String> = chargerDefauts()

    private fun chargerDefauts(): Map<String, String> {
        memDefauts?.let { return it }
        val json = prefs.getString(KEY_DEFAUTS, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            (gson.fromJson(json, type) ?: emptyMap<String, String>()).also { memDefauts = it }
        } catch (_: Exception) { emptyMap() }
    }

    /** Purge valeurs ET défauts (mémoire + disque). Les id_nomenclature sont propres à une
     *  instance GeoNature : à appeler au changement d'identité serveur, sinon les envois
     *  partent avec des FK de l'ancienne instance. Sans effet si [init] n'a pas été appelé. */
    fun vider() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(KEY).remove(KEY_DEFAUTS).apply()
        mem = null
        memDefauts = null
    }

    private fun charger(): Map<String, List<NomValeur>> {
        mem?.let { return it }
        val json = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, List<NomValeur>>>() {}.type
            (gson.fromJson<Map<String, List<NomValeur>>>(json, type) ?: emptyMap()).also { mem = it }
        } catch (_: Exception) { emptyMap() }
    }
}
