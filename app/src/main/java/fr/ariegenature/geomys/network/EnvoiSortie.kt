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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Résultat d'un envoi de sortie OCCTAX : [succes] + [message] humanisé prêt à afficher. */
data class ResultatEnvoiSortie(val succes: Boolean, val message: String)

/**
 * Occurrences TRANSMISES à GeoNature dont le marquage local a ÉCHOUÉ (commit disque refusé :
 * espace plein) — filet mémoire jusqu'à la mort du process : un ré-envoi de la même saisie dans
 * cette session ne les re-poste pas (audit 2026-08-27). Après redémarrage, seul l'avertissement
 * affiché à l'utilisateur protège (d'où son insistance).
 */
internal object EnvoisNonPersistes {
    private val obs = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    fun ajouter(ids: Collection<String>) { obs.addAll(ids) }
    fun contient(id: String): Boolean = id in obs
    @androidx.annotation.VisibleForTesting fun vider() { obs.clear() }
}

private const val AVERT_PERSISTANCE =
    "\n\n⚠ Transmis à GeoNature mais l'enregistrement local a ÉCHOUÉ (espace disque ?) : libérez " +
        "de l'espace AVANT tout nouvel envoi de cette saisie — un ré-envoi après redémarrage " +
        "créerait des doublons."

/** Envoie [sortie] vers GeoNature et tient le store à jour : marquée envoyée (erreur
 *  précédente effacée) en cas de succès ; erreur HUMANISÉE persistée sinon (→ cadre rouge
 *  dans « Mes saisies »). Factorise le flux qui était copié dans 4 écrans (liste des
 *  saisies, détail, fin de trace multi-taxons, saisie rapide) — les messages avaient déjà
 *  divergé (e.message brut sur 3 écrans vs humanisé sur 1) et le récapitulatif de succès
 *  existait en 4 variantes. L'affichage (dialog/overlay/navigation) reste à l'appelant.
 *
 *  Robustesse (audit 2026-08-27) : le bloc POST + marquages tourne en [NonCancellable] — une
 *  annulation de coroutine (vue détruite pendant l'envoi : retour, navigation) ne peut plus
 *  jeter le résultat d'un POST abouti (occurrences créées côté serveur non marquées → doublons
 *  au ré-envoi). Le marquage AU FIL DE L'EAU ([MarqueurEnvoiOcctax]) couvre en plus la mort du
 *  process en plein lot, et un échec de persistance est SIGNALÉ au lieu d'être ignoré. */
suspend fun envoyerSortieVersGeoNature(
    sortie: Sortie,
    sortieStore: SortieStore,
    config: GeoNatureConfig,
): ResultatEnvoiSortie = withContext(NonCancellable) {
    // Filet mémoire : occurrences transmises lors d'un envoi précédent de CE process dont le
    // marquage disque avait échoué → exclues comme si elles étaient marquées.
    val sortieEff = if (sortie.observations.any { EnvoisNonPersistes.contient(it.id) })
        sortie.copy(observations = sortie.observations.map {
            if (EnvoisNonPersistes.contient(it.id)) it.copy(envoyeeServeur = true) else it
        })
    else sortie
    var marquageEchoue = false
    val marqueur = object : MarqueurEnvoiOcctax {
        override fun occurrenceCreee(obsId: String) {
            if (!sortieStore.marquerObservationsEnvoyees(sortie.id, listOf(obsId))) {
                marquageEchoue = true
                EnvoisNonPersistes.ajouter(listOf(obsId))
            }
        }
        override fun occurrenceIncertaine(obsId: String, idReleve: Int) {
            sortieStore.marquerObservationIncertaine(sortie.id, obsId, idReleve)
        }
    }
    try {
        val res = GeoNatureUpload.envoyer(sortieEff, config, marqueur)
        // L'ACQUIS d'abord (redondant avec le marqueur, idempotent) : les occurrences créées
        // pendant cet envoi sont marquées AVANT tout autre traitement.
        if (res.obsCreesIds.isNotEmpty() &&
            !sortieStore.marquerObservationsEnvoyees(sortie.id, res.obsCreesIds)
        ) {
            marquageEchoue = true
            EnvoisNonPersistes.ajouter(res.obsCreesIds)
        }
        if (res.nbCrees == res.nbTotal) {
            // Envoi COMPLET (nbTotal == 0 : tout avait déjà été transmis lors d'un envoi
            // partiel précédent — on clôture simplement).
            if (!sortieStore.marquerEnvoyee(sortie.id)) marquageEchoue = true
            val msg = buildString {
                if (res.nbTotal == 0) {
                    append("Toutes les observations avaient déjà été transmises — saisie marquée envoyée.")
                } else {
                    append("${res.nbCrees}/${res.nbTotal} relevé")
                    if (res.nbTotal > 1) append("s")
                    append(" créé")
                    if (res.nbCrees > 1) append("s")
                    append(" sur GeoNature")
                    if (res.nbDejaEnvoyees > 0) {
                        append(" (+ ${res.nbDejaEnvoyees} déjà transmis précédemment)")
                    }
                }
                res.premierIdReleve?.let { append("\nPremier id_releve_occtax : $it") }
                if (res.mediasOK > 0) append("\n${res.mediasOK} média(s) uploadé(s)")
                if (res.mediasKO > 0) {
                    // Ici, seuls des échecs média LOCAUX (fichier disparu) : un échec réseau
                    // aurait retenu l'observation entière (envoi partiel, branche ci-dessous).
                    append("\n⚠ ${res.mediasKO} média(s) introuvable(s) sur l'appareil — envoyé(s) sans photo")
                    res.mediaErreurMsg?.let { append(" : $it") }
                }
                if (res.relevesOrphelins.isNotEmpty()) {
                    append("\n⚠ ${res.relevesOrphelins.size} relevé(s) vide(s) côté GeoNature ")
                    append("(id : ${res.relevesOrphelins.joinToString(", ")}) — ")
                    append("suppression retentée automatiquement au prochain envoi.")
                }
                if (marquageEchoue) append(AVERT_PERSISTANCE)
            }
            // Marquage échoué = l'appareil croit encore la saisie « à envoyer » : on le rend
            // VISIBLE (échec) plutôt que de laisser un succès trompeur.
            ResultatEnvoiSortie(!marquageEchoue, msg)
        } else {
            // Envoi PARTIEL (réseau tombé entre deux groupes, photo non transmise…) : la sortie
            // N'EST PAS marquée envoyée — elle reste visible et ré-envoyable dans « Mes
            // saisies », et seules les obs restantes partiront au prochain essai (les créées
            // sont marquées ci-dessus). Avant ce garde, UNE seule occurrence créée suffisait à
            // verrouiller toute la sortie : les observations restantes étaient perdues.
            val total = res.nbTotal + res.nbDejaEnvoyees
            val transmises = res.nbCrees + res.nbDejaEnvoyees
            val msg = buildString {
                append("Envoi partiel : $transmises/$total observation(s) transmise(s)")
                res.messageDerniereErreur?.let { append("\nDernière erreur : $it") }
                if (res.obsIncertaines.isNotEmpty()) {
                    append("\n${res.obsIncertaines.size} observation(s) au statut incertain (réponse perdue) : " +
                        "vérifiée(s) automatiquement au prochain envoi, pas de doublon.")
                }
                append("\n→ Ré-envoyez la saisie : seules les observations restantes partiront.")
                if (marquageEchoue) append(AVERT_PERSISTANCE)
            }
            sortieStore.marquerErreurEnvoi(sortie.id, msg)
            ResultatEnvoiSortie(false, msg)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        val msg = humaniserErreurReseau(e)
        sortieStore.marquerErreurEnvoi(sortie.id, msg)
        ResultatEnvoiSortie(false, msg)
    }
}
