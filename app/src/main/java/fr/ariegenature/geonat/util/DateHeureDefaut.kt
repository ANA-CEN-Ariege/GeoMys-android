package fr.ariegenature.geonat.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Valeurs par défaut « date du jour / heure actuelle » pour les champs date/heure des
 *  formulaires, appliquées seulement quand le serveur GeoNature ne fournit pas de défaut
 *  propre. Les formats correspondent à ce qu'attendent les renderers et le backend :
 *   - date     → "yyyy-MM-dd"          (ISO, accepté en entrée par creerChampDate)
 *   - heure    → "HH:mm"               (cf. creerChampTime)
 *   - datetime → "yyyy-MM-dd HH:mm:ss" (cf. creerChampDateTime / formatDateTime serveur)
 */
object DateHeureDefaut {
    fun dateDuJour(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun heureActuelle(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    fun dateHeureActuelle(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
