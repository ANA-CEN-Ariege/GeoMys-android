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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geomys.databinding.FragmentSaisiesEnAttenteBinding
import fr.ariegenature.geomys.network.OutboxEnvoi
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OutboxMonitoring
import fr.ariegenature.geomys.store.SaisieEnAttente
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Liste les saisies monitoring (visites + obs) en attente d'envoi vers le serveur.
 *  Envoi exclusivement à la demande, et **par groupe** : l'icône ➤ d'une racine (ou l'option
 *  "Envoyer ce groupe") appelle [OutboxEnvoi.envoyerGroupe] pour cette saisie + ses descendants
 *  locaux. Aucun envoi automatique, et pas d'envoi global ici (OutboxEnvoi.envoyerTout existe et
 *  est couvert par les tests, mais n'est pas câblé à cet écran). */
class SaisiesEnAttenteFragment : Fragment() {
    private var _binding: FragmentSaisiesEnAttenteBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSaisiesEnAttenteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "Mes visites")
        rafraichir()
    }

    override fun onResume() {
        super.onResume()
        rafraichir()
    }

    private fun rafraichir() {
        val toutes = OutboxMonitoring.tout()
        val enAttente = toutes.count { it.etat == SaisieEnAttente.Etat.PENDING || it.etat == SaisieEnAttente.Etat.ERROR }
        val envoyees = toutes.count { it.etat == SaisieEnAttente.Etat.SENT }
        binding.tvResume.text = when {
            toutes.isEmpty() -> "Aucune saisie locale."
            enAttente == 0 -> "Toutes les saisies ont été envoyées ($envoyees)."
            else -> "$enAttente en attente · $envoyees envoyées"
        }
        peuplerListe(toutes)
    }

    private fun peuplerListe(saisies: List<SaisieEnAttente>) {
        binding.llSaisies.removeAllViews()
        if (saisies.isEmpty()) {
            binding.llSaisies.addView(TextView(requireContext()).apply {
                text = "Les saisies que tu enregistres apparaîtront ici jusqu'à leur envoi."
                setTextColor(couleurSecondaire(requireContext()))
                textSize = 13f
            })
            return
        }
        val fmtDate = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)

        // Présentation par "groupe" parent → enfants. On identifie les racines locales
        // (= saisies dont le parentUuidLocal est nul OU pointe vers un uuid absent de la
        // file — typiquement le parent a déjà été envoyé), on les trie par état puis date,
        // puis on DFS récursivement chaque sous-arbre. Chaque ligne reçoit sa profondeur
        // pour qu'on l'indente visuellement.
        val parParent = saisies.groupBy { it.parentUuidLocal }
        val uuidsConnus = saisies.mapTo(HashSet()) { it.uuid }
        val racines = saisies
            .filter { it.parentUuidLocal == null || it.parentUuidLocal !in uuidsConnus }
            .sortedWith(compareBy({ ordrePourTri(it.etat) }, { it.dateLocale }))

        // Regroupement hiérarchique : Protocole → Site (header de groupe) → Visite/Obs.
        // On préserve l'ordre des racines (déjà trié par état/date) au sein de chaque
        // module, et on ordonne les modules par leur ordre d'apparition de la 1re racine.
        val racinesParModule = linkedMapOf<String, MutableList<SaisieEnAttente>>()
        racines.forEach { r ->
            racinesParModule.getOrPut(r.moduleCode) { mutableListOf() }.add(r)
        }

        racinesParModule.forEach { (moduleCode, racinesModule) ->
            binding.llSaisies.addView(creerHeaderProtocole(moduleCode))
            racinesModule.forEach { racine ->
                // Header de groupe : on remonte la chaîne des parents serveur du parent direct
                // de la racine pour situer le groupe (par ex. "Forêt de Foix › Point Foix-Nord"
                // pour une visite faite sur un point d'écoute). Si le parent serveur n'a jamais
                // été ouvert dans l'app (cache vide), on omet le header — le titre de la racine
                // gardera son fallback "type #id".
                creerHeaderGroupe(racine)?.let { binding.llSaisies.addView(it) }

                val lignes = mutableListOf<Pair<SaisieEnAttente, Int>>()
                fun ajouterArbre(s: SaisieEnAttente, profondeur: Int) {
                    lignes.add(s to profondeur)
                    parParent[s.uuid].orEmpty()
                        .sortedWith(compareBy({ ordrePourTri(it.etat) }, { it.dateLocale }))
                        .forEach { ajouterArbre(it, profondeur + 1) }
                }
                ajouterArbre(racine, 0)
                lignes.forEach { (s, profondeur) ->
                    binding.llSaisies.addView(creerLigne(s, fmtDate, profondeur))
                }
            }
        }
    }

    /** Header de section "Protocole : <nom>" qui regroupe toutes les saisies d'un même
     *  moduleCode. Permet de scanner rapidement la liste quand plusieurs protocoles ont
     *  des saisies en attente en parallèle. Style bleu foncé en majuscules pour bien le
     *  distinguer du header de groupe (📍 Site, plus discret). */
    private fun creerHeaderProtocole(moduleCode: String): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val label = fr.ariegenature.geomys.network.MonitoringApi.labelModuleEnCache(moduleCode)
            ?: moduleCode
        return TextView(ctx).apply {
            text = "🔬 $label"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // Label "🔬 PROTOCOLE" : jaune clair pour cohérence avec le reste des éléments
            // cliquables / accent du fil Suivis. Avant : colorPrimary (bleu).
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, fr.ariegenature.geomys.R.color.jaune_clair))
            isAllCaps = true
            setPadding(
                (12 * density).toInt(), (18 * density).toInt(),
                (12 * density).toInt(), (2 * density).toInt(),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    /** Construit le bandeau de contexte d'un groupe : icône 📍 + chemin du parent serveur
     *  le plus haut vers le parent direct. Retourne null si la racine n'a pas de parent
     *  serveur identifiable (cas d'une saisie isolée hors arborescence).
     *
     *  Le chemin part de l'ancêtre le plus lointain à gauche pour qu'on lise toujours du
     *  contexte le plus large au plus précis (cohérent avec un fil d'Ariane classique). */
    private fun creerHeaderGroupe(racine: SaisieEnAttente): View? {
        val parentType = racine.parentObjectType?.takeIf { it.isNotEmpty() } ?: return null
        val parentId = racine.parentIdServeur ?: return null
        val labelDirect = fr.ariegenature.geomys.network.MonitoringApi
            .labelObjetEnCache(racine.moduleCode, parentType, parentId)
            ?: "$parentType #$parentId"
        val ancetres = fr.ariegenature.geomys.network.MonitoringApi.chaineParentsEnCache(
            racine.moduleCode, parentType, parentId,
        )
        // Chaîne du plus haut au plus bas (parent direct en dernier). Chaque segment est
        // préfixé par le label humain de son type (ex. "Site : Forêt de Foix"). Si le
        // schéma cache n'a pas de label, fallback sur le type technique capitalisé.
        val chemin = (ancetres.reversed().map { it.first to it.third } + (parentType to labelDirect))
            .joinToString(" › ") { (type, label) ->
                val labelType = fr.ariegenature.geomys.network.MonitoringApi
                    .labelTypeEnCache(racine.moduleCode, type)
                    ?: type.replaceFirstChar { it.uppercase() }
                "$labelType : $label"
            }
        val density = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            text = "📍 $chemin"
            textSize = 12f
            setTextColor(couleurSurOnSurface(requireContext()))
            setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (4 * density).toInt(),
            )
            // Marge top plus généreuse pour bien séparer les groupes les uns des autres.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (12 * density).toInt() }
        }
    }

    /** Extrait le nom humain du taxon stocké dans [s.valeursJson] (si présent). On lit la
     *  clé `cd_nom` du payload — convention gn_module_monitoring — et on résout via
     *  TaxRefCache. Préfère le nom français quand dispo, sinon le nom scientifique.
     *  Retourne null si la saisie ne porte pas de taxon ou si le cd_nom est inconnu. */
    private fun nomTaxonDeSaisie(s: SaisieEnAttente): String? {
        val cdNom = try {
            val obj = org.json.JSONObject(s.valeursJson)
            when (val v = obj.opt("cd_nom")) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            }
        } catch (_: Exception) { null } ?: return null
        if (cdNom <= 0) return null
        return fr.ariegenature.geomys.store.TaxRefCache.nomAffichageParCdNom(cdNom)
    }

    private fun ordrePourTri(etat: SaisieEnAttente.Etat) = when (etat) {
        SaisieEnAttente.Etat.PENDING, SaisieEnAttente.Etat.SENDING -> 0
        SaisieEnAttente.Etat.ERROR -> 1
        SaisieEnAttente.Etat.SENT -> 2
    }

    private fun creerLigne(s: SaisieEnAttente, fmtDate: SimpleDateFormat, profondeur: Int = 0): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        // Indentation : 20dp par niveau pour bien voir la filiation parent → enfant. Les
        // enfants gardent le même padding vertical et droit, seul le gauche augmente.
        val padGauche = ((12 + 20 * profondeur) * density).toInt()
        val padVert = (10 * density).toInt()
        val padDroit = (12 * density).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padGauche, padVert, padDroit, padVert)
            // États signalés par un CADRE coloré sur le fond du thème (les anciens fonds
            // pastel — rose/vert/ambre très clairs — passaient pour des fonds blancs sur le
            // thème sombre et rendaient les textes illisibles) : rouge = erreur, vert =
            // envoyée (groupe conservé tant qu'il reste des obs), ambre = envoi en cours.
            when (s.etat) {
                SaisieEnAttente.Etat.ERROR -> background = cadreColore(couleurErreur(ctx), density)
                SaisieEnAttente.Etat.SENT -> background = cadreColore(0xFF4CAF50.toInt(), density)     // vert
                SaisieEnAttente.Etat.SENDING -> background = cadreColore(0xFFFFB300.toInt(), density)  // ambre
                else -> setBackgroundColor(0x00000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (if (profondeur == 0) 4 else 1) * density.toInt() }
        }
        val icone = when (s.etat) {
            SaisieEnAttente.Etat.PENDING -> "⏳"
            SaisieEnAttente.Etat.SENDING -> "🚀"
            SaisieEnAttente.Etat.SENT -> "✅"
            SaisieEnAttente.Etat.ERROR -> "⚠"
        }
        // Préfixe "↳" sur les enfants pour renforcer visuellement la filiation même quand
        // l'indentation seule reste discrète sur petit écran.
        val flecheEnfant = if (profondeur > 0) "↳ " else ""
        // parentInfo : ne l'affiche que pour les saisies racines (profondeur 0). Sur un
        // enfant, l'indentation + le ↳ portent déjà l'info — répéter "parent local" ferait
        // bruit. On garde le parent serveur explicite sur les racines (utile pour
        // localiser l'obs rattachée à une visite déjà envoyée).
        // Le parent serveur de la racine est désormais affiché dans le header de groupe
        // (creerHeaderGroupe). Plus de suffixe " — <parent>" ici : ce serait redondant et
        // tronquerait inutilement les titres longs (taxons en latin, etc.).
        val parentInfo = ""
        // Header : titre à gauche (weight=1 pour absorber l'espace dispo) + icônes
        // d'actions à droite. Les actions ne sont rendues que pour les saisies encore
        // modifiables (PENDING/ERROR). Sur SENT/SENDING, le tap court ouvre toujours le
        // menu options "Retirer / Réessayer".
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // Préfère le label humain du type (depuis le schéma cache) pour rester cohérent
        // avec le header de groupe (qui affiche par ex. "Visite", "Observation").
        val labelType = fr.ariegenature.geomys.network.MonitoringApi
            .labelTypeEnCache(s.moduleCode, s.objectType)
            ?: s.objectType.replaceFirstChar { it.uppercase() }
        // Si la saisie porte un cd_nom (typiquement une observation), on remplace le label
        // type par le nom du taxon — plus parlant que "Observation" générique. Le type
        // reste accessible visuellement via l'indentation et le contexte du groupe.
        val titrePrincipal = nomTaxonDeSaisie(s) ?: labelType
        header.addView(TextView(ctx).apply {
            text = "$flecheEnfant$icone $titrePrincipal$parentInfo"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        if (s.etat == SaisieEnAttente.Etat.PENDING || s.etat == SaisieEnAttente.Etat.ERROR) {
            ajouterIconesActions(header, s, profondeur)
        } else if (profondeur == 0 && s.etat == SaisieEnAttente.Etat.SENT && aDescendantsAEnvoyer(s)) {
            // Visite déjà envoyée mais obs restantes : la ligne reste affichée comme groupe
            // (cf. purgerSent) et garde UNIQUEMENT la flèche — qui n'enverra que le reste
            // (l'objet créé n'est jamais re-POSTé, cf. SaisieEnAttente.objetCree).
            header.addView(creerIconeAction(
                fr.ariegenature.geomys.R.drawable.ic_send,
                "Envoyer les saisies restantes",
                tintBleu = true,
            ) { lancerEnvoiGroupe(s.uuid) })
        }
        row.addView(header)
        row.addView(TextView(ctx).apply {
            text = "${s.moduleCode} · ${fmtDate.format(Date(s.dateLocale))}" +
                (s.idServeur?.let { " · id serveur=$it" } ?: "")
            textSize = 12f
            setTextColor(couleurSecondaire(ctx))
        })
        s.messageErreur?.takeIf { it.isNotBlank() }?.let { err ->
            row.addView(TextView(ctx).apply {
                text = err
                textSize = 12f
                setTextColor(couleurErreur(ctx))
            })
        }
        // Tap court = options (réessayer en cas d'erreur, retirer pour SENT). Les
        // ImageButton du header consomment leur propre clic.
        row.setOnClickListener { afficherOptions(s) }
        return row
    }

    /** La saisie a-t-elle au moins un descendant local encore à envoyer (PENDING/ERROR) ?
     *  Détermine si une visite déjà SENT doit garder sa flèche « envoyer le reste ». */
    private fun aDescendantsAEnvoyer(s: SaisieEnAttente): Boolean {
        val descendants = OutboxMonitoring.descendants(s.uuid).toSet()
        if (descendants.isEmpty()) return false
        return OutboxMonitoring.tout().any {
            it.uuid in descendants &&
                (it.etat == SaisieEnAttente.Etat.PENDING || it.etat == SaisieEnAttente.Etat.ERROR)
        }
    }

    /** Ajoute les icônes d'action à droite du titre d'une saisie. Sur une racine
     *  (profondeur 0) : Envoyer le groupe + Éditer + Supprimer. Sur un enfant
     *  (profondeur > 0) : Éditer + Supprimer (l'envoi reste géré par "Envoyer le groupe"
     *  de la racine ou par "Envoyer tout" — un enfant ne peut pas partir sans son parent). */
    private fun ajouterIconesActions(parent: LinearLayout, s: SaisieEnAttente, profondeur: Int) {
        if (profondeur == 0) {
            parent.addView(creerIconeAction(
                fr.ariegenature.geomys.R.drawable.ic_send,
                "Envoyer ce groupe",
                tintBleu = true,
            ) { lancerEnvoiGroupe(s.uuid) })
        }
        parent.addView(creerIconeAction(
            fr.ariegenature.geomys.R.drawable.ic_edit,
            "Éditer cette saisie",
            tintBleu = true,
        ) { ouvrirEdition(s) })
        parent.addView(creerIconeAction(
            fr.ariegenature.geomys.R.drawable.ic_delete,
            "Supprimer cette saisie",
            tintBleu = false,
        ) {
            val nbEnfants = OutboxMonitoring.descendants(s.uuid).size
            demanderSuppression(s, nbEnfants)
        })
    }

    /** ImageButton compact avec fond borderless (ripple) pour ne pas alourdir la ligne. La
     *  couleur d'icône suit le thème primaire pour Envoyer/Éditer, rouge pour Supprimer. */
    private fun creerIconeAction(
        drawableId: Int,
        description: String,
        tintBleu: Boolean,
        action: () -> Unit,
    ): android.widget.ImageButton {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val attr = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, attr, true)
        return android.widget.ImageButton(ctx).apply {
            setImageResource(drawableId)
            contentDescription = description
            setBackgroundResource(attr.resourceId)
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                (40 * density).toInt(), (40 * density).toInt(),
            )
            // Icônes d'action : jaune clair pour Envoyer/Éditer (cohérence cliquable),
            // colorError pour Supprimer (sémantique destructive maintenue).
            val couleur = if (tintBleu)
                androidx.core.content.ContextCompat.getColor(ctx, fr.ariegenature.geomys.R.color.jaune_clair)
            else couleurErreur(ctx)
            setColorFilter(couleur)
            setOnClickListener { action() }
        }
    }

    /** Navigation vers [NouvelleVisiteFragment] en mode édition pour la saisie [s]. Le
     *  fragment va récupérer les autres meta (parent serveur, type, etc.) directement
     *  depuis [OutboxMonitoring] via l'editUuid — pas besoin de tout passer ici. */
    private fun ouvrirEdition(s: SaisieEnAttente) {
        findNavController().navigate(
            fr.ariegenature.geomys.R.id.action_attente_to_edition,
            androidx.core.os.bundleOf(
                "editUuid" to s.uuid,
                // Fil d'Ariane reconstruit depuis le cache (le formulaire l'affichera en
                // texte simple : pas de pile de drill-down à remonter dans ce contexte).
                "fil" to construireFilPourEdition(s),
            ),
        )
    }

    /** Reconstruit le fil d'Ariane (encodé) d'une saisie pour l'écran d'édition : protocole
     *  puis chaîne des parents serveur, via le même cache que [creerHeaderGroupe]. Les ids
     *  réels (lus du cache) sont conservés pour que chaque segment reste cliquable et ouvre
     *  la fiche correspondante. Renvoie au moins le segment protocole ; vide si moduleCode
     *  manque. */
    private fun construireFilPourEdition(s: SaisieEnAttente): String {
        val segments = mutableListOf<FilSegment>()
        val moduleLabel = fr.ariegenature.geomys.network.MonitoringApi
            .labelModuleEnCache(s.moduleCode) ?: s.moduleCode
        // Racine "Suivis › <protocole>".
        segments.addAll(filRacineSuivis(moduleLabel))
        val parentType = s.parentObjectType?.takeIf { it.isNotEmpty() }
        val parentId = s.parentIdServeur
        if (parentType != null && parentId != null) {
            val labelDirect = fr.ariegenature.geomys.network.MonitoringApi
                .labelObjetEnCache(s.moduleCode, parentType, parentId) ?: "$parentType #$parentId"
            val ancetres = fr.ariegenature.geomys.network.MonitoringApi
                .chaineParentsEnCache(s.moduleCode, parentType, parentId)
            // ancetres = du parent direct vers le haut (type, id, label) → on inverse pour
            // lire haut→bas, puis on ajoute le parent direct en queue (cohérent avec
            // creerHeaderGroupe).
            ancetres.reversed().forEach { (type, id, label) ->
                segments.add(FilSegment(type, id, label))
            }
            segments.add(FilSegment(parentType, parentId, labelDirect))
        }
        return encoderFil(segments)
    }

    private fun afficherOptions(s: SaisieEnAttente) {
        val ctx = requireContext()
        // Descendants locaux = obs rattachées à cette visite (et leurs propres enfants
        // éventuels). Influent sur :
        //  - le libellé / message du "Supprimer" (cascade D)
        //  - la présence de l'action "Envoyer ce groupe" (F).
        val enfants = OutboxMonitoring.descendants(s.uuid)
        val nbEnfants = enfants.size

        val actions = mutableListOf<String>()
        if (s.etat == SaisieEnAttente.Etat.ERROR) actions.add("Réessayer")
        // F : "Envoyer ce groupe" — uniquement si la saisie est encore à envoyer ET a
        // au moins un enfant local. Pour une saisie isolée, l'envoi unitaire ne gagne
        // rien sur "Envoyer tout".
        if (nbEnfants > 0 &&
            (s.etat == SaisieEnAttente.Etat.PENDING || s.etat == SaisieEnAttente.Etat.ERROR)
        ) {
            actions.add("Envoyer ce groupe (${nbEnfants + 1} saisies)")
        }
        if (s.etat != SaisieEnAttente.Etat.SENT) actions.add("Supprimer cette saisie")
        if (s.etat == SaisieEnAttente.Etat.SENT) actions.add("Retirer de la liste")
        if (actions.isEmpty()) return
        AlertDialog.Builder(ctx)
            .setTitle("${s.objectType} · ${SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(s.dateLocale))}")
            .setItems(actions.toTypedArray()) { _, idx ->
                val choix = actions[idx]
                when {
                    choix == "Réessayer" -> {
                        OutboxMonitoring.mettreAJour(s.uuid) {
                            it.copy(etat = SaisieEnAttente.Etat.PENDING, messageErreur = null)
                        }
                        rafraichir()
                    }
                    choix.startsWith("Envoyer ce groupe") -> lancerEnvoiGroupe(s.uuid)
                    choix == "Supprimer cette saisie" -> demanderSuppression(s, nbEnfants)
                    choix == "Retirer de la liste" -> {
                        OutboxMonitoring.supprimer(s.uuid)
                        rafraichir()
                    }
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** D : suppression d'une saisie. Si elle a des enfants locaux (obs rattachées à la
     *  visite via parentUuidLocal), on demande une confirmation explicite parce qu'on va
     *  aussi les perdre — sinon ces obs deviendraient orphelines et inenvoyables. */
    private fun demanderSuppression(s: SaisieEnAttente, nbEnfants: Int) {
        if (nbEnfants == 0) {
            OutboxMonitoring.supprimer(s.uuid)
            rafraichir()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Supprimer cette saisie ?")
            .setMessage(
                "Cette ${s.objectType} a $nbEnfants saisie(s) locale(s) rattachée(s) " +
                    "(par ex. des observations). Sans son parent, elles ne pourront plus être " +
                    "envoyées.\n\nSupprimer la ${s.objectType} ET ses $nbEnfants enfant(s) ?"
            )
            .setPositiveButton("Tout supprimer") { _, _ ->
                val n = OutboxMonitoring.supprimerCascade(s.uuid)
                android.widget.Toast.makeText(
                    requireContext(), "$n saisie(s) supprimée(s)",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                rafraichir()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** F : envoi du sous-arbre — progression + récap final. On ne pousse que la saisie
     *  [uuidRacine] et ses descendants locaux (déclenché par « Envoyer ce groupe »). */
    private fun lancerEnvoiGroupe(uuidRacine: String) {
        binding.progressEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.text = "Préparation du groupe…"
        val config = GeoNatureConfig(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val res = OutboxEnvoi.envoyerGroupe(config, uuidRacine) { envoyees, total, msg ->
                activity?.runOnUiThread {
                    binding.tvMessageEnvoi.text = "Envoi $envoyees/$total · $msg".trim().trimEnd('·', ' ')
                    rafraichir()
                }
            }
            if (!isAdded) return@launch
            binding.progressEnvoi.visibility = View.GONE
            val recap = buildString {
                append("Envoi du groupe terminé · ${res.succes} succès, ${res.echecs} échec(s)")
                if (res.messages.isNotEmpty()) {
                    append("\n\n")
                    append(res.messages.joinToString("\n"))
                }
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Récap")
                .setMessage(recap)
                .setPositiveButton("OK", null)
                .show()
            rafraichir()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
