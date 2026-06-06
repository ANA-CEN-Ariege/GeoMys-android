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
import com.google.gson.annotations.SerializedName

/**
 * Périodes de nidification des oiseaux, chargées depuis l'asset embarqué
 * `nidification_oiseaux.json` (source : oiseaux_nicheurs_france.xlsx, 285 espèces).
 *
 * La table est indexée par nom scientifique normalisé (cf. [TaxRefCache.normaliser]).
 * À la saisie, on résout le `cd_nom` de l'espèce ajoutée en nom scientifique via le
 * cache TaxRef, puis on regarde si le mois pertinent est marqué comme période de
 * nidification.
 *
 * Ne concerne que les oiseaux : l'appelant doit déjà filtrer sur `Taxon.OISEAU`.
 * Données figées dans l'APK pour la v1 (phénologie stable) ; migration possible
 * vers une synchro serveur (attributs TaxHub) ultérieurement sans toucher au
 * point de déclenchement.
 */
object NidificationOiseaux {

    private data class Espece(
        @SerializedName("sci") val sci: String = "",
        @SerializedName("mois") val mois: List<Int> = emptyList(),   // 12 entiers 0/1, Jan..Déc
    )

    private data class Fichier(
        @SerializedName("especes") val especes: List<Espece> = emptyList(),
    )

    // Nom scientifique normalisé -> 12 booléens (index 0 = janvier … 11 = décembre).
    private var table: Map<String, BooleanArray>? = null

    private fun charger(context: Context): Map<String, BooleanArray> {
        table?.let { return it }
        val map = HashMap<String, BooleanArray>()
        try {
            val json = context.assets.open("nidification_oiseaux.json")
                .bufferedReader().use { it.readText() }
            val fichier = Gson().fromJson(json, Fichier::class.java)
            for (e in fichier.especes) {
                if (e.mois.size != 12) continue
                val cle = TaxRefCache.normaliser(e.sci)
                if (cle.isEmpty()) continue
                val periodes = BooleanArray(12) { e.mois[it] != 0 }
                map[cle] = periodes
                // Repli genre+espèce (2 premiers tokens) pour absorber un éventuel
                // auteur/année ou une sous-espèce côté TaxRef.
                val court = clePremiersTokens(cle)
                if (court != cle) map.putIfAbsent(court, periodes)
            }
        } catch (_: Exception) {
            // Asset absent ou illisible : table vide => aucun déclenchement automatique.
        }
        return map.also { table = it }
    }

    private fun clePremiersTokens(cle: String): String =
        cle.split(' ').filter { it.isNotEmpty() }.take(2).joinToString(" ")

    /**
     * Vrai si l'espèce (résolue par [cdNom] via TaxRef) est en période de nidification
     * pour le [mois] donné (1 = janvier … 12 = décembre). Faux si le mois est hors
     * bornes, si le `cd_nom` n'est pas résolu, ou si l'espèce est absente du fichier.
     */
    fun estEnPeriode(context: Context, cdNom: Int, mois: Int): Boolean {
        if (mois !in 1..12) return false
        val sci = TaxRefCache.entreesParCdNom()[cdNom]?.sciNom ?: return false
        val t = charger(context)
        val cle = TaxRefCache.normaliser(sci)
        val periodes = t[cle] ?: t[clePremiersTokens(cle)] ?: return false
        return periodes[mois - 1]
    }
}