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

package fr.ariegenature.geomys

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.location.LocationTracker
import fr.ariegenature.geomys.model.PointTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Tracé GPS ([LocationTracker]) : les positions précises (≤ 50 m) alimentent le parcours et la
 *  distance pendant un parcours démarré ; une position imprécise est ignorée ; arrêt, restauration
 *  et distance imposée. Simulation via le shadow du LocationManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationTrackerTest {

    private var horloge = System.currentTimeMillis()

    /** Positions espacées de 3 s : le shadow applique le minTime (2 s) / minDistance (5 m) de
     *  requestLocationUpdates comme le vrai LocationManager. */
    private fun loc(lat: Double, lon: Double, precision: Float) = Location(LocationManager.GPS_PROVIDER).apply {
        horloge += 3000
        latitude = lat; longitude = lon; accuracy = precision
        time = horloge
        elapsedRealtimeNanos = horloge * 1_000_000
    }

    @Test
    fun parcours_accumule_les_positions_precises_et_la_distance() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLm = shadowOf(lm)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val tracker = LocationTracker(ctx)
        tracker.demarrer()
        tracker.demarrerParcours()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, tracker.estEnCours.value)

        shadowLm.simulateLocation(loc(42.90, 1.40, 5f))
        shadowLm.simulateLocation(loc(42.90, 1.50, 120f)) // imprécise → ignorée
        shadowLm.simulateLocation(loc(42.91, 1.40, 5f))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("2 points précis", 2, tracker.parcours.value!!.size)
        val d = tracker.distanceTotale.value!!
        assertTrue("≈ 1,1 km entre 42.90 et 42.91 (d=$d)", d > 1000.0 && d < 1300.0)
        assertEquals(42.91, tracker.position.value!!.latitude, 1e-9)

        // Parcours arrêté : la position est toujours suivie, le tracé n'accumule plus.
        tracker.arreterParcours()
        shadowLm.simulateLocation(loc(42.92, 1.40, 5f))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, tracker.parcours.value!!.size)
        assertEquals(42.92, tracker.position.value!!.latitude, 1e-9)
        tracker.arreter()
    }

    @Test
    fun restauration_et_distance_imposee() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val tracker = LocationTracker(ctx)
        tracker.restaurerParcours(listOf(PointTrace(42.9, 1.4), PointTrace(42.91, 1.41), PointTrace(42.92, 1.42)))
        tracker.definirDistance(50.0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, tracker.parcours.value!!.size)
        assertEquals(50.0, tracker.distanceTotale.value!!, 1e-9)
        tracker.reinitialiser()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, tracker.parcours.value!!.size)
        assertEquals(0.0, tracker.distanceTotale.value!!, 1e-9)
        assertEquals(false, tracker.estEnCours.value)
    }
}
