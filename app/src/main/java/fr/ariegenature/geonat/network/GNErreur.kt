/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat.network

sealed class GNErreur(message: String) : Exception(message) {
    class UrlInvalide : GNErreur("URL du serveur invalide")
    class AuthEchouee(val code: Int) : GNErreur("Authentification refusée (HTTP $code)")
    class EnvoiEchoue(val code: Int, val msg: String) : GNErreur("Envoi échoué HTTP $code : $msg")
    class AucuneObservationCompatible : GNErreur("Aucune observation n'a de cd_nom résolu.")
}

/** Convertit une exception réseau (typiquement [GNErreur.EnvoiEchoue] ou [GNErreur.AuthEchouee])
 *  en message lisible par l'utilisateur final. Le code HTTP brut reste en parenthèses pour
 *  le support technique. */
fun humaniserErreurReseau(e: Throwable): String {
    val code = when (e) {
        is GNErreur.EnvoiEchoue -> e.code
        is GNErreur.AuthEchouee -> e.code
        else -> null
    }
    // Pour les 4xx/5xx avec un message serveur non vide, on le remonte tel quel (utile au
    // diagnostic : "field 'id_dataset' is required", "RoleNotAllowedException", etc.).
    val detail = (e as? GNErreur.EnvoiEchoue)?.msg?.takeIf { it.isNotBlank() }
    return when (code) {
        401 -> "Identifiants expirés — reconnecte-toi depuis la config GeoNature (HTTP 401)."
        403 -> "Pas de droit d'accès à cette ressource (CRUVED) (HTTP 403)."
        404 -> "Ressource introuvable côté serveur (HTTP 404)."
        in 500..599 -> buildString {
            append("Erreur serveur (HTTP $code).")
            if (detail != null) append("\n\nDétail : $detail")
        }
        null -> "Erreur réseau : ${e.message ?: e.javaClass.simpleName}"
        else -> "Erreur HTTP $code : ${detail ?: e.message ?: "—"}"
    }
}
