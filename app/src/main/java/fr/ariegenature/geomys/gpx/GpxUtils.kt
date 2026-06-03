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

package fr.ariegenature.geomys.gpx

import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.PointTrace
import fr.ariegenature.geomys.model.Sortie
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

private val String.xmlEscaped: String
    get() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun genererGPX(observations: List<Observation>, parcours: List<PointTrace>): String {
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val sb = StringBuilder()
    sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    sb.appendLine("""<gpx version="1.1" creator="GeoMys" xmlns="http://www.topografix.com/GPX/1/1">""")

    for (obs in observations.sortedBy { it.date }) {
        sb.appendLine("""  <wpt lat="${obs.latitude}" lon="${obs.longitude}">""")
        sb.appendLine("""    <time>${iso.format(Date(obs.date))}</time>""")
        sb.appendLine("""    <name>${obs.espece.xmlEscaped}</name>""")
        var desc = if (obs.nombre > 1) "${obs.nombre} individus" else "1 individu"
        if (obs.notes.isNotEmpty()) desc += " — ${obs.notes.xmlEscaped}"
        sb.appendLine("""    <desc>$desc</desc>""")
        sb.appendLine("""    <sym>Waypoint</sym>""")
        sb.appendLine("""    <type>Observation ornithologique</type>""")
        sb.appendLine("""  </wpt>""")
    }

    if (parcours.isNotEmpty()) {
        sb.appendLine("""  <trk>""")
        sb.appendLine("""    <name>Parcours GeoMys</name>""")
        sb.appendLine("""    <trkseg>""")
        for (pt in parcours) {
            sb.appendLine("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}"/>""")
        }
        sb.appendLine("""    </trkseg>""")
        sb.appendLine("""  </trk>""")
    }

    sb.appendLine("</gpx>")
    return sb.toString()
}

fun importerGPX(data: ByteArray): Sortie? {
    val handler = GPXHandler()
    return try {
        val factory = SAXParserFactory.newInstance()
        val parser = factory.newSAXParser()
        parser.parse(data.inputStream(), handler)
        handler.buildSortie()
    } catch (e: Exception) { null }
}

private class GPXHandler : DefaultHandler() {
    private val trkpts = mutableListOf<PointTrace>()
    private val wpts = mutableListOf<WptData>()

    private var inWpt = false
    private var inTrkpt = false
    private var currentLat: Double? = null
    private var currentLon: Double? = null
    private var currentTime: Long? = null
    private var currentName = ""
    private var currentDesc = ""
    private var currentText = StringBuilder()
    private var premiereDate: Long? = null

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    data class WptData(val lat: Double, val lon: Double, val time: Long?, val name: String, val desc: String)

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        currentText.clear()
        when (qName) {
            "wpt" -> {
                inWpt = true
                currentLat = attributes.getValue("lat")?.toDoubleOrNull()
                currentLon = attributes.getValue("lon")?.toDoubleOrNull()
                currentTime = null; currentName = ""; currentDesc = ""
            }
            "trkpt" -> {
                inTrkpt = true
                currentLat = attributes.getValue("lat")?.toDoubleOrNull()
                currentLon = attributes.getValue("lon")?.toDoubleOrNull()
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        currentText.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        val text = currentText.toString().trim()
        if (inWpt) {
            when (qName) {
                "name" -> currentName = text
                "desc" -> currentDesc = text
                "time" -> currentTime = try { iso.parse(text)?.time } catch (e: Exception) { null }
                "wpt" -> {
                    val lat = currentLat ?: return
                    val lon = currentLon ?: return
                    wpts.add(WptData(lat, lon, currentTime, currentName, currentDesc))
                    inWpt = false
                }
            }
        } else if (inTrkpt) {
            when (qName) {
                "time" -> if (premiereDate == null) premiereDate = try { iso.parse(text)?.time } catch (e: Exception) { null }
                "trkpt" -> {
                    val lat = currentLat ?: return
                    val lon = currentLon ?: return
                    trkpts.add(PointTrace(lat, lon))
                    inTrkpt = false
                }
            }
        }
    }

    fun buildSortie(): Sortie? {
        if (trkpts.isEmpty() && wpts.isEmpty()) return null
        var distance = 0.0
        var prev: PointTrace? = null
        for (pt in trkpts) {
            if (prev != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(prev.latitude, prev.longitude, pt.latitude, pt.longitude, results)
                distance += results[0]
            }
            prev = pt
        }
        val observations = wpts.map { wpt ->
            var nombre = 1
            var notes = wpt.desc
            val match = Regex("""^(\d+) individu""").find(wpt.desc)
            if (match != null) {
                val parts = wpt.desc.split(" — ")
                nombre = parts[0].split(" ").firstOrNull()?.toIntOrNull() ?: 1
                notes = if (parts.size > 1) parts.drop(1).joinToString(" — ") else ""
            }
            Observation(
                espece = wpt.name.ifEmpty { "Espèce inconnue" },
                latitude = wpt.lat, longitude = wpt.lon,
                date = wpt.time ?: premiereDate ?: System.currentTimeMillis(),
                notes = notes, nombre = nombre
            )
        }
        val dateSortie = premiereDate ?: wpts.mapNotNull { it.time }.minOrNull() ?: System.currentTimeMillis()
        return Sortie(date = dateSortie, pointsParcours = trkpts, observations = observations, distanceTotale = distance)
    }
}