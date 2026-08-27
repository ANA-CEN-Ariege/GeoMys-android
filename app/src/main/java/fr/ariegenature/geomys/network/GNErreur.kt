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

sealed class GNErreur(message: String) : Exception(message) {
    class AuthEchouee(val code: Int) : GNErreur("Authentification refusée (HTTP $code)")
    class EnvoiEchoue(val code: Int, val msg: String) : GNErreur("Envoi échoué HTTP $code : $msg")
    /** Envoi au statut INCERTAIN : la requête a été émise mais la réponse s'est perdue (coupure
     *  réseau après émission). L'objet a PEUT-ÊTRE été créé côté serveur — un ré-envoi doit
     *  vérifier l'existence avant de re-POSTer (anti-doublon). */
    class EnvoiIncertain(val msg: String) : GNErreur(msg)
    class AucuneObservationCompatible : GNErreur("Aucune observation n'a de cd_nom résolu.")
    /** Le jeu de données configuré n'existe pas sur le serveur ciblé (id absent du cache
     *  datasets). Détecté avant l'envoi pour éviter le 500 opaque (FK invalide). */
    class DatasetInvalide(val id: Int) : GNErreur("Jeu de données $id introuvable sur ce serveur")
}

/** Convertit une exception réseau (typiquement [GNErreur.EnvoiEchoue] ou [GNErreur.AuthEchouee])
 *  en message lisible par l'utilisateur final. Le code HTTP brut reste en parenthèses pour
 *  le support technique. */
fun humaniserErreurReseau(e: Throwable): String {
    if (e is GNErreur.DatasetInvalide) {
        return "Le jeu de données configuré (id ${e.id}) n'existe pas sur ce serveur.\n\n" +
            "Ouvrez la config GeoNature et sélectionnez un jeu de données valide."
    }
    val code = when (e) {
        is GNErreur.EnvoiEchoue -> e.code
        is GNErreur.AuthEchouee -> e.code
        else -> null
    }
    // Pour les 4xx/5xx avec un message serveur non vide, on le remonte tel quel (utile au
    // diagnostic : "field 'id_dataset' is required", "RoleNotAllowedException", etc.).
    val detail = (e as? GNErreur.EnvoiEchoue)?.msg?.takeIf { it.isNotBlank() }
    return when (code) {
        401 -> "Identifiants expirés — reconnectez-vous depuis la config GeoNature (HTTP 401)."
        403 -> "Pas de droit d'accès à cette ressource (CRUVED) (HTTP 403)."
        404 -> "Ressource introuvable côté serveur (HTTP 404)."
        in 500..599 -> buildString {
            append("Erreur serveur (HTTP $code).")
            if (detail != null) append("\n\nDétail : $detail")
        }
        null -> when (e) {
            // Exceptions réseau brutes (login relevé, GET/POST) : message sans jargon.
            is java.net.UnknownHostException ->
                "Pas de réseau, ou serveur introuvable (${e.message ?: "hôte inconnu"}). Vérifiez la connexion."
            is java.net.SocketTimeoutException ->
                "Serveur injoignable ou trop lent (délai dépassé). Réessayez avec une meilleure connexion."
            is java.net.ConnectException ->
                "Connexion au serveur impossible (${e.message ?: "refusée"}). Vérifiez le réseau et l'URL."
            else -> "Erreur réseau : ${e.message ?: e.javaClass.simpleName}"
        }
        else -> "Erreur HTTP $code : ${detail ?: e.message ?: "—"}"
    }
}
