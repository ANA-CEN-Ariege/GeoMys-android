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
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

enum class FondCarte { OSM, TOPO, SCAN25, ORTHO }

private const val PREFS_FOND = "GeoMys_prefs"
private const val KEY_FOND = "fond_carte"

/** Charge le dernier fond de carte choisi par l'utilisateur (partagé entre toutes les cartes).
 *  `defaut` n'est utilisé qu'à la toute première ouverture, avant tout choix. */
fun chargerFondCarte(context: Context, defaut: FondCarte): FondCarte {
    val nom = context.getSharedPreferences(PREFS_FOND, Context.MODE_PRIVATE)
        .getString(KEY_FOND, null) ?: return defaut
    return runCatching { FondCarte.valueOf(nom) }.getOrDefault(defaut)
}

/** Mémorise le fond de carte choisi pour qu'il soit réutilisé à la prochaine ouverture. */
fun enregistrerFondCarte(context: Context, fond: FondCarte) {
    context.getSharedPreferences(PREFS_FOND, Context.MODE_PRIVATE)
        .edit().putString(KEY_FOND, fond.name).apply()
}

/** Policy permissive : autorise le bulk download (FLAG_NO_BULK désactivé) — nécessaire pour
 *  l'écran Cache Manager. Sur les autres écrans c'est neutre. Le respect des CGU OSM/IGN
 *  reste de la responsabilité de l'utilisateur (l'app cap déjà la surface à 200 km², soit
 *  bien en dessous des limites de quota courantes). */
private val POLICY_PERMISSIVE = TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_PREVENTIVE)

fun tileSourcePour(fond: FondCarte) = when (fond) {
    FondCarte.OSM    -> osmTileSource()
    FondCarte.TOPO   -> ignTileSource("GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2", "image/png", false)
    FondCarte.SCAN25 -> ignTileSource("GEOGRAPHICALGRIDSYSTEMS.MAPS", "image/jpeg", true)
    FondCarte.ORTHO  -> ignTileSource("ORTHOIMAGERY.ORTHOPHOTOS", "image/jpeg", false)
}

/** OSM/Mapnik avec policy permissive pour autoriser le bulk download depuis Cache Manager.
 *  Note : TileSourceFactory.MAPNIK utilise une policy stricte qui refuse explicitement le
 *  bulk download. On reconstruit la source à la main avec les mêmes serveurs miroir. */
private fun osmTileSource(): OnlineTileSourceBase = XYTileSource(
    "Mapnik",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.tile.openstreetmap.org/",
        "https://b.tile.openstreetmap.org/",
        "https://c.tile.openstreetmap.org/",
    ),
    "© OpenStreetMap contributors",
    POLICY_PERMISSIVE,
)

fun FondCarte.suivant() = when (this) {
    FondCarte.OSM    -> FondCarte.TOPO
    FondCarte.TOPO   -> FondCarte.SCAN25
    FondCarte.SCAN25 -> FondCarte.ORTHO
    FondCarte.ORTHO  -> FondCarte.OSM
}

fun ignTileSource(layer: String, fmt: String, prive: Boolean): OnlineTileSourceBase =
    object : OnlineTileSourceBase("IGN_$layer", 2, 19, 256, "", arrayOf(""), "", POLICY_PERMISSIVE) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x    = MapTileIndex.getX(pMapTileIndex)
            val y    = MapTileIndex.getY(pMapTileIndex)
            val base = if (prive)
                "https://data.geopf.fr/private/wmts?apikey=ign_scan_ws&SERVICE=WMTS"
            else
                "https://data.geopf.fr/wmts?SERVICE=WMTS"
            return "$base&REQUEST=GetTile&VERSION=1.0.0" +
                "&LAYER=$layer&STYLE=normal&FORMAT=$fmt" +
                "&TILEMATRIXSET=PM&TILEMATRIX=$zoom&TILEROW=$y&TILECOL=$x"
        }
    }
