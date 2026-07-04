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

import android.os.Bundle
import androidx.navigation.NavController

/** `navigate()` tolérant au double-tap. Après un premier `navigate()`, le deuxième événement
 *  du même doigt (déjà dans la file du main thread) s'exécute alors que `currentDestination`
 *  a changé : l'action n'existe plus depuis la nouvelle destination et `navigate()` lève
 *  `IllegalArgumentException` → crash. On ne navigue que si la destination COURANTE offre
 *  bien l'action ; sinon le clic « en trop » est ignoré silencieusement.
 *  À utiliser pour toute navigation déclenchée par un clic (boutons, items de liste, snacks). */
fun NavController.naviguerSur(
    actionId: Int,
    args: Bundle? = null,
    navOptions: androidx.navigation.NavOptions? = null,
) {
    if (currentDestination?.getAction(actionId) != null) {
        navigate(actionId, args, navOptions)
    } else {
        android.util.Log.d("Navigation",
            "naviguerSur : action $actionId absente de ${currentDestination?.label} — clic ignoré (double-tap ?)")
    }
}
