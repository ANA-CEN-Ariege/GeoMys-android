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

package fr.ariegenature.geomys.ui

import org.osmdroid.util.GeoPoint

/**
 * Historique des opérations de géométrie OccHab — « Annuler » = la DERNIÈRE opération, et non
 * le dernier sommet (demande terrain 2026-08-30).
 *
 * Un instantané est pris AVANT chaque opération (tap, déplacement, insertion de sommet) et
 * n'emporte que la géométrie EN COURS : « Annuler » ne touche pas aux stations voisines qu'une
 * propagation topologique a pu déplacer (demande utilisateur 2026-10-18) — annuler son propre
 * geste ne doit pas modifier le travail enregistré sur d'autres stations.
 *
 * Les points sont COPIÉS à la capture (les [GeoPoint] de l'édition sont mutables et suivent le
 * doigt) ; les anneaux de trous le sont en profondeur, pour qu'un sommet de trou s'annule comme
 * un sommet extérieur.
 *
 * Extrait d'`OccHabCarteFragment` (audit OccHab 2026-10-18, O1) : la RESTAURATION reste au
 * fragment — elle touche les overlays, le store et les boutons —, seul l'historique vit ici.
 */
class PileAnnulationGeometrie<M>(private val profondeurMax: Int = 30) {

    class Instantane<M>(
        val mode: M,
        val pointChoisi: GeoPoint?,
        val sommets: List<GeoPoint>,
        val trous: List<List<GeoPoint>>,
    )

    private val pile = ArrayDeque<Instantane<M>>()

    val estVide: Boolean get() = pile.isEmpty()

    /** Capture l'état AVANT une opération. Les points sont recopiés, pas référencés. */
    fun empiler(
        mode: M,
        pointChoisi: GeoPoint?,
        sommets: List<GeoPoint>,
        trous: List<List<GeoPoint>>,
    ) {
        pile.addLast(Instantane(
            mode,
            pointChoisi?.let { GeoPoint(it.latitude, it.longitude) },
            sommets.map { GeoPoint(it.latitude, it.longitude) },
            trous.map { anneau -> anneau.map { GeoPoint(it.latitude, it.longitude) } },
        ))
        // Borne mémoire : une saisie de terrain peut enchaîner des centaines de sommets.
        while (pile.size > profondeurMax) pile.removeFirst()
    }

    fun depiler(): Instantane<M>? = pile.removeLastOrNull()

    fun vider() = pile.clear()
}
