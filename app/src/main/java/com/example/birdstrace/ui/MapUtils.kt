package com.example.birdstrace.ui

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

enum class FondCarte { OSM, TOPO, ORTHO }

fun tileSourcePour(fond: FondCarte) = when (fond) {
    FondCarte.OSM   -> TileSourceFactory.MAPNIK
    FondCarte.TOPO  -> ignTileSource("GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2", "image/png")
    FondCarte.ORTHO -> ignTileSource("ORTHOIMAGERY.ORTHOPHOTOS", "image/jpeg")
}

fun ignTileSource(layer: String, fmt: String): OnlineTileSourceBase =
    object : OnlineTileSourceBase("IGN_$layer", 2, 19, 256, "", arrayOf("")) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x    = MapTileIndex.getX(pMapTileIndex)
            val y    = MapTileIndex.getY(pMapTileIndex)
            return "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
                "&LAYER=$layer&STYLE=normal&FORMAT=$fmt" +
                "&TILEMATRIXSET=PM&TILEMATRIX=$zoom&TILEROW=$y&TILECOL=$x"
        }
    }
