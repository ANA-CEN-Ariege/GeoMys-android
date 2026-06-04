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

/** Relevés OccTax créés côté serveur mais restés VIDES (aucune occurrence) et dont le
 *  DELETE de rollback a échoué — typiquement une coupure réseau totale (mode avion) qui
 *  fait échouer le POST de l'occurrence PUIS le DELETE du relevé. Mémorisés par serveur
 *  pour retenter leur suppression automatiquement au prochain envoi, au lieu de demander
 *  à l'utilisateur de les supprimer à la main dans l'interface web. */
object RelevesOrphelins {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("releves_orphelins", Context.MODE_PRIVATE)
    }

    /** Clé par identité serveur : un id de relevé n'a de sens que sur l'instance qui l'a créé. */
    private fun cle(base: String) = "orphelins_" + base.trim().trimEnd('/')

    fun liste(base: String): List<Int> {
        if (!::prefs.isInitialized) return emptyList()
        return prefs.getString(cle(base), "")
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
    }

    fun ajouter(base: String, ids: Collection<Int>) {
        if (!::prefs.isInitialized || ids.isEmpty()) return
        val fusion = (liste(base) + ids).distinct()
        prefs.edit().putString(cle(base), fusion.joinToString(",")).apply()
    }

    fun retirer(base: String, id: Int) {
        if (!::prefs.isInitialized) return
        val restants = liste(base) - id
        if (restants.isEmpty()) prefs.edit().remove(cle(base)).apply()
        else prefs.edit().putString(cle(base), restants.joinToString(",")).apply()
    }
}