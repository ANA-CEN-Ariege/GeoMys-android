package com.example.birdstrace.model

import java.util.Date
import java.util.UUID

enum class Taxon { OISEAU, MAMMIFERE, REPTILE, BATRACIEN, POISSON, INSECTE, FONGE, INVERTEBRES, PLANTE }

data class PointTrace(
    val latitude: Double,
    val longitude: Double
)

data class Observation(
    val id: String = UUID.randomUUID().toString(),
    var espece: String,
    var taxon: Taxon? = null,   // null = OISEAU (rétrocompatibilité JSON existant)
    var cdNom: Int? = null,
    var latitude: Double,
    var longitude: Double,
    var date: Long = System.currentTimeMillis(),
    var notes: String = "",
    var nombre: Int = 1,
    var sexe: String? = null,
    var stadeVie: String? = null,
    var techniqueObs: String? = null,
    var statutBio: String? = null,
    var etaBio: String? = null,
    var preuveExist: String? = null,
    var objDenbr: String? = null,
    var typDenbr: String? = null,
    var comportement: String? = null,
    var methDetermin: String? = null,
    var determinateur: String? = null
)

data class Sortie(
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    var pointsParcours: List<PointTrace> = emptyList(),
    var observations: List<Observation> = emptyList(),
    var distanceTotale: Double = 0.0,
    var envoyeGeoNature: Boolean = false,
    var estImportee: Boolean = false
)