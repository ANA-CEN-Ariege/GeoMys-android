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

/** Version minimale de GeoNature supportée par l'application.
 *
 *  Justification : l'app appelle TaxHub via `<URL_GEONATURE>/api/taxhub/…`, ce qui suppose
 *  TaxHub INTÉGRÉ à GeoNature (2.15+) — sur une instance plus ancienne (TaxHub séparé), la
 *  synchro des taxons échoue. L'endpoint `/api/gn_commons/config` qui permet de détecter la
 *  version existe lui depuis la 2.12 : en dessous, la version n'est pas détectable et le
 *  test de connexion l'indique sans bloquer (bénéfice du doute — proxy filtrant possible).
 *
 *  Quand une version détectée est inférieure à ce minimum, le test de connexion échoue et
 *  [fr.ariegenature.geomys.store.GeoNatureConfig.serveurCompatible] invalide la config
 *  (toutes les opérations réseau sont gelées) jusqu'à un nouveau test réussi. */
const val VERSION_GEONATURE_MINIMALE = "2.15"

/** Compare deux versions pointées numériquement : "2.9" < "2.15" < "2.15.1" (comparaison
 *  par segment, PAS lexicographique). Un suffixe non numérique de segment est ignoré
 *  ("0-rc1" → 0) ; les segments manquants valent 0 ("2.15" == "2.15.0").
 *  Retourne <0, 0 ou >0 comme un Comparator. */
fun comparerVersions(a: String, b: String): Int {
    fun segments(v: String) = v.trim().split('.').map { seg ->
        seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
    val sa = segments(a)
    val sb = segments(b)
    for (i in 0 until maxOf(sa.size, sb.size)) {
        val d = sa.getOrElse(i) { 0 }.compareTo(sb.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}

/** La [version] détectée sur le serveur est-elle au moins la minimale supportée ? */
fun versionGeoNatureSupportee(version: String): Boolean =
    comparerVersions(version, VERSION_GEONATURE_MINIMALE) >= 0