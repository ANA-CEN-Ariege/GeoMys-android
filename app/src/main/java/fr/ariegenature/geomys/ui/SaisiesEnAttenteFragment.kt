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
    private val adapterAttente = AttenteAdapter()
    private val fmtDateListe = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSaisiesEnAttenteBinding.inflate(inflater, container, false)
        return binding.root
    }

    /** Onglet courant : 0 = À envoyer (groupes avec au moins une donnée non partie),
     *  1 = Envoyées (groupes entièrement transmis). Mêmes onglets que les autres « Mes X ». */
    private var ongletCourant = 0

    /** true pendant un envoi (groupe ou « Tout envoyer ») : masque le bouton d'envoi global et
     *  refuse un second déclenchement. OutboxEnvoi sérialise déjà côté réseau (mutex), ce drapeau
     *  ne fait que garder l'UI cohérente. */
    private var envoiEnCours = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "Mes visites")
        binding.rvSaisies.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.rvSaisies.adapter = adapterAttente
        binding.btnToutEnvoyer.setOnClickListener { confirmerEnvoiTout() }
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("À envoyer"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Envoyées"))
        binding.tabLayout.addOnTabSelectedListener(
            object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                    ongletCourant = tab.position; rafraichir()
                }
                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            })
        rafraichir()
    }

    // ── Items de la liste (RecyclerView) : la hiérarchie protocole → groupe → lignes est
    //    APLATIE en une liste typée ; chaque item est rendu par la même fonction creer* qu'avant
    //    la conversion (le holder est un simple conteneur rebindé — vues légères, le gain du
    //    RecyclerView est la virtualisation : seules les lignes visibles sont construites). ──
    private sealed interface ItemAttente {
        data class Vide(val texte: String) : ItemAttente
        data class HeaderProtocole(val moduleCode: String) : ItemAttente
        data class HeaderGroupe(val racine: SaisieEnAttente) : ItemAttente
        data class Ligne(val saisie: SaisieEnAttente, val profondeur: Int) : ItemAttente
    }

    private inner class AttenteAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<AttenteAdapter.VH>() {
        private var items: List<ItemAttente> = emptyList()

        @Suppress("NotifyDataSetChanged") // liste courte, reconstruite en bloc à chaque refresh
        fun submit(nouveaux: List<ItemAttente>) { items = nouveaux; notifyDataSetChanged() }

        inner class VH(val conteneur: LinearLayout) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(conteneur)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT,
                    androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            })

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.conteneur.removeAllViews()
            when (val item = items[position]) {
                is ItemAttente.Vide -> holder.conteneur.addView(TextView(requireContext()).apply {
                    text = item.texte
                    setTextColor(couleurSecondaire(requireContext()))
                    textSize = 13f
                })
                is ItemAttente.HeaderProtocole ->
                    holder.conteneur.addView(creerHeaderProtocole(item.moduleCode))
                is ItemAttente.HeaderGroupe ->
                    creerHeaderGroupe(item.racine)?.let { holder.conteneur.addView(it) }
                is ItemAttente.Ligne ->
                    holder.conteneur.addView(creerLigne(item.saisie, fmtDateListe, item.profondeur))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rafraichir()
    }

    private fun rafraichir() {
        if (_binding == null) return // appelable depuis le callback de progression (cf. lancerEnvoiGroupe)
        val toutes = OutboxMonitoring.tout()
        // Le RÉSUMÉ compte les objets de niveau VISITE seulement (ceux placés directement sous
        // le protocole : visite, transect, pelouse…) — pas leurs enfants (espèces/observations),
        // qui gonflaient le total sans correspondre à ce que l'utilisateur appelle une visite
        // (demande terrain 2026-09-03). Les enfants restent affichés sous leur visite et partent
        // avec elle.
        val visites = toutes.filter { estObjetDeNiveauVisite(it.parentObjectType) }
        val enAttente = visites.count { it.etat == SaisieEnAttente.Etat.PENDING || it.etat == SaisieEnAttente.Etat.ERROR }
        val envoyees = visites.count { it.etat == SaisieEnAttente.Etat.SENT }
        binding.tvResume.text = when {
            toutes.isEmpty() -> "Aucune donnée locale."
            enAttente == 0 -> "Toutes les données ont été envoyées ($envoyees)."
            else -> "$enAttente en attente · $envoyees envoyées"
        }
        binding.tabLayout.getTabAt(0)?.text = "À envoyer ($enAttente)"
        binding.tabLayout.getTabAt(1)?.text = "Envoyées ($envoyees)"
        peuplerListe(toutes)
        majBoutonToutEnvoyer(toutes)
    }

    /** Bouton « Tout envoyer » : visible seulement dans l'onglet « À envoyer », hors envoi en
     *  cours, et s'il reste au moins une donnée en attente dans un protocole AUTORISÉ (CRUVED C —
     *  même garde que les flèches par groupe : sans droit, le POST partirait en 403). */
    private fun majBoutonToutEnvoyer(toutes: List<SaisieEnAttente>) {
        val memo = mutableMapOf<String, Boolean>()
        val nb = toutes.count { s ->
            (s.etat == SaisieEnAttente.Etat.PENDING || s.etat == SaisieEnAttente.Etat.ERROR) &&
                memo.getOrPut(s.moduleCode) {
                    fr.ariegenature.geomys.network.MonitoringModules.moduleAutoriseCreation(s.moduleCode)
                }
        }
        val visible = ongletCourant == 0 && nb > 0 && !envoiEnCours
        binding.btnToutEnvoyer.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnToutEnvoyer.isEnabled = !envoiEnCours
        binding.btnToutEnvoyer.text = "Tout envoyer ($nb)"
    }

    // ── Terminologie monitoring : chaque item est nommé par le LABEL SERVEUR de son type
    //    (« visite », « passage », « observation »…), accordé en genre, au lieu d'un « saisie »
    //    générique. Les messages portant sur l'ENSEMBLE (liste hétérogène) utilisent le terme
    //    neutre « donnée ». ─────────────────────────────────────────────────────────────────
    private fun labelDuType(s: SaisieEnAttente): String =
        fr.ariegenature.geomys.network.MonitoringObjets.labelTypeEnCache(s.moduleCode, s.objectType)
            ?: s.objectType.replaceFirstChar { it.uppercase() }

    private fun typeMasculin(s: SaisieEnAttente): Boolean =
        fr.ariegenature.geomys.network.MonitoringObjets
            .genreTypeEnCache(s.moduleCode, s.objectType).equals("M", ignoreCase = true)

    private fun voyelleInitiale(mot: String): Boolean = mot.firstOrNull()?.let {
        it in setOf('a', 'à', 'â', 'e', 'é', 'è', 'ê', 'i', 'î', 'o', 'ô', 'u', 'ù', 'û', 'h', 'y')
    } == true

    /** « cette visite » / « ce passage » / « cet inventaire » / « cette observation ». */
    private fun ceTypeDe(s: SaisieEnAttente): String {
        val mot = labelDuType(s).lowercase()
        val dem = when { !typeMasculin(s) -> "cette"; voyelleInitiale(mot) -> "cet"; else -> "ce" }
        return "$dem $mot"
    }

    /** « la visite » / « le passage » / « l'observation » (article défini accordé + élision). */
    private fun leTypeDe(s: SaisieEnAttente): String {
        val mot = labelDuType(s).lowercase()
        val art = when { voyelleInitiale(mot) -> "l'"; typeMasculin(s) -> "le "; else -> "la " }
        return "$art$mot"
    }

    private fun peuplerListe(saisies: List<SaisieEnAttente>) {
        envoiAutoriseParModule.clear() // les droits sont re-lus à chaque peuplement
        if (saisies.isEmpty()) {
            adapterAttente.submit(listOf(ItemAttente.Vide(
                "Les données que vous enregistrez apparaîtront ici jusqu'à leur envoi.")))
            return
        }
        val items = mutableListOf<ItemAttente>()

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

        // Onglets « À envoyer / Envoyées » : un GROUPE (racine + descendants) ne passe dans
        // « Envoyées » que lorsque TOUT son sous-arbre est SENT — tant qu'une obs reste à
        // envoyer, le groupe entier reste visible dans « À envoyer » (hiérarchie conservée).
        fun groupeToutEnvoye(racine: SaisieEnAttente): Boolean {
            if (racine.etat != SaisieEnAttente.Etat.SENT) return false
            val file = ArrayDeque<String>().apply { add(racine.uuid) }
            while (file.isNotEmpty()) {
                val courant = file.removeFirst()
                parParent[courant].orEmpty().forEach {
                    if (it.etat != SaisieEnAttente.Etat.SENT) return false
                    file.add(it.uuid)
                }
            }
            return true
        }
        val racinesOnglet = racines.filter { groupeToutEnvoye(it) == (ongletCourant == 1) }
        if (racinesOnglet.isEmpty()) {
            adapterAttente.submit(listOf(ItemAttente.Vide(
                if (ongletCourant == 1) "Aucune donnée envoyée."
                else "Aucune donnée en attente d'envoi.")))
            return
        }

        // Regroupement hiérarchique : Protocole → Site (header de groupe) → Visite/Obs.
        // On préserve l'ordre des racines (déjà trié par état/date) au sein de chaque
        // module, et on ordonne les modules par leur ordre d'apparition de la 1re racine.
        val racinesParModule = linkedMapOf<String, MutableList<SaisieEnAttente>>()
        racinesOnglet.forEach { r ->
            racinesParModule.getOrPut(r.moduleCode) { mutableListOf() }.add(r)
        }

        racinesParModule.forEach { (moduleCode, racinesModule) ->
            items.add(ItemAttente.HeaderProtocole(moduleCode))
            racinesModule.forEach { racine ->
                // Header de groupe : on remonte la chaîne des parents serveur du parent direct
                // de la racine pour situer le groupe (par ex. "Forêt de Foix › Point Foix-Nord"
                // pour une visite faite sur un point d'écoute). Si le parent serveur n'a jamais
                // été ouvert dans l'app (cache vide), on omet le header — le titre de la racine
                // gardera son fallback "type #id".
                if (racine.parentObjectType?.isNotEmpty() == true && racine.parentIdServeur != null) {
                    items.add(ItemAttente.HeaderGroupe(racine))
                }

                fun ajouterArbre(s: SaisieEnAttente, profondeur: Int) {
                    items.add(ItemAttente.Ligne(s, profondeur))
                    parParent[s.uuid].orEmpty()
                        .sortedWith(compareBy({ ordrePourTri(it.etat) }, { it.dateLocale }))
                        .forEach { ajouterArbre(it, profondeur + 1) }
                }
                ajouterArbre(racine, 0)
            }
        }
        adapterAttente.submit(items)
    }

    /** Header de section "Protocole : <nom>" qui regroupe toutes les saisies d'un même
     *  moduleCode. Permet de scanner rapidement la liste quand plusieurs protocoles ont
     *  des saisies en attente en parallèle. Style bleu foncé en majuscules pour bien le
     *  distinguer du header de groupe (📍 Site, plus discret). */
    private fun creerHeaderProtocole(moduleCode: String): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val label = fr.ariegenature.geomys.network.MonitoringModules.labelModuleEnCache(moduleCode)
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
        val labelDirect = fr.ariegenature.geomys.network.MonitoringObjets
            .labelObjetEnCache(racine.moduleCode, parentType, parentId)
            ?: "$parentType #$parentId"
        val ancetres = fr.ariegenature.geomys.network.MonitoringObjets.chaineParentsEnCache(
            racine.moduleCode, parentType, parentId,
        )
        // Chaîne du plus haut au plus bas (parent direct en dernier). Chaque segment est
        // préfixé par le label humain de son type (ex. "Site : Forêt de Foix"). Si le
        // schéma cache n'a pas de label, fallback sur le type technique capitalisé.
        val chemin = (ancetres.reversed().map { it.first to it.third } + (parentType to labelDirect))
            .joinToString(" › ") { (type, label) ->
                val labelType = fr.ariegenature.geomys.network.MonitoringObjets
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

    /** CRUVED C du protocole (cache modules) : false → flèches « Envoyer » masquées, le POST
     *  serait refusé (403). Cohérent avec le gating des listes Occtax/OccHab. Mémoïsé par
     *  module le temps d'un peuplement (le cache disque est re-parsé sinon à chaque ligne). */
    private val envoiAutoriseParModule = mutableMapOf<String, Boolean>()
    private fun envoiAutorise(s: SaisieEnAttente): Boolean =
        envoiAutoriseParModule.getOrPut(s.moduleCode) {
            fr.ariegenature.geomys.network.MonitoringModules.moduleAutoriseCreation(s.moduleCode)
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
                SaisieEnAttente.Etat.SENT -> background = cadreColore(couleurSucces(), density)
                SaisieEnAttente.Etat.SENDING -> background = cadreColore(couleurEnCours(), density)
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
        val labelType = fr.ariegenature.geomys.network.MonitoringObjets
            .labelTypeEnCache(s.moduleCode, s.objectType)
            ?: s.objectType.replaceFirstChar { it.uppercase() }
        // Si la saisie porte un cd_nom (typiquement une observation), on remplace le label
        // type par le nom du taxon — plus parlant que "Observation" générique. Le type
        // reste accessible visuellement via l'indentation et le contexte du groupe.
        val titrePrincipal = nomTaxonDeSaisie(s) ?: labelType
        header.addView(TextView(ctx).apply {
            // Ligne STRICTEMENT IDENTIQUE pour toutes les saisies, incomplètes comprises :
            // c'est la FLÈCHE D'ENVOI qui passe au rouge quand il reste des champs
            // obligatoires (demande terrain 2026-09-03) — ni pastille, ni cadre, ni bloc de
            // texte. Le détail des champs manquants est donné à la tentative d'envoi.
            text = "$flecheEnfant$icone $titrePrincipal$parentInfo"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        if (s.etat == SaisieEnAttente.Etat.PENDING || s.etat == SaisieEnAttente.Etat.ERROR) {
            ajouterIconesActions(header, s, profondeur)
        } else if (profondeur == 0 && s.etat == SaisieEnAttente.Etat.SENT &&
            aDescendantsAEnvoyer(s) && envoiAutorise(s)
        ) {
            // Visite déjà envoyée mais obs restantes : la ligne reste affichée comme groupe
            // (cf. purgerSent) et garde UNIQUEMENT la flèche — qui n'enverra que le reste
            // (l'objet créé n'est jamais re-POSTé, cf. SaisieEnAttente.objetCree).
            header.addView(creerIconeAction(
                fr.ariegenature.geomys.R.drawable.ic_send,
                "Envoyer les données restantes",
                tint = couleurEnvoi(),
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
        if (profondeur == 0 && envoiAutorise(s)) {
            // Flèche ROUGE = saisie incomplète : la toucher ouvre son formulaire pour finir
            // les champs obligatoires au lieu de lancer l'envoi (cf. lancerEnvoiGroupe).
            parent.addView(creerIconeAction(
                fr.ariegenature.geomys.R.drawable.ic_send,
                if (s.aCompleter) "Compléter avant d'envoyer" else "Envoyer ce groupe",
                tint = if (s.aCompleter) ROUGE_A_COMPLETER else couleurEnvoi(),
            ) { lancerEnvoiGroupe(s.uuid) })
        }
        parent.addView(creerIconeAction(
            fr.ariegenature.geomys.R.drawable.ic_edit,
            "Éditer ${ceTypeDe(s)}",
            tint = couleurEdition(),
        ) { ouvrirEdition(s) })
        parent.addView(creerIconeAction(
            fr.ariegenature.geomys.R.drawable.ic_delete,
            "Supprimer ${ceTypeDe(s)}",
            // MÊME rouge que la poubelle de « Mes saisies » et « Mes stations »
            // (item_sortie.xml / item_occhab_station.xml) : la couleur d'erreur du thème
            // Material tire sur le rose pâle en thème sombre.
            tint = androidx.core.content.ContextCompat.getColor(
                requireContext(), android.R.color.holo_red_light,
            ),
        ) {
            val nbEnfants = OutboxMonitoring.descendants(s.uuid).size
            demanderSuppression(s, nbEnfants)
        })
    }

    /** ImageButton compact avec fond borderless (ripple) pour ne pas alourdir la ligne. [tint]
     *  est la couleur d'icône à appliquer : vert d'envoi ([couleurEnvoi]) pour la flèche ➤,
     *  jaune clair pour Éditer, rouge d'erreur pour Supprimer. */
    private fun creerIconeAction(
        drawableId: Int,
        description: String,
        tint: Int,
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
            // Zone cliquable 48dp (minimum tactile Material — gants/froid) ; le padding garde
            // l'icône à 24dp visuels.
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(), (48 * density).toInt(),
            )
            setColorFilter(tint)
            setOnClickListener { action() }
        }
    }

    /** Vert d'envoi (@color/colorSecondary) — IDENTIQUE à la flèche ➤ « Envoyer » de « Mes
     *  saisies » et « Mes stations » (app:tint colorSecondary sur ic_send). */
    private fun couleurEnvoi(): Int = androidx.core.content.ContextCompat.getColor(
        requireContext(), fr.ariegenature.geomys.R.color.colorSecondary,
    )

    /** Jaune clair des icônes cliquables (Éditer). */
    private fun couleurEdition(): Int = androidx.core.content.ContextCompat.getColor(
        requireContext(), fr.ariegenature.geomys.R.color.jaune_clair,
    )

    /** Navigation vers [NouvelleVisiteFragment] en mode édition pour la saisie [s]. Le
     *  fragment va récupérer les autres meta (parent serveur, type, etc.) directement
     *  depuis [OutboxMonitoring] via l'editUuid — pas besoin de tout passer ici. */
    private fun ouvrirEdition(
        s: SaisieEnAttente,
        messageEphemere: String? = null,
        /** true = on vient COMPLÉTER des champs obligatoires manquants : à l'enregistrement,
         *  le formulaire n'enchaîne PAS sur la saisie des espèces (rien à ajouter, on ne
         *  faisait que finir les infos de la visite) et rend la main à cet écran. */
        modeCompletion: Boolean = false,
    ) {
        findNavController().naviguerSur(
            fr.ariegenature.geomys.R.id.action_attente_to_edition,
            androidx.core.os.bundleOf(
                "editUuid" to s.uuid,
                // Affiché en toast PAR LE FORMULAIRE, une fois celui-ci rendu (le montrer
                // ici le ferait apparaître avant l'écran, voire s'éteindre avant lui).
                "messageEphemere" to messageEphemere,
                "modeCompletion" to modeCompletion,
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
        val moduleLabel = fr.ariegenature.geomys.network.MonitoringModules
            .labelModuleEnCache(s.moduleCode) ?: s.moduleCode
        // Racine "Suivis › <protocole>".
        segments.addAll(filRacineSuivis(moduleLabel))
        val parentType = s.parentObjectType?.takeIf { it.isNotEmpty() }
        val parentId = s.parentIdServeur
        if (parentType != null && parentId != null) {
            val labelDirect = fr.ariegenature.geomys.network.MonitoringObjets
                .labelObjetEnCache(s.moduleCode, parentType, parentId) ?: "$parentType #$parentId"
            val ancetres = fr.ariegenature.geomys.network.MonitoringObjets
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
        if (nbEnfants > 0 && envoiAutorise(s) &&
            (s.etat == SaisieEnAttente.Etat.PENDING || s.etat == SaisieEnAttente.Etat.ERROR)
        ) {
            actions.add("Envoyer ce groupe (${nbEnfants + 1} données)")
        }
        if (s.etat != SaisieEnAttente.Etat.SENT) actions.add("Supprimer ${ceTypeDe(s)}")
        if (s.etat == SaisieEnAttente.Etat.SENT) actions.add("Retirer de la liste")
        if (actions.isEmpty()) return
        AlertDialog.Builder(ctx)
            .setTitle("${labelDuType(s)} · ${SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(s.dateLocale))}")
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
                    choix.startsWith("Supprimer") -> demanderSuppression(s, nbEnfants)
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
            .setTitle("Supprimer ${ceTypeDe(s)} ?")
            .setMessage(
                "${ceTypeDe(s).replaceFirstChar { it.uppercase() }} a $nbEnfants donnée(s) locale(s) " +
                    "rattachée(s) (par ex. des observations). Sans son parent, elles ne pourront " +
                    "plus être envoyées.\n\nSupprimer ${leTypeDe(s)} ET ses $nbEnfants enfant(s) ?"
            )
            .setPositiveButton("Tout supprimer") { _, _ ->
                val n = OutboxMonitoring.supprimerCascade(s.uuid)
                android.widget.Toast.makeText(
                    requireContext(), "$n donnée(s) supprimée(s)",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                rafraichir()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** « Tout envoyer » : confirme, puis pousse TOUTE la file en attente d'un coup, dans l'ordre
     *  parent → enfant ([OutboxEnvoi.envoyerTout]). Anti-doublon assuré par objetCree. */
    private fun confirmerEnvoiTout() {
        if (envoiEnCours) {
            android.widget.Toast.makeText(requireContext(), "Un envoi est déjà en cours…",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val config = GeoNatureConfig(requireContext())
        if (!config.estConfiguree) {
            AlertDialog.Builder(requireContext())
                .setTitle("Configuration requise")
                .setMessage("La connexion GeoNature n'est pas configurée. Ouvrez la configuration (⚙️) avant d'envoyer.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val nb = OutboxMonitoring.tout().count {
            it.etat == SaisieEnAttente.Etat.PENDING || it.etat == SaisieEnAttente.Etat.ERROR
        }
        if (nb == 0) return
        AlertDialog.Builder(requireContext())
            .setTitle("Tout envoyer")
            .setMessage("Envoyer les $nb donnée(s) en attente vers GeoNature ?")
            .setPositiveButton("Envoyer") { _, _ -> lancerEnvoiTout(config) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun lancerEnvoiTout(config: GeoNatureConfig) {
        envoiEnCours = true
        binding.btnToutEnvoyer.visibility = View.GONE
        binding.progressEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.text = "Préparation de l'envoi…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = OutboxEnvoi.envoyerTout(config) { envoyees, total, msg ->
                    activity?.runOnUiThread {
                        // Callback venant du bloc Dispatchers.IO : peut arriver après onDestroyView.
                        val b = _binding ?: return@runOnUiThread
                        b.tvMessageEnvoi.text = "Envoi $envoyees/$total · $msg".trim().trimEnd('·', ' ')
                        rafraichir()
                    }
                }
                if (!isAdded || _binding == null) return@launch
                val recap = buildString {
                    append("Envoi terminé · ${res.succes} succès, ${res.echecs} échec(s)")
                    if (res.messages.isNotEmpty()) { append("\n\n"); append(res.messages.joinToString("\n")) }
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Envoi")
                    .setMessage(recap)
                    .setPositiveButton("OK", null)
                    .show()
            } finally {
                envoiEnCours = false
                _binding?.let {
                    it.progressEnvoi.visibility = View.GONE
                    it.tvMessageEnvoi.visibility = View.GONE
                }
                rafraichir()
            }
        }
    }

    /** F : envoi du sous-arbre — progression + récap final. On ne pousse que la saisie
     *  [uuidRacine] et ses descendants locaux (déclenché par « Envoyer ce groupe »). */
    private fun lancerEnvoiGroupe(uuidRacine: String) {
        // TENTATIVE D'ENVOI d'un groupe dont la RACINE est « à compléter » : on ouvre
        // DIRECTEMENT son formulaire, avec un message éphémère — pas de dialogue
        // intermédiaire (demande terrain 2026-09-03). Les autres cas (un descendant à
        // compléter, « Tout envoyer ») passent par le récapitulatif d'envoi, qui nomme les
        // saisies concernées.
        val racine = OutboxMonitoring.tout().firstOrNull { it.uuid == uuidRacine }
        if (racine != null && racine.aCompleter) {
            ouvrirEdition(racine, MESSAGE_CHAMPS_OBLIGATOIRES, modeCompletion = true)
            return
        }
        envoiEnCours = true
        binding.btnToutEnvoyer.visibility = View.GONE
        binding.progressEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.text = "Préparation du groupe…"
        val config = GeoNatureConfig(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = OutboxEnvoi.envoyerGroupe(config, uuidRacine) { envoyees, total, msg ->
                    activity?.runOnUiThread {
                        // Le callback vient du bloc Dispatchers.IO d'OutboxEnvoi : il peut être
                        // posté juste avant l'annulation et s'exécuter APRÈS onDestroyView
                        // (_binding = null) si l'utilisateur quitte l'écran pendant l'envoi.
                        val b = _binding ?: return@runOnUiThread
                        b.tvMessageEnvoi.text = "Envoi $envoyees/$total · $msg".trim().trimEnd('·', ' ')
                        rafraichir()
                    }
                }
                if (!isAdded || _binding == null) return@launch
                binding.progressEnvoi.visibility = View.GONE
                val recap = buildString {
                    append("Envoi du groupe terminé · ${res.succes} succès, ${res.echecs} échec(s)")
                    if (res.messages.isNotEmpty()) {
                        append("\n\n")
                        append(res.messages.joinToString("\n"))
                    }
                }
                AlertDialog.Builder(requireContext())
                    // « Envoi » : même titre de récap que Mes saisies / Mes stations.
                    .setTitle("Envoi")
                    .setMessage(recap)
                    .setPositiveButton("OK", null)
                    .show()
                rafraichir()
            } finally {
                // finally (comme lancerEnvoiTout) : une navigation pendant l'envoi annule la
                // coroutine — sans lui, envoiEnCours restait bloqué à true et l'écran refusait
                // tout envoi (« déjà en cours ») jusqu'à destruction du fragment (audit 2026-08-23).
                envoiEnCours = false
                _binding?.let {
                    it.progressEnvoi.visibility = View.GONE
                    it.tvMessageEnvoi.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Rouge de la flèche d'envoi d'une saisie « à compléter » : même rouge franc que les barres
 *  des champs obligatoires du formulaire et que les repères de la carte. */
private const val ROUGE_A_COMPLETER = 0xFFD32F2F.toInt()
