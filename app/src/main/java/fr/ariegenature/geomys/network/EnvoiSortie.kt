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

package fr.ariegenature.geomys.network

import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.SortieStore

/** Résultat d'un envoi de sortie OCCTAX : [succes] + [message] humanisé prêt à afficher. */
data class ResultatEnvoiSortie(val succes: Boolean, val message: String)

/** Envoie [sortie] vers GeoNature et tient le store à jour : marquée envoyée (erreur
 *  précédente effacée) en cas de succès ; erreur HUMANISÉE persistée sinon (→ cadre rouge
 *  dans « Mes saisies »). Factorise le flux qui était copié dans 4 écrans (liste des
 *  saisies, détail, fin de trace multi-taxons, saisie rapide) — les messages avaient déjà
 *  divergé (e.message brut sur 3 écrans vs humanisé sur 1) et le récapitulatif de succès
 *  existait en 4 variantes. L'affichage (dialog/overlay/navigation) reste à l'appelant. */
suspend fun envoyerSortieVersGeoNature(
    sortie: Sortie,
    sortieStore: SortieStore,
    config: GeoNatureConfig,
): ResultatEnvoiSortie {
    return try {
        val res = GeoNatureUpload.envoyer(sortie, config)
        sortieStore.marquerEnvoyee(sortie.id)
        val msg = buildString {
            append("${res.nbCrees}/${res.nbTotal} relevé")
            if (res.nbTotal > 1) append("s")
            append(" créé")
            if (res.nbCrees > 1) append("s")
            append(" sur GeoNature")
            res.premierIdReleve?.let { append("\nPremier id_releve_occtax : $it") }
            if (res.mediasOK > 0) append("\n${res.mediasOK} média(s) uploadé(s)")
            if (res.mediasKO > 0) {
                append("\n⚠ ${res.mediasKO} média(s) échoué(s)")
                res.mediaErreurMsg?.let { append(" : $it") }
            }
            if (res.relevesOrphelins.isNotEmpty()) {
                append("\n⚠ ${res.relevesOrphelins.size} relevé(s) vide(s) côté GeoNature ")
                append("(id : ${res.relevesOrphelins.joinToString(", ")}) — ")
                append("suppression retentée automatiquement au prochain envoi.")
            }
        }
        ResultatEnvoiSortie(true, msg)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        val msg = humaniserErreurReseau(e)
        sortieStore.marquerErreurEnvoi(sortie.id, msg)
        ResultatEnvoiSortie(false, msg)
    }
}