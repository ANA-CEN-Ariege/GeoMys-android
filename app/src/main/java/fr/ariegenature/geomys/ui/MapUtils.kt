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
import fr.ariegenature.geomys.store.MbtilesStore
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.io.File

enum class FondCarte { OSM, OPENTOPO, TOPO, SCAN25, ORTHO, ESRI }

/** Fond de carte sélectionné : soit un fond EN LIGNE ([FondCarte]), soit un fichier MBTILES
 *  local importé par l'utilisateur (hors-ligne). Partagé entre toutes les cartes. */
sealed interface FondChoisi {
    data class EnLigne(val fond: FondCarte) : FondChoisi
    data class Mbtiles(val fichier: File) : FondChoisi
}

private const val PREFS_FOND = "GeoMys_prefs"
private const val KEY_FOND = "fond_carte"

/** Charge le dernier fond choisi (en ligne ou MBTiles). Repli sur OSM si aucun choix ou si le
 *  fichier MBTiles mémorisé a disparu. */
fun chargerFondChoisi(context: Context): FondChoisi {
    val v = context.getSharedPreferences(PREFS_FOND, Context.MODE_PRIVATE).getString(KEY_FOND, null)
        ?: return FondChoisi.EnLigne(FondCarte.OSM)
    if (v.startsWith("mbtiles:")) {
        val f = File(MbtilesStore.dossier(context), v.removePrefix("mbtiles:"))
        return if (f.exists()) FondChoisi.Mbtiles(f) else FondChoisi.EnLigne(FondCarte.OSM)
    }
    return FondChoisi.EnLigne(runCatching { FondCarte.valueOf(v) }.getOrDefault(FondCarte.OSM))
}

/** Mémorise le fond choisi (en ligne : nom de l'enum ; MBTiles : `mbtiles:<nom de fichier>`). */
fun enregistrerFondChoisi(context: Context, fond: FondChoisi) {
    val v = when (fond) {
        is FondChoisi.EnLigne -> fond.fond.name
        is FondChoisi.Mbtiles -> "mbtiles:${fond.fichier.name}"
    }
    context.getSharedPreferences(PREFS_FOND, Context.MODE_PRIVATE).edit().putString(KEY_FOND, v).apply()
}

/** Applique le fond [fond] sur la carte [map] : bascule le fournisseur de tuiles entre le mode
 *  EN LIGNE (provider réseau + cache) et le mode HORS-LIGNE MBTiles (OfflineTileProvider sur le
 *  fichier SQLite). Détache proprement un provider MBTiles précédent (libère la connexion SQLite). */
fun appliquerFond(map: MapView, fond: FondChoisi, context: Context) {
    (map.tileProvider as? OfflineTileProvider)?.detach()
    when (fond) {
        is FondChoisi.EnLigne -> {
            // Si on venait du hors-ligne, le provider a été détaché ci-dessus → on en remet un
            // en ligne. Sinon (online→online), on garde le provider existant et on change juste
            // la source (préserve le cache configuré à l'init de la carte).
            if (map.tileProvider !is MapTileProviderBasic) map.setTileProvider(MapTileProviderBasic(context))
            map.setUseDataConnection(true)
            map.setTileSource(tileSourcePour(fond.fond))
        }
        is FondChoisi.Mbtiles -> {
            val info = MbtilesStore.info(fond.fichier)
            val provider = OfflineTileProvider(SimpleRegisterReceiver(context), arrayOf(fond.fichier))
            // setIgnoreTileSource(true) : l'archive sert les tuiles par z/x/y quel que soit le
            // nom de source (un MBTiles n'a pas de « dossier source » comme un zip).
            provider.archives.forEach { it.setIgnoreTileSource(true) }
            map.setTileProvider(provider)
            map.setUseDataConnection(false) // pas de réseau : tout vient du fichier
            // Source générique : ne sert qu'à borner le zoom et la taille de tuile ; le nom est
            // ignoré (cf. ci-dessus), le format png/jpg est auto-détecté au décodage.
            map.setTileSource(
                XYTileSource("mbtiles", info.minZoom, info.maxZoom, 256, ".${info.format}", arrayOf(""))
            )
        }
    }
    map.invalidate()
}

/** Policy permissive : autorise le bulk download (FLAG_NO_BULK désactivé) — nécessaire pour
 *  l'écran Cache Manager. Sur les autres écrans c'est neutre. Le respect des CGU OSM/IGN
 *  reste de la responsabilité de l'utilisateur (l'app cap déjà la surface à 200 km², soit
 *  bien en dessous des limites de quota courantes). */
private val POLICY_PERMISSIVE = TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_PREVENTIVE)

fun tileSourcePour(fond: FondCarte) = when (fond) {
    FondCarte.OSM      -> osmTileSource()
    FondCarte.OPENTOPO -> openTopoTileSource()
    FondCarte.TOPO     -> ignTileSource("GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2", "image/png", false)
    FondCarte.SCAN25   -> ignTileSource("GEOGRAPHICALGRIDSYSTEMS.MAPS", "image/jpeg", true)
    FondCarte.ORTHO    -> ignTileSource("ORTHOIMAGERY.ORTHOPHOTOS", "image/jpeg", false)
    FondCarte.ESRI     -> esriImageryTileSource()
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

/** Libellé lisible d'un fond de carte, pour le menu de choix. */
fun FondCarte.libelle(): String = when (this) {
    FondCarte.OSM      -> "OpenStreetMap"
    FondCarte.OPENTOPO -> "OpenTopoMap (relief)"
    FondCarte.TOPO     -> "IGN Plan"
    FondCarte.SCAN25   -> "IGN SCAN 25 (topo)"
    FondCarte.ORTHO    -> "IGN Photo aérienne"
    FondCarte.ESRI     -> "Esri (satellite)"
}

/** Libellé d'un fond pour le menu : nom du fond en ligne, ou « 📁 <nom> (hors-ligne) » pour
 *  un MBTiles. */
private fun libelleFond(fond: FondChoisi): String = when (fond) {
    is FondChoisi.EnLigne -> fond.fond.libelle()
    is FondChoisi.Mbtiles -> "📁 ${MbtilesStore.nomAffichage(fond.fichier)} (hors-ligne)"
}

private fun memeFond(a: FondChoisi, b: FondChoisi): Boolean = when {
    a is FondChoisi.EnLigne && b is FondChoisi.EnLigne -> a.fond == b.fond
    a is FondChoisi.Mbtiles && b is FondChoisi.Mbtiles -> a.fichier.name == b.fichier.name
    else -> false
}

/** Ouvre un menu de choix du fond de carte : liste à choix unique (fonds EN LIGNE + fichiers
 *  MBTILES importés), le fond [courant] présélectionné. [onChoix] est invoqué avec le fond
 *  retenu (rien si annulation). Remplace le cyclage « fond suivant » au tap. */
fun choisirFondCarte(context: Context, courant: FondChoisi, onChoix: (FondChoisi) -> Unit) {
    val options: List<FondChoisi> =
        FondCarte.values().map { FondChoisi.EnLigne(it) } +
            MbtilesStore.liste(context).map { FondChoisi.Mbtiles(it) }
    val labels = options.map { libelleFond(it) }.toTypedArray()
    val idx = options.indexOfFirst { memeFond(it, courant) }.coerceAtLeast(0)
    AlertDialog.Builder(context)
        .setTitle("Fond de carte")
        .setSingleChoiceItems(labels, idx) { dialog, which ->
            dialog.dismiss()
            onChoix(options[which])
        }
        .setNegativeButton("Annuler", null)
        .show()
}

/** OpenTopoMap (tuiles XYZ standard z/x/y, CC-BY-SA). Zoom max 17. */
private fun openTopoTileSource(): OnlineTileSourceBase = XYTileSource(
    "OpenTopoMap",
    0, 17, 256, ".png",
    arrayOf(
        "https://a.tile.opentopomap.org/",
        "https://b.tile.opentopomap.org/",
        "https://c.tile.opentopomap.org/",
    ),
    "© OpenTopoMap (CC-BY-SA) · © OpenStreetMap contributors",
    POLICY_PERMISSIVE,
)

/** Esri World Imagery. ATTENTION : ordre de tuiles **z/y/x** (y avant x), pas le z/x/y standard —
 *  d'où une source custom au lieu d'un simple XYTileSource. */
private fun esriImageryTileSource(): OnlineTileSourceBase =
    object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, "", arrayOf(""),
        "Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community", POLICY_PERMISSIVE,
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x    = MapTileIndex.getX(pMapTileIndex)
            val y    = MapTileIndex.getY(pMapTileIndex)
            return "https://server.arcgisonline.com/ArcGIS/rest/services/" +
                "World_Imagery/MapServer/tile/$zoom/$y/$x"
        }
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
