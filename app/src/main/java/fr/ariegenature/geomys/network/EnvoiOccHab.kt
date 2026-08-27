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

import fr.ariegenature.geomys.model.OccHabSaisie
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OccHabStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Résultat d'un envoi de saisie OccHab : [succes] + [message] humanisé prêt à afficher. */
data class ResultatEnvoiOccHab(val succes: Boolean, val message: String)

/**
 * Envoie une SAISIE OccHab (ses stations) vers GeoNature et tient le store à jour, sur le modèle
 * de [envoyerSortieVersGeoNature] pour Occtax. Chaque station est postée via [OccHabUpload.envoyer]
 * (anti-doublon par UUID/statut incertain déjà en place) et son résultat est persisté AU FUR ET À
 * MESURE : l'acquis d'une station survit même si une suivante échoue.
 *
 * - Toutes les stations parties → la saisie est marquée envoyée (par recalcul au store).
 * - Échec PARTIEL (réseau coupé en cours) → la saisie N'EST PAS marquée envoyée : elle reste
 *   visible et ré-envoyable, et seules les stations restantes repartiront au prochain essai (les
 *   déjà-créées ne sont pas re-postées).
 * L'affichage (dialog/progression) reste à l'appelant.
 */
suspend fun envoyerSaisieOccHabVersGeoNature(
    saisie: OccHabSaisie,
    store: OccHabStore,
    config: GeoNatureConfig,
    /** Envoi d'UNE station — injectable pour les tests (défaut : le POST réseau réel). */
    envoyer: suspend (OccHabStation, GeoNatureConfig) -> OccHabEnvoiResult = OccHabUpload::envoyer,
): ResultatEnvoiOccHab = withContext(Dispatchers.IO) {
    // Sur IO : les commits synchrones du store (gson.toJson + prefs.commit) ne bloquent PAS le
    // thread UI à l'envoi d'une saisie multi-stations.
    val aEnvoyer = saisie.stations.filter { !it.envoyeGeoNature }
    val dejaEnvoyees = saisie.stations.size - aEnvoyer.size
    val total = saisie.stations.size

    if (aEnvoyer.isEmpty()) {
        // Tout avait déjà été transmis (la saisie est déjà marquée envoyée au store).
        return@withContext ResultatEnvoiOccHab(true, "Toutes les stations avaient déjà été transmises.")
    }

    var nbCrees = 0
    var derniereErreur: String? = null
    for (station in aEnvoyer) {
        // CRÉATION (pas d'id serveur) : « tentée » (statut INCERTAIN) persisté AVANT le POST — si
        // le process meurt ou si la coroutine est annulée pendant l'appel, le prochain envoi
        // vérifiera l'existence par UUID au lieu de re-POSTer (audit 2026-08-27, modèle
        // OutboxEnvoi.dejaTentee). Un UPDATE (/stations/<id>/) est idempotent : inutile.
        // Impossible d'écrire cet état (espace disque ?) → on n'envoie PAS (un re-POST aveugle
        // après crash dupliquerait).
        val creation = (station.idStationServeur ?: 0) <= 0
        if (creation && !store.marquerStationIncertain(saisie.id, station.id,
                "Envoi interrompu — vérification anti-doublon au prochain envoi")
        ) {
            derniereErreur = "Impossible d'enregistrer l'état d'envoi (espace disque ?) — station non envoyée"
            continue
        }
        try {
            // NonCancellable : POST + marquage d'un bloc — une annulation (vue détruite pendant
            // l'envoi) ne peut plus jeter le résultat d'un POST abouti.
            withContext(kotlinx.coroutines.NonCancellable) {
                val res = envoyer(station, config)
                // L'ACQUIS d'abord : la station créée est marquée AVANT tout le reste — un ré-envoi
                // ne la re-postera pas (recalcule aussi l'état de la saisie).
                val persiste = store.marquerStationEnvoyee(saisie.id, station.id, res.idStationServeur)
                if (persiste) {
                    nbCrees++
                } else {
                    // POST réussi mais écriture disque échouée (disque plein…) : la station EST créée
                    // côté serveur mais non marquée localement → on la passe en INCERTAIN pour que le
                    // ré-envoi vérifie l'existence par UUID au lieu de re-POSTer (anti-doublon).
                    store.marquerStationIncertain(saisie.id, station.id,
                        "Station transmise mais enregistrement local incomplet — vérification au prochain envoi.")
                    derniereErreur = "Enregistrement local incomplet"
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: GNErreur.EnvoiIncertain) {
            store.marquerStationIncertain(saisie.id, station.id, e.msg)
            derniereErreur = e.msg
        } catch (e: GNErreur.EnvoiEchoue) {
            // Un 5xx a PU committer la station côté serveur avant l'échec (500 en sérialisant la
            // réponse, 504 après l'INSERT). Le module OccHab n'a AUCUNE contrainte d'unicité →
            // on la traite comme INCERTAINE (le ré-envoi vérifiera par UUID) et non comme un échec
            // net qui inviterait à re-POSTer → anti-doublon.
            val msg = humaniserErreurReseau(e)
            if (e.code >= 500) store.marquerStationIncertain(saisie.id, station.id, msg)
            else store.marquerStationErreur(saisie.id, station.id, msg)
            derniereErreur = msg
        } catch (e: Exception) {
            val msg = humaniserErreurReseau(e)
            store.marquerStationErreur(saisie.id, station.id, msg)
            derniereErreur = msg
        }
    }

    if (nbCrees == aEnvoyer.size) {
        // Envoi COMPLET (la saisie a été marquée envoyée par le dernier marquerStationEnvoyee).
        val msg = buildString {
            append("$nbCrees station")
            if (nbCrees > 1) append("s")
            append(" envoyée")
            if (nbCrees > 1) append("s")
            append(" sur GeoNature")
            if (dejaEnvoyees > 0) append(" (+ $dejaEnvoyees déjà transmise(s) précédemment)")
            append(".")
        }
        ResultatEnvoiOccHab(true, msg)
    } else {
        // Envoi PARTIEL : la saisie reste à envoyer, seules les restantes repartiront.
        val transmises = nbCrees + dejaEnvoyees
        val msg = buildString {
            append("Envoi partiel : $transmises/$total station(s) transmise(s)")
            derniereErreur?.let { append("\nDernière erreur : $it") }
            append("\n→ Ré-envoyez la saisie : seules les stations restantes partiront.")
        }
        store.marquerErreurSaisie(saisie.id, msg)
        ResultatEnvoiOccHab(false, msg)
    }
}
