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

package fr.ariegenature.geonat.ui

/** Interprétation du `module_picto` d'un protocole monitoring (factorisé depuis SuivisFragment).
 *  Logique pure (testable hors device) : distingue une URL/chemin d'image d'un code FontAwesome,
 *  et mappe les codes FA courants vers un emoji unicode. */
object PictoMonitoring {

    /** Vrai si la chaîne ressemble à une URL ou un chemin d'image (vs un identifiant FontAwesome). */
    fun estImagePicto(picto: String): Boolean {
        val s = picto.trim()
        if (s.startsWith("http://", true) || s.startsWith("https://", true) ||
            s.startsWith("//") || s.startsWith("/")) return true
        val low = s.lowercase()
        return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") ||
            low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".svg")
    }

    /** Mappe un code FontAwesome (`module_picto`) vers un emoji. Fallback : 📋 pour les codes
     *  non listés. Retourne null si la chaîne est vide. */
    fun faEnEmoji(picto: String): String? {
        if (picto.isEmpty()) return null
        return when (picto.lowercase().removePrefix("fa-").removePrefix("fas-").removePrefix("far-")) {
            "puzzle-piece", "puzzle" -> "🧩"
            "bird", "dove", "crow", "feather" -> "🐦"
            "leaf", "seedling" -> "🌿"
            "tree" -> "🌳"
            "fish" -> "🐟"
            "bug" -> "🐛"
            "spider" -> "🕷️"
            "paw" -> "🐾"
            "frog" -> "🐸"
            "snake" -> "🐍"
            "horse" -> "🐎"
            "cow" -> "🐄"
            "dog" -> "🐕"
            "cat" -> "🐈"
            "deer" -> "🦌"
            "rabbit" -> "🐇"
            "mouse" -> "🐁"
            "flower", "fan" -> "🌸"
            "mountain" -> "⛰️"
            "water", "tint", "droplet" -> "💧"
            "map", "map-marker", "map-pin", "location-dot" -> "📍"
            "binoculars" -> "🔭"
            "camera" -> "📷"
            "ear-listen", "headphones" -> "🎧"
            "compass" -> "🧭"
            "ruler", "ruler-combined" -> "📏"
            "clipboard", "clipboard-list", "list", "list-ul" -> "📋"
            "book" -> "📖"
            "calendar", "calendar-days" -> "📅"
            "globe", "earth", "globe-europe" -> "🌍"
            "sun" -> "☀️"
            "cloud" -> "☁️"
            "user", "users" -> "👤"
            else -> "📋"
        }
    }
}
