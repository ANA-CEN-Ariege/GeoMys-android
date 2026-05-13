package fr.ariegenature.geonat.model

import java.util.Date
import java.util.UUID

enum class Taxon { OISEAU, MAMMIFERE, REPTILE, BATRACIEN, POISSON, INSECTE, FONGE, MOLLUSQUE, INVERTEBRES, PLANTE }

data class PointTrace(
    val latitude: Double,
    val longitude: Double
)

/** Un dénombrement (counting) GeoNature : 1 occurrence peut en avoir plusieurs.
 *  Chacun a sa propre tranche d'effectif + caractéristiques (sexe, stade…). */
data class Denombrement(
    val id: String = UUID.randomUUID().toString(),
    var nombreMin: Int = 1,
    var nombreMax: Int = 1,
    var sexe: String? = null,
    var stadeVie: String? = null,
    var objDenbr: String? = null,
    var typDenbr: String? = null,
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
    // ── Champs du counting #0 (conservés en flat pour rétrocompat Gson + mono-taxon) ──
    var nombre: Int = 1,
    var sexe: String? = null,
    var stadeVie: String? = null,
    var objDenbr: String? = null,
    var typDenbr: String? = null,
    // ── Caractérisation de l'occurrence ──
    var techniqueObs: String? = null,
    var statutBio: String? = null,
    var etaBio: String? = null,
    var preuveExist: String? = null,
    var comportement: String? = null,
    var methDetermin: String? = null,
    var determinateur: String? = null,
    // UUID partagé par les obs d'une même saisie multi-taxons : à l'envoi, elles seront
    // regroupées dans un seul relevé GeoNature (1 relevé = 1 point + N occurrences).
    // null pour les obs mono-taxon (saisie rapide, import GPX) → chacune devient son propre relevé.
    var releveId: String? = null,
    // ── Extras counting #0 (mode multi-taxon) ──
    /** Si null, le counting #0 utilise `nombre` pour count_min ET count_max. */
    var nombreMax: Int? = null,
    // ── Dénombrements supplémentaires (mode multi-taxon : countings 1..N) ──
    /** Le counting #0 est représenté par les champs flat ci-dessus ; cette liste
     *  contient les dénombrements supplémentaires éventuels. À l'envoi GeoNature,
     *  un cor_counting_occtax est émis pour chaque (counting #0 + entrées de cette liste). */
    var denombrementsAdditionnels: List<Denombrement> = emptyList(),
    // ── Statut d'observation ──
    var statutObs: String? = null,
    // ── Média attaché (1 max par obs) ──
    /** URI locale (file://) du média copié dans le storage privé de l'app, ou null. */
    var mediaUri: String? = null,
    /** Type MIME du média (ex: "image/jpeg", "audio/mp4"). */
    var mediaMimeType: String? = null,
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