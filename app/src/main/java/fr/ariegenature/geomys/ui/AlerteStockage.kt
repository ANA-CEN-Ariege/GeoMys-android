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

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** Alerte BLOQUANTE d'échec d'écriture disque d'un store (stockage plein, I/O…).
 *
 *  Pendant Occtax/OccHab du dialog `afficherErreurEcritureOutbox` du monitoring : les stores
 *  renvoient un Boolean de succès (`JsonCollectionStore.muter` — le cache mémoire n'est mis à
 *  jour QUE si l'écriture a réussi), mais côté Occtax/OccHab personne ne le lisait — disque
 *  plein = saisies affichées à l'écran puis perdues au kill du process, sans aucun signal
 *  (audit 2026-08-23, constat CRITIQUE). Tout site d'écriture visible de l'utilisateur doit
 *  vérifier le retour et appeler cette alerte sur échec, SANS quitter l'écran (les données ne
 *  vivent plus qu'en mémoire tant que l'écriture n'a pas abouti).
 *
 *  [consigne] complète le constat par la conduite à tenir propre à l'écran appelant. */
fun alerterEchecEcritureStore(
    context: Context,
    consigne: String = "Libérez de l'espace (photos, cache de cartes) puis réessayez.",
) {
    AlertDialog.Builder(context)
        .setTitle("Enregistrement impossible")
        .setMessage(
            "L'écriture sur l'appareil a échoué (stockage plein ?). " +
                "La saisie n'a PAS été enregistrée.\n\n$consigne")
        .setPositiveButton("OK", null)
        .show()
}
