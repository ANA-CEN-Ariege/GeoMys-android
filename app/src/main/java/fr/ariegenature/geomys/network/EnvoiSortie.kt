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

/**
 * Un verrou d'envoi PAR SAISIE, process-wide — le pendant Occtax de `OutboxEnvoi.mutexEnvoi`
 * (monitoring), qui protégeait déjà ce module et n'avait jamais été porté ici.
 *
 * Les cinq points d'appel d'un envoi Occtax (« Tout envoyer » et flèche de ligne dans Mes saisies,
 * écran de détail, fin de trace multi-taxons, saisie rapide) n'avaient pour toute garde que des
 * drapeaux `envoiEnCours` de FRAGMENT : aveugles d'un écran à l'autre. Cas réel : l'envoi est lancé
 * depuis l'écran de détail, l'utilisateur revient à la liste — dont l'instance n'a jamais posé son
 * drapeau —, la saisie y est toujours « À envoyer » avec sa flèche, et un second envoi part sans
 * même un toast. Chacun crée son propre relevé : doublons en base régionale puis en synthèse
 * nationale, qu'aucune contrainte serveur n'arrête (`unique_id_occurence_occtax` n'est pas unique
 * côté GeoNature, et `unique_id_sinp_occtax`, qui l'est, n'est jamais envoyé).
 *
 * `tryLock` et non `lock` : un second envoi est REFUSÉ et le dit, plutôt que d'attendre en silence
 * derrière un premier qui peut durer des minutes (photos, réseau de terrain).
 */
private object VerrousEnvoiSortie {
    private val verrous = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    fun pour(sortieId: String): kotlinx.coroutines.sync.Mutex =
        verrous.computeIfAbsent(sortieId) { kotlinx.coroutines.sync.Mutex() }
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
    val verrou = VerrousEnvoiSortie.pour(sortie.id)
    if (!verrou.tryLock()) {
        return@withContext ResultatEnvoiSortie(
            false,
            "Un envoi de cette saisie est déjà en cours — attendez qu'il se termine avant d'en " +
                "relancer un (sinon les observations partiraient en double sur GeoNature).",
        )
    }
    try {
    // RELECTURE SOUS VERROU. Le verrou seul ne corrigerait rien : il sérialiserait les deux envois,
    // après quoi le second repartirait de l'instantané que son écran a chargé à l'ouverture — donc
    // reposterait tout ce que le premier vient de créer. Les cinq chemins d'appel persistent la
    // sortie dans le store sous son id AVANT d'envoyer (vérifié un par un), la relecture est donc
    // sûre ; on se replie sur l'objet reçu si l'id est absent (cas dégradé : écriture disque
    // refusée), pour ne jamais transformer une saisie en envoi vide.
    // UNION des acquis, et pas simple préférence au disque : `envoyeeServeur` n'étant posé qu'après
    // un 2xx, l'union ne peut pas fabriquer de faux positif, et elle rattrape les marquages qu'une
    // réécriture concurrente aurait effacés.
    val passees = sortie.observations.associateBy { it.id }
    val sortieRelue = sortieStore.charger().find { it.id == sortie.id }?.let { s ->
        s.copy(observations = s.observations.map { o ->
            val p = passees[o.id] ?: return@map o
            o.copy(
                envoyeeServeur = o.envoyeeServeur || p.envoyeeServeur,
                idReleveIncertain = o.idReleveIncertain ?: p.idReleveIncertain,
            )
        })
    } ?: sortie
    // Filet mémoire : occurrences transmises lors d'un envoi précédent de CE process dont le
    // marquage disque avait échoué → exclues comme si elles étaient marquées. Il couvre ce qu'aucune
    // relecture ne verra jamais, puisque justement rien n'a pu être écrit.
    val sortieEff = if (sortieRelue.observations.any { EnvoisNonPersistes.contient(it.id) })
        sortieRelue.copy(observations = sortieRelue.observations.map {
            if (EnvoisNonPersistes.contient(it.id)) it.copy(envoyeeServeur = true) else it
        })
    else sortieRelue
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
    } finally {
        verrou.unlock()
    }
}
