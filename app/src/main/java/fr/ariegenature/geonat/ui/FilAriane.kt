package fr.ariegenature.geonat.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
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

/** Marqueurs de [FilSegment.type] pour les niveaux racine fixes (la cible de leur clic
 *  ne dépend pas d'un id d'objet mais d'une destination fixe). Les segments objets, eux,
 *  portent leur vrai `object_type`. */
const val FIL_TYPE_ACCUEIL = "__accueil__"
const val FIL_TYPE_SUIVIS = "__suivis__"
const val FIL_TYPE_MODULE = "__module__"

/** Un niveau du fil. Pour les segments objets, [type]/[id] sont l'`object_type` et l'id
 *  serveur. Pour les racines [FIL_TYPE_SUIVIS]/[FIL_TYPE_MODULE], seul [label] est affiché —
 *  la destination est fixe (liste des protocoles / détail du protocole courant). */
data class FilSegment(val type: String, val id: Int, val label: String)

/** Préfixe racine commun à tous les fils de suivi : "🏠 › Suivis › <protocole>". L'entrée
 *  ACCUEIL est rendue sous forme d'icône maison cliquable (cf [appliquerFilAriane]) pour
 *  permettre de revenir à l'écran d'accueil depuis n'importe quelle profondeur de drill.
 *  [moduleLabel] = libellé du protocole courant. */
fun filRacineSuivis(moduleLabel: String): List<FilSegment> = listOf(
    FilSegment(FIL_TYPE_ACCUEIL, -1, "Accueil"),
    FilSegment(FIL_TYPE_SUIVIS, -1, "Monitoring"),
    FilSegment(FIL_TYPE_MODULE, -1, moduleLabel),
)

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
    val couleurLien = ContextCompat.getColor(tv.context, R.color.jaune_clair)
    segments.forEachIndexed { i, seg ->
        if (i > 0) sb.append(FIL_SEPARATEUR)
        val debut = sb.length
        // Pour le segment ACCUEIL, on insère un placeholder remplacé par une icône maison
        // via ImageSpan — le label "Accueil" sert uniquement de fallback talkback / copier-
        // coller. Les autres segments restent en texte normal.
        if (seg.type == FIL_TYPE_ACCUEIL) {
            sb.append(" ")
            val drawable = ContextCompat.getDrawable(tv.context, R.drawable.ic_home)
            if (drawable != null) {
                // Taille = 2× la taille du texte (×2.10 pour cumuler le petit boost optique
                // d'avant 1.05 + le +100% demandé). Sur un fil 14sp ça donne ~30sp d'icône,
                // bien visible en plein soleil sans déséquilibrer le bandeau.
                val taille = (tv.textSize * 2.10f).toInt()
                drawable.setBounds(0, 0, taille, taille)
                // Teinte jaune clair pour ressortir sur le fond accueil sombre — cohérent
                // avec les liens cliquables du fil et les boutons "+" des suivis.
                drawable.setTint(couleurLien)
                // ALIGN_BOTTOM : le bas de l'icône s'aligne sur la ligne de base du texte
                // → l'icône grossie reste posée sur la même ligne que "Suivis › Protocole"
                // au lieu de flotter au centre (ALIGN_CENTER décalait visuellement vers le
                // haut quand la taille de l'icône dépasse celle du texte).
                sb.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), debut, debut + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            sb.append(seg.label)
        }
        val fin = sb.length
        val estDernier = i == segments.lastIndex
        if (estDernier && !dernierCliquable) return@forEachIndexed
        val cible = segments.subList(0, i + 1).toList()
        // ClickableSpan personnalisé : couleur jaune clair + pas de soulignement (l'underline
        // par défaut système devient agressif sur un fil avec plusieurs liens). Le tap reste
        // détecté via onClick.
        sb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = naviguerVersFil(nav, moduleCode, cible)
            override fun updateDrawState(ds: TextPaint) {
                ds.color = couleurLien
                ds.isUnderlineText = false
            }
        }, debut, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    tv.text = sb
}

/** Bandeau de navigation simplifié des écrans de SAISIE (mono / multi-taxons) :
 *  "🏠 › <type de saisie>". Contrairement au fil d'Ariane des suivis, il n'a que deux
 *  niveaux et un seul est cliquable : l'icône maison (retour à l'accueil). Le libellé du
 *  type de saisie est le niveau courant, non cliquable.
 *
 *  [typeLabel] = "Saisie mono-taxons" ou "Saisie multi-taxons" (cf. R.string). Masque [tv]
 *  si vide (état non initialisé, ex. restauration après mort du process). */
fun appliquerBandeauSaisie(tv: TextView, nav: NavController, typeLabel: String) {
    if (typeLabel.isEmpty()) {
        tv.visibility = View.GONE
        return
    }
    tv.visibility = View.VISIBLE
    tv.movementMethod = LinkMovementMethod.getInstance()
    val sb = SpannableStringBuilder()
    val couleurLien = ContextCompat.getColor(tv.context, R.color.jaune_clair)
    // Icône maison cliquable (même rendu que le fil des suivis) : placeholder espace remplacé
    // par un ImageSpan teinté jaune clair.
    sb.append(" ")
    val drawable = ContextCompat.getDrawable(tv.context, R.drawable.ic_home)
    if (drawable != null) {
        val taille = (tv.textSize * 2.10f).toInt()
        drawable.setBounds(0, 0, taille, taille)
        drawable.setTint(couleurLien)
        sb.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    sb.setSpan(object : ClickableSpan() {
        override fun onClick(widget: View) {
            if (!nav.popBackStack(R.id.accueilFragment, false)) nav.navigate(R.id.accueilFragment)
        }
        override fun updateDrawState(ds: TextPaint) {
            ds.color = couleurLien
            ds.isUnderlineText = false
        }
    }, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sb.append(FIL_SEPARATEUR)
    sb.append(typeLabel)
    tv.text = sb
}

/** Navigue vers le dernier segment de [cible] (= le niveau cliqué).
 *
 *  - Protocole (taille 1) : simple popBackStack vers sa fiche, unique dans la pile.
 *  - Objet : un seul `navigate` vers FicheObjetFragment avec `popUpTo` détail-protocole
 *    (non inclusif), ce qui replie d'un coup tous les niveaux au-dessus et évite la
 *    fragilité d'un pop suivi d'un navigate. Le fil est tronqué jusqu'à ce niveau. */
private fun naviguerVersFil(nav: NavController, moduleCode: String, cible: List<FilSegment>) {
    val seg = cible.last()
    when (seg.type) {
        // Accueil (icône maison) → racine de l'app. popBackStack jusqu'à accueilFragment ;
        // si on n'y arrive pas (pile rétractée), navigate explicite avec clearBackStack pour
        // ne laisser que l'accueil dans la pile.
        FIL_TYPE_ACCUEIL ->
            if (!nav.popBackStack(R.id.accueilFragment, false)) nav.navigate(R.id.accueilFragment)
        // "Suivis" → liste des protocoles. Remonter si dans la pile (drill-down), sinon ouvrir.
        FIL_TYPE_SUIVIS ->
            if (!nav.popBackStack(R.id.suivisFragment, false)) nav.navigate(R.id.suivisFragment)
        // Protocole → son détail. Remonter si présent (fiable car unique), sinon ouvrir.
        FIL_TYPE_MODULE ->
            if (!nav.popBackStack(R.id.suiviDetailFragment, false)) {
                nav.navigate(R.id.suiviDetailFragment, bundleOf("moduleCode" to moduleCode))
            }
        // Objet : un seul `navigate` vers sa fiche. Le popUpTo replie les niveaux au-dessus du
        // détail-protocole quand il est présent (drill-down) ; sinon il est sans effet et on
        // empile simplement la fiche cible (flux édition depuis « Saisies en attente »).
        else -> nav.navigate(
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
}
