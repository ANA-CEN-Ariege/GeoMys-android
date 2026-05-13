package fr.ariegenature.geonat.network

sealed class GNErreur(message: String) : Exception(message) {
    class UrlInvalide : GNErreur("URL du serveur invalide")
    class AuthEchouee(code: Int) : GNErreur("Authentification refusée (HTTP $code)")
    class EnvoiEchoue(code: Int, msg: String) : GNErreur("Envoi échoué HTTP $code : $msg")
    class AucuneObservationCompatible : GNErreur("Aucune observation n'a de cd_nom résolu.")
}