package fr.ariegenature.geonat.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import fr.ariegenature.geonat.R

/** Fil d'Ariane CLIQUABLE des écrans de suivi : "Protocole › Site › Point d'écoute".
 *
 *  Le fil est accumulé de proche en proche dans l'argument de navigation `fil` (encodé) :
 *  chaque écran connaît déjà l'objet de la ligne sur laquelle on tape (type + id + nom),
 *  donc il construit le chemin complet de la destination sans re-fetch d'ascendance.
 *
 *  Le segment d'index 0 est TOUJOURS le protocole (sa fiche = SuiviDetailFragment, instance
 *  unique dans la pile) ; les suivants sont des objets monitoring (FicheObjetFragment).
 *  Un clic remonte au protocole (popBackStack fiable car unique) puis, si la cible est un
 *  objet, ré-ouvre sa fiche — ce qui « replie » proprement les niveaux intermédiaires. */

/** Séparateur d'affichage — chevron `›`, cohérent avec le fil des saisies en attente. */
const val FIL_SEPARATEUR = " › "

/** Un niveau du fil. [type]/[id] ne servent qu'aux segments objets (index ≥ 1) ; pour le
 *  protocole (index 0) seul [label] compte. */
data class FilSegment(val type: String, val id: Int, val label: String)

// Séparateurs de sérialisation : caractères de contrôle (RS / US) absents des noms.
private const val SEP_SEGMENT = "\u001E"
private const val SEP_CHAMP = "\u001F"

/** Sérialise les segments pour les passer dans un Bundle de navigation. */
fun encoderFil(segments: List<FilSegment>): String =
    segments.joinToString(SEP_SEGMENT) { "${it.type}$SEP_CHAMP${it.id}$SEP_CHAMP${it.label}" }

/** Inverse de [encoderFil]. Tolère null/vide → liste vide. */
fun decoderFil(encode: String?): List<FilSegment> {
    if (encode.isNullOrEmpty()) return emptyList()
    return encode.split(SEP_SEGMENT).mapNotNull { seg ->
        val parts = seg.split(SEP_CHAMP)
        if (parts.size < 3) return@mapNotNull null
        // Le label peut théoriquement contenir SEP_CHAMP : on rejoint le reste par sécurité.
        FilSegment(parts[0], parts[1].toIntOrNull() ?: -1, parts.drop(2).joinToString(SEP_CHAMP))
    }
}

/** Rend [segments] dans [tv] sous forme de fil d'Ariane cliquable. Chaque segment cliquable
 *  remonte à son niveau via [naviguerVersFil]. [dernierCliquable] = false quand le dernier
 *  segment est l'écran courant (une fiche s'affiche elle-même en bout de fil) ; true quand
 *  tous les segments sont des ancêtres (cas du formulaire de nouvelle visite). Masque [tv]
 *  si la liste est vide (entrée hors arborescence, ex. édition depuis l'outbox). */
fun appliquerFilAriane(
    tv: TextView,
    nav: NavController,
    moduleCode: String,
    segments: List<FilSegment>,
    dernierCliquable: Boolean,
) {
    if (segments.isEmpty()) {
        tv.visibility = View.GONE
        return
    }
    tv.visibility = View.VISIBLE
    tv.movementMethod = LinkMovementMethod.getInstance()
    val sb = SpannableStringBuilder()
    segments.forEachIndexed { i, seg ->
        if (i > 0) sb.append(FIL_SEPARATEUR)
        val debut = sb.length
        sb.append(seg.label)
        val fin = sb.length
        val estDernier = i == segments.lastIndex
        if (estDernier && !dernierCliquable) return@forEachIndexed
        val cible = segments.subList(0, i + 1).toList()
        sb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = naviguerVersFil(nav, moduleCode, cible)
        }, debut, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    tv.text = sb
}

/** Navigue vers le dernier segment de [cible] (= le niveau cliqué).
 *
 *  - Protocole (taille 1) : simple popBackStack vers sa fiche, unique dans la pile.
 *  - Objet : un seul `navigate` vers FicheObjetFragment avec `popUpTo` détail-protocole
 *    (non inclusif), ce qui replie d'un coup tous les niveaux au-dessus et évite la
 *    fragilité d'un pop suivi d'un navigate. Le fil est tronqué jusqu'à ce niveau. */
private fun naviguerVersFil(nav: NavController, moduleCode: String, cible: List<FilSegment>) {
    if (cible.size <= 1) {
        // En pratique le fil n'est cliquable que dans le flux de drill-down, où
        // SuiviDetailFragment est toujours dans la pile (popBackStack renvoie true).
        nav.popBackStack(R.id.suiviDetailFragment, false)
        return
    }
    val seg = cible.last()
    nav.navigate(
        R.id.ficheObjetFragment,
        bundleOf(
            "moduleCode" to moduleCode,
            "objectType" to seg.type,
            "id" to seg.id,
            "fil" to encoderFil(cible),
        ),
        NavOptions.Builder().setPopUpTo(R.id.suiviDetailFragment, false).build(),
    )
}
