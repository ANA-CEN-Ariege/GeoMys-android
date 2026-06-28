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

import android.widget.TextView

/** Accès à la mise à jour intégrée depuis l'écran d'accueil — IMPLÉMENTATION DU FLAVOR PLAY (VIDE).
 *
 *  La version Play Store se met à jour via le Store (la politique Google interdit qu'une appli
 *  télécharge/installe elle-même un APK). Tout le code de MAJ — l'objet réel, `MiseAJour`
 *  (polling/téléchargement) et `MiseAJourFragment` (écran) — vit dans `src/github` et est donc
 *  ABSENT de ce binaire `.aab`. Ces méthodes sont des no-op : elles existent uniquement pour que
 *  le code partagé d'`AccueilFragment` compile et tourne sans rien déclencher. */
object MiseAJourAccueil {
    fun configurer(fragment: AccueilFragment, tvVersion: TextView) { /* no-op : pas de MAJ intégrée */ }
    fun reverifier(fragment: AccueilFragment) { /* no-op : MAJ gérée par le Store */ }
}
