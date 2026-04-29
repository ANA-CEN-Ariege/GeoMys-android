package com.example.birdstrace.ui

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

enum class FondCarte { OSM, TOPO, SCAN25, ORTHO }

fun tileSourcePour(fond: FondCarte) = when (fond) {
    FondCarte.OSM    -> TileSourceFactory.MAPNIK
    FondCarte.TOPO   -> ignTileSource("GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2", "image/png", false)
    FondCarte.SCAN25 -> ignTileSource("GEOGRAPHICALGRIDSYSTEMS.MAPS", "image/jpeg", true)
    FondCarte.ORTHO  -> ignTileSource("ORTHOIMAGERY.ORTHOPHOTOS", "image/jpeg", false)
}

fun FondCarte.suivant() = when (this) {
    FondCarte.OSM    -> FondCarte.TOPO
    FondCarte.TOPO   -> FondCarte.SCAN25
    FondCarte.SCAN25 -> FondCarte.ORTHO
    FondCarte.ORTHO  -> FondCarte.OSM
}

fun ignTileSource(layer: String, fmt: String, prive: Boolean): OnlineTileSourceBase =
    object : OnlineTileSourceBase("IGN_$layer", 2, 19, 256, "", arrayOf("")) {
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
