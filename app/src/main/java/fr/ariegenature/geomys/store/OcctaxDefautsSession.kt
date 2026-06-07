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

/**
 * Défauts de session des nomenclatures Occtax — honore le flag serveur
 * `nomenclature.save_default_values` (cf. [OcctaxFieldsConfig.sauvegarderValeursDefaut]).
 *
 * Quand le flag est actif, les dernières valeurs choisies par l'utilisateur sont mémorisées et
 * pré-remplies sur les saisies suivantes (gain de temps sur les saisies répétitives), exactement
 * comme l'app mobile officielle. Portée process (perdu au redémarrage) — c'est un défaut de
 * confort, pas une donnée à persister.
 */
object OcctaxDefautsSession {

    private val valeurs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Dernière valeur (code nomenclature) mémorisée pour ce mnémonique, ou "" si aucune. */
    fun valeur(code: String): String = valeurs[code] ?: ""

    /** Mémorise les valeurs NON vides d'une saisie validée (les codes vides « non renseigné » ne
     *  remplacent pas un défaut existant). */
    fun memoriser(choix: Map<String, String>) {
        choix.forEach { (code, valeur) -> if (valeur.isNotEmpty()) valeurs[code] = valeur }
    }

    /** Purge (ex. changement d'identité serveur, comme les caches). */
    fun vider() = valeurs.clear()
}
