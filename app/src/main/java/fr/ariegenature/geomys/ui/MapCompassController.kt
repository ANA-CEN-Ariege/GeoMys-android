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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.osmdroid.views.MapView

/**
 * Contrôleur de boussole partagé par les écrans-carte (Saisie multi-taxons, mono-taxons, OccHab) :
 * factorise le [SensorEventListener] (fusion accéléro/magnéto ou rotation-vector) et l'enregistrement
 * des capteurs, jusqu'ici copiés-collés à l'identique.
 *
 * L'ÉTAT « la carte suit la boussole » reste porté par le FRAGMENT (champ persistant à travers la
 * recréation de vue) et lu ici via [estActif] — le contrôleur ne le possède pas, pour ne rien
 * changer au comportement. Le toggle (clic sur la boussole) et l'init visuel restent aussi côté
 * fragment (courts, écrivent le champ). Cycle de vie : [demarrer] en onResume, [arreter] en onPause,
 * abandon du contrôleur en onDestroyView (aucun event ne peut alors toucher une vue détachée).
 *
 * @param estActif lit l'état courant du fragment (`carteSuitBoussole`) : true → l'aiguille tourne
 *   avec le téléphone ET la carte compense (nord en haut) ; false → aiguille au repos (0°).
 */
class MapCompassController(
    private val context: Context,
    private val map: MapView,
    private val compass: CompassView,
    private val estActif: () -> Boolean,
) {
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var gravityReady = false
    private var geomagneticReady = false
    private var sensorManager: SensorManager? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val azimuth: Float = when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val r = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(r, event.values)
                    val o = FloatArray(3)
                    SensorManager.getOrientation(r, o)
                    Math.toDegrees(o[0].toDouble()).toFloat()
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                    gravityReady = true
                    return
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                    geomagneticReady = true
                    if (!gravityReady) return
                    val r = FloatArray(9)
                    if (!SensorManager.getRotationMatrix(r, null, gravity, geomagnetic)) return
                    val o = FloatArray(3)
                    SensorManager.getOrientation(r, o)
                    Math.toDegrees(o[0].toDouble()).toFloat()
                }
                else -> return
            }
            if (estActif()) {
                compass.post { compass.setAzimuth(-azimuth) }
                map.post { map.setMapOrientation(-azimuth); map.invalidate() }
            } else {
                compass.post { compass.setAzimuth(0f) }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    /** onResume : enregistre le rotation-vector si dispo, sinon accéléromètre + magnétomètre. */
    fun demarrer() {
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        sensorManager = sm
        val rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVec != null) {
            sm.registerListener(listener, rotVec, SensorManager.SENSOR_DELAY_UI)
        } else {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            }
            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    /** onPause : désenregistre les capteurs et réinitialise l'état de fusion. */
    fun arreter() {
        sensorManager?.unregisterListener(listener)
        sensorManager = null
        gravityReady = false
        geomagneticReady = false
    }
}
