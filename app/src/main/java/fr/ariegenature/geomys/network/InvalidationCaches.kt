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

/** Vide en cascade tous les caches mémoire process-wide qui dépendent de l'identité du
 *  serveur GeoNature (URL / login / mot de passe). À appeler par l'écran de configuration
 *  dès qu'un de ces 3 champs change — sans ça, un changement de serveur continue à servir
 *  des id_nomenclature, id_table_location, id_role, modules et LabelResolver issus de
 *  l'instance précédente, et les envois partent avec des FK invalides.
 *
 *  Les caches disque versionnés par module_code ([fr.ariegenature.geomys.store.MonitoringCache])
 *  ne sont pas vidés ici : ils restent valides tant que l'utilisateur ne change pas de
 *  serveur, et un changement de serveur impose de toute façon un nouveau sync explicite. */
fun invaliderCachesSession() {
    GeoNatureAuth.invaliderCache()
    MonitoringApi.invaliderCaches()
    GeoNatureUpload.invaliderCaches()
    // Cache DISQUE des nomenclatures : contrairement aux caches ci-dessus il survit au
    // process, et GeoNatureUpload.envoyer() le préfère au réseau (`fromCache.isNotEmpty()`).
    // Sans cette purge, les id_nomenclature de l'ancienne instance partaient dans les
    // occurrences → FK invalide → 500 opaque. Purge volontairement large (aussi sur simple
    // changement de login/mdp) : un cache vide se resynchronise tout seul à l'envoi.
    fr.ariegenature.geomys.store.NomenclatureCache.vider()
}
