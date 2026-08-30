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

import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.model.OccHabStation
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * EXHAUSTIVITÉ de [OccHabStation.empreinteContenu] (audit 2026-08-27) : tout champ métier du
 * modèle doit faire varier l'empreinte — sinon sa modification passerait pour « aucune
 * modification » et la station n'entrerait jamais dans « Mes stations ». Par réflexion sur les
 * champs des data classes : un nouveau champ ajouté au modèle sans être ajouté à l'empreinte (ni
 * à la liste d'exclusions ci-dessous, documentée) fait échouer ce test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmpreinteExhaustiviteTest {

    /** Champs de la STATION volontairement HORS empreinte : identité/état d'envoi, dérivés de la
     *  géométrie (recalculés à chaque Valider), libellés d'affichage. */
    private val exclusStation = setOf(
        "id", "uuidStation", "idStationServeur", "date",
        "latitude", "longitude",           // centroïde d'un polygone, recalculé localement
        "altitudeMin", "altitudeMax", "surface", // MNT / aire auto — limite assumée (lot A : « i » force)
        "observateursNoms",                // libellés d'affichage (les ids font foi)
        "envoyeGeoNature", "origineServeur", "derniereErreurEnvoi", "envoiIncertain", "empreinteOrigine", "origineEnvoyee",
        "habitats",                        // testé champ par champ ci-dessous
    )

    /** Champs de l'HABITAT hors empreinte : identité locale, uuid serveur, libellé d'affichage. */
    private val exclusHabitat = setOf("id", "uuidHabitat", "habitatLabel")

    private fun stationDeBase() = OccHabStation(
        geometryType = "Polygon", geometryCoordsJson = "[[1.4,42.9],[1.5,42.9],[1.5,43.0]]",
        idDataset = 12, observateursIds = listOf(7), observateursTxt = "DUPONT", stationName = "S",
        comment = "c", anaEvalJson = "{\"enjeu\":\"fort\"}", dateMin = 1L, dateMax = 2L,
        profondeurMin = 0, profondeurMax = 1, precision = 5,
        idNomExposition = 1, idNomCalculSurface = 2, idNomObjetGeographique = 3, idNomTypeSol = 4, idNomTypeMosaique = 5,
        habitats = listOf(OccHabHabitat(idHabitatServeur = 91, cdHab = 629, nomCite = "n", determiner = "d",
            recouvrement = 10.0, precisionTechnique = "p", anaEvalJson = "{\"typicite\":\"bonne\"}",
            idNomTypeDetermination = 1, idNomTechniqueCollecte = 2, idNomAbondance = 3, idNomSensibilite = 4,
            idNomInteretCommunautaire = 5)),
    )

    /** Valeur DIFFÉRENTE de [actuelle] pour le type de [champ] (null → valeur non nulle). */
    private fun autreValeur(champ: Field, actuelle: Any?): Any? = when {
        // org.json tolère un suffixe après le tableau : une chaîne « …]x » parse comme l'originale.
        champ.name == "geometryCoordsJson" -> "[[9.0,9.0],[9.0,10.0],[10.0,10.0]]"
        else -> autreValeurParType(champ, actuelle)
    }

    private fun autreValeurParType(champ: Field, actuelle: Any?): Any? = when (champ.type) {
        Int::class.javaPrimitiveType, Int::class.javaObjectType -> ((actuelle as? Int) ?: 0) + 1
        Long::class.javaPrimitiveType, Long::class.javaObjectType -> ((actuelle as? Long) ?: 0L) + 1
        Double::class.javaPrimitiveType, Double::class.javaObjectType -> ((actuelle as? Double) ?: 0.0) + 1.0
        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> !((actuelle as? Boolean) ?: false)
        String::class.java -> ((actuelle as? String) ?: "") + "x"
        List::class.java -> listOf(999)
        else -> throw AssertionError("type non géré pour ${champ.name} : ${champ.type}")
    }

    private fun champsMetier(classe: Class<*>, exclus: Set<String>): List<Field> =
        classe.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.name !in exclus && !it.name.contains('$') }

    @Test
    fun chaque_champ_metier_de_la_station_fait_varier_l_empreinte() {
        val base = stationDeBase()
        val reference = base.empreinteContenu()
        for (champ in champsMetier(OccHabStation::class.java, exclusStation)) {
            val copie = base.copy()
            champ.isAccessible = true
            champ.set(copie, autreValeur(champ, champ.get(copie)))
            assertNotEquals("champ station « ${champ.name} » ABSENT de empreinteContenu() — l'ajouter " +
                "(ou le documenter dans exclusStation)", reference, copie.empreinteContenu())
        }
    }

    @Test
    fun chaque_champ_metier_d_un_habitat_fait_varier_l_empreinte() {
        val base = stationDeBase()
        val reference = base.empreinteContenu()
        for (champ in champsMetier(OccHabHabitat::class.java, exclusHabitat)) {
            val habitat = base.habitats.single().copy()
            champ.isAccessible = true
            champ.set(habitat, autreValeur(champ, champ.get(habitat)))
            val copie = base.copy(habitats = listOf(habitat))
            assertNotEquals("champ habitat « ${champ.name} » ABSENT de empreinteContenu()", reference, copie.empreinteContenu())
        }
    }
}
