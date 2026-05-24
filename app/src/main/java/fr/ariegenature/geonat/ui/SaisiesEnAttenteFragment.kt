package fr.ariegenature.geonat.ui

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
import fr.ariegenature.geonat.databinding.FragmentSaisiesEnAttenteBinding
import fr.ariegenature.geonat.network.OutboxEnvoi
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.OutboxMonitoring
import fr.ariegenature.geonat.store.SaisieEnAttente
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Liste les saisies monitoring (visites + obs) en attente d'envoi vers le serveur.
 *  Envoi exclusivement à la demande via le FAB "Envoyer tout" — aucun envoi auto. */
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
        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }
        binding.fabEnvoyer.setOnClickListener { lancerEnvoi() }
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
        binding.fabEnvoyer.isEnabled = enAttente > 0
        peuplerListe(toutes)
    }

    private fun peuplerListe(saisies: List<SaisieEnAttente>) {
        binding.llSaisies.removeAllViews()
        if (saisies.isEmpty()) {
            binding.llSaisies.addView(TextView(requireContext()).apply {
                text = "Les saisies que tu enregistres apparaîtront ici jusqu'à leur envoi."
                setTextColor(0xFF888888.toInt())
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

        racines.forEach { racine ->
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

    /** Construit le bandeau de contexte d'un groupe : icône 📍 + chemin du parent serveur
     *  le plus haut vers le parent direct. Retourne null si la racine n'a pas de parent
     *  serveur identifiable (cas d'une saisie isolée hors arborescence).
     *
     *  Le chemin part de l'ancêtre le plus lointain à gauche pour qu'on lise toujours du
     *  contexte le plus large au plus précis (cohérent avec un fil d'Ariane classique). */
    private fun creerHeaderGroupe(racine: SaisieEnAttente): View? {
        val parentType = racine.parentObjectType?.takeIf { it.isNotEmpty() } ?: return null
        val parentId = racine.parentIdServeur ?: return null
        val labelDirect = fr.ariegenature.geonat.network.MonitoringApi
            .labelObjetEnCache(racine.moduleCode, parentType, parentId)
            ?: "$parentType #$parentId"
        val ancetres = fr.ariegenature.geonat.network.MonitoringApi.chaineParentsEnCache(
            racine.moduleCode, parentType, parentId,
        )
        // Chaîne du plus haut au plus bas (parent direct en dernier). Chaque segment est
        // préfixé par le label humain de son type (ex. "Site : Forêt de Foix"). Si le
        // schéma cache n'a pas de label, fallback sur le type technique capitalisé.
        val chemin = (ancetres.reversed() + (parentType to labelDirect))
            .joinToString(" › ") { (type, label) ->
                val labelType = fr.ariegenature.geonat.network.MonitoringApi
                    .labelTypeEnCache(racine.moduleCode, type)
                    ?: type.replaceFirstChar { it.uppercase() }
                "$labelType : $label"
            }
        val density = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            text = "📍 $chemin"
            textSize = 12f
            setTextColor(0xFF424242.toInt())
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
        val entry = fr.ariegenature.geonat.store.TaxRefCache.entreesParCdNom()[cdNom] ?: return null
        return entry.nomFrOriginal?.takeIf { it.isNotEmpty() } ?: entry.sciNom
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
            setBackgroundColor(when (s.etat) {
                SaisieEnAttente.Etat.SENT -> 0xFFE8F5E9.toInt()      // vert très clair
                SaisieEnAttente.Etat.ERROR -> 0xFFFFEBEE.toInt()     // rouge très clair
                SaisieEnAttente.Etat.SENDING -> 0xFFFFF8E1.toInt()   // ambre très clair
                else -> 0x00000000
            })
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
        val labelType = fr.ariegenature.geonat.network.MonitoringApi
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
        }
        row.addView(header)
        row.addView(TextView(ctx).apply {
            text = "${s.moduleCode} · ${fmtDate.format(Date(s.dateLocale))}" +
                (s.idServeur?.let { " · id serveur=$it" } ?: "")
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        })
        s.messageErreur?.takeIf { it.isNotBlank() }?.let { err ->
            row.addView(TextView(ctx).apply {
                text = err
                textSize = 12f
                setTextColor(0xFFC62828.toInt())
            })
        }
        // Tap court = options (réessayer en cas d'erreur, retirer pour SENT). Les
        // ImageButton du header consomment leur propre clic.
        row.setOnClickListener { afficherOptions(s) }
        return row
    }

    /** Ajoute les icônes d'action à droite du titre d'une saisie. Sur une racine
     *  (profondeur 0) : Envoyer le groupe + Éditer + Supprimer. Sur un enfant
     *  (profondeur > 0) : Éditer + Supprimer (l'envoi reste géré par "Envoyer le groupe"
     *  de la racine ou par "Envoyer tout" — un enfant ne peut pas partir sans son parent). */
    private fun ajouterIconesActions(parent: LinearLayout, s: SaisieEnAttente, profondeur: Int) {
        if (profondeur == 0) {
            parent.addView(creerIconeAction(
                fr.ariegenature.geonat.R.drawable.ic_send,
                "Envoyer ce groupe",
                tintBleu = true,
            ) { lancerEnvoiGroupe(s.uuid) })
        }
        parent.addView(creerIconeAction(
            fr.ariegenature.geonat.R.drawable.ic_edit,
            "Éditer cette saisie",
            tintBleu = true,
        ) { ouvrirEdition(s) })
        parent.addView(creerIconeAction(
            fr.ariegenature.geonat.R.drawable.ic_delete,
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
            val couleur = if (tintBleu) 0xFF1976D2.toInt() else 0xFFC62828.toInt()
            setColorFilter(couleur)
            setOnClickListener { action() }
        }
    }

    /** Navigation vers [NouvelleVisiteFragment] en mode édition pour la saisie [s]. Le
     *  fragment va récupérer les autres meta (parent serveur, type, etc.) directement
     *  depuis [OutboxMonitoring] via l'editUuid — pas besoin de tout passer ici. */
    private fun ouvrirEdition(s: SaisieEnAttente) {
        findNavController().navigate(
            fr.ariegenature.geonat.R.id.action_attente_to_edition,
            androidx.core.os.bundleOf("editUuid" to s.uuid),
        )
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

    /** F : envoi du sous-arbre. Même UX que [lancerEnvoi] (progression + récap final),
     *  mais on ne pousse que la saisie [uuidRacine] et ses descendants locaux. */
    private fun lancerEnvoiGroupe(uuidRacine: String) {
        binding.fabEnvoyer.isEnabled = false
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
            binding.fabEnvoyer.isEnabled = OutboxMonitoring.countEnAttente() > 0
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

    private fun lancerEnvoi() {
        binding.fabEnvoyer.isEnabled = false
        binding.progressEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.text = "Préparation…"
        val config = GeoNatureConfig(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val res = OutboxEnvoi.envoyerTout(config) { envoyees, total, msg ->
                activity?.runOnUiThread {
                    binding.tvMessageEnvoi.text = "Envoi $envoyees/$total · $msg".trim().trimEnd('·', ' ')
                    rafraichir()
                }
            }
            if (!isAdded) return@launch
            binding.progressEnvoi.visibility = View.GONE
            binding.fabEnvoyer.isEnabled = true
            val recap = buildString {
                append("Envoi terminé · ${res.succes} succès, ${res.echecs} échec(s)")
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
