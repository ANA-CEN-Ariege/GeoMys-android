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
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.location.LocationTracker
import fr.ariegenature.geomys.model.PointTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * DEUX DÉFAUTS SIGNALÉS PAR LE PORTAGE iOS (2026-10-17).
 *
 * A — un simple changement de MOT DE PASSE réinitialisait les sélections Occtax, et avec elles les
 *     caches JSON qui peuplent leurs menus : la configuration devenait incomplète, l'écran
 *     Paramètres se verrouillait et plus rien n'était saisissable tant qu'un rechargement n'avait
 *     pas abouti — donc tant qu'il n'y avait pas de réseau. Une rotation de mot de passe ne change
 *     pourtant ni de serveur ni de compte.
 *
 * B — le bouton ▶ repartait toujours de zéro : il effaçait les points déjà enregistrés. Comme
 *     l'enregistrement s'arrête à chaque sortie du flux de saisie, un simple aller-retour par
 *     l'accueil suffisait à repasser le bouton en ▶ — et le relancer perdait le parcours de la
 *     journée, définitivement (le store écrase `pointsParcours` en bloc au premier auto-save).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefautsPortageIosTest {

    private fun tracker() = LocationTracker(ApplicationProvider.getApplicationContext<Context>())

    /** Les états du tracker sont publiés par `postValue` : sans vidange du looper principal, les
     *  LiveData restent à leur valeur initiale et le test mesurerait n'importe quoi. */
    private fun LocationTracker.stabiliser() = also { shadowOf(Looper.getMainLooper()).idle() }

    private val deuxPoints = listOf(PointTrace(42.90, 1.40), PointTrace(42.91, 1.41))

    // ── B : ▶ continue la trace, il ne la recommence pas ────────────────────

    @Test
    fun reprendre_un_parcours_conserve_les_points_deja_enregistres() {
        val t = tracker()
        t.restaurerParcours(deuxPoints); t.stabiliser()
        t.reprendreParcours(); t.stabiliser()
        assertEquals("les points restaurés doivent survivre", deuxPoints, t.parcours.value)
        assertTrue("l'enregistrement doit être réarmé", t.estEnCours.value == true)
    }

    /** CONTRE-ÉPREUVE : démarrer une NOUVELLE trace doit bien repartir de zéro — sinon le
     *  correctif aurait simplement neutralisé la remise à zéro. */
    @Test
    fun demarrer_un_parcours_repart_bien_de_zero() {
        val t = tracker()
        t.restaurerParcours(deuxPoints); t.stabiliser()
        t.demarrerParcours(); t.stabiliser()
        assertTrue("une nouvelle trace part vide", t.parcours.value.isNullOrEmpty())
        assertEquals(0.0, t.distanceTotale.value ?: -1.0, 0.0001)
    }

    @Test
    fun une_pause_puis_une_reprise_ne_perd_rien() {
        val t = tracker()
        t.restaurerParcours(deuxPoints); t.stabiliser()
        t.reprendreParcours(); t.stabiliser()
        t.arreterParcours(); t.stabiliser()
        assertFalse("en pause", t.estEnCours.value == true)
        t.reprendreParcours(); t.stabiliser()
        assertEquals("la pause ne doit rien effacer", deuxPoints, t.parcours.value)
    }

    // ── Gardes sur les SITES D'APPEL (code d'interface, non testable autrement) ──

    private fun source(chemin: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val f = File(dir, chemin)
            if (f.exists()) {
                return f.readLines()
                    .filterNot { val l = it.trim(); l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") }
                    .joinToString("\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("Source introuvable : $chemin")
    }

    /**
     * Le correctif B ne vaut que si le bouton l'emprunte : c'est le listener qui portait le
     * défaut, en ne regardant que `estEnCours`.
     */
    @Test
    fun le_bouton_de_trace_ne_redemarre_de_zero_que_sur_une_trace_vide() {
        val src = source("app/src/main/java/fr/ariegenature/geomys/ui/TraceFragment.kt")
        assertTrue(
            "le bouton doit CONTINUER une trace non vide (reprendreParcours)",
            src.contains("reprendreParcours()"),
        )
        val avantDemarrer = src.substringBefore("tracker.demarrerParcours()")
        assertTrue(
            "demarrerParcours() ne doit être atteint qu'après avoir écarté une trace déjà " +
                "remplie — sinon ▶ efface le parcours en cours (défaut du portage iOS).",
            avantDemarrer.contains("parcours.value.isNullOrEmpty()"),
        )
    }

    /**
     * Le correctif A vit dans `sauvegarderChamps`, qui n'est pas testable unitairement (Fragment,
     * vues, navigation). On garde donc le site d'appel : la réinitialisation des sélections et la
     * purge du cache monitoring disque doivent être conditionnées à un changement d'IDENTITÉ.
     */
    @Test
    fun seul_un_changement_d_identite_reinitialise_les_selections() {
        val src = source("app/src/main/java/fr/ariegenature/geomys/ui/ConfigGeoNatureFragment.kt")
        assertTrue(
            "l'identité doit être définie sur l'URL et le login, pas sur le mot de passe",
            src.contains("val identiteChangee = ancien.first != nouveau.first || ancien.second != nouveau.second"),
        )
        // On vise la seule fonction concernée : « Vider le cache » réinitialise lui aussi les
        // sélections, mais c'est un reset VOLONTAIRE et assumé, qui doit le rester.
        val corps = src.substringAfter("private fun sauvegarderChamps()").substringBefore("private fun testerConnexion()")
        for (appel in listOf("reinitialiserSelectionsOcctax()", "MonitoringCache.vider()")) {
            assertTrue("$appel doit figurer dans sauvegarderChamps", corps.contains(appel))
            val avant = corps.substringBefore(appel)
            assertTrue(
                "$appel doit être placé sous `if (identiteChangee)` : une rotation de mot de " +
                    "passe ne change ni de serveur ni de compte, et enfermait l'utilisateur dans " +
                    "Paramètres sans réseau (défaut du portage iOS).",
                avant.contains("if (identiteChangee)"),
            )
        }
    }
}
