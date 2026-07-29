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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentSortiesBinding
import fr.ariegenature.geomys.databinding.ItemSortieBinding
import fr.ariegenature.geomys.gpx.importerGPX
import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.network.envoyerSortieVersGeoNature
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.SortieStore
import kotlinx.coroutines.launch
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.*

class SortiesFragment : Fragment() {
    private var _binding: FragmentSortiesBinding? = null
    private val binding get() = _binding!!
    private lateinit var sortieStore: SortieStore
    private lateinit var adapter: SortieAdapter
    private var ongletCourant = 0
    private val traceViewModel: TraceViewModel by activityViewModels()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val bytes = requireContext().contentResolver.openInputStream(uri)?.readBytes() ?: return@let
                    var sortie = importerGPX(bytes)
                    if (sortie != null) {
                        sortie = sortie.copy(estImportee = true)
                        sortieStore.ajouter(sortie)
                        ongletCourant = 2
                        binding.tabLayout.getTabAt(2)?.select()
                        refreshList()
                    } else {
                        showError(getString(R.string.erreur_import_gpx))
                    }
                } catch (e: Exception) {
                    showError(e.message ?: getString(R.string.erreur_import_gpx))
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSortiesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), getString(R.string.mes_sorties))
        sortieStore = SortieStore(requireContext())

        adapter = SortieAdapter(
            onClick = { sortie ->
                val bundle = Bundle().apply { putString("sortieId", sortie.id) }
                findNavController().naviguerSur(R.id.action_sorties_to_detail, bundle)
            },
            onDelete = { sortie ->
                confirmerSuppression(sortie)
            },
            onEdit = { sortie ->
                // Force le rechargement + recadrage sur la sortie, même si elle a déjà été
                // éditée dans cette session (sinon la carte se centrerait sur le GPS).
                traceViewModel.forcerRepriseAuProchainEcran()
                val bundle = Bundle().apply { putString("sortieId", sortie.id) }
                findNavController().naviguerSur(R.id.action_sorties_to_trace, bundle)
            },
            onEnvoyer = { sortie -> envoyerSortie(sortie) },
            onListe = { sortie -> montrerListeEspeces(sortie) },
            // CRUVED C du module OCCTAX (détecté à la synchro, comme OccHab) : sans droit de
            // création, le POST partirait en 403 — on masque le bouton d'envoi.
            envoiAutorise = fr.ariegenature.geomys.store.GeoNatureConfig(requireContext()).occtaxPeutCreer,
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnImporterGpx.setOnClickListener { lancerImport() }
        binding.btnToutEnvoyer.setOnClickListener { confirmerEnvoiTout() }

        setupTabs()
        refreshList()
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("À envoyer"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Envoyées"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Importées"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                ongletCourant = tab.position
                refreshList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val toutes = sortieStore.charger()
        val filtrees = when (ongletCourant) {
            1 -> toutes.filter { it.envoyeGeoNature }
            2 -> toutes.filter { it.estImportee }
            else -> toutes.filter { !it.envoyeGeoNature && !it.estImportee }
        }
        updateTabCounts(toutes)
        adapter.submitList(filtrees)
        val emptyMsg = when (ongletCourant) {
            1 -> "Aucune saisie envoyée à GeoNature"
            2 -> "Aucune saisie importée"
            else -> "Aucune saisie en attente d'envoi"
        }
        binding.emptyView.visibility = if (filtrees.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (filtrees.isEmpty()) View.GONE else View.VISIBLE
        binding.tvEmpty.text = emptyMsg
        majBoutonToutEnvoyer(toutes)
    }

    /** Sorties réellement envoyables : à envoyer (ni envoyée ni importée) ET au moins une obs
     *  déterminée (cd_nom résolu) OU un relevé sans espèce — mêmes conditions que le bouton
     *  d'envoi d'une ligne. */
    private fun sortiesEnvoyables(toutes: List<Sortie>) = toutes.filter {
        !it.envoyeGeoNature && !it.estImportee &&
            it.observations.any { o -> o.cdNom != null || o.releveSansEspece }
    }

    /** Bouton « Tout envoyer » : visible seulement dans l'onglet « À envoyer », hors envoi en
     *  cours, avec droit de création OCCTAX (CRUVED C) et au moins une sortie envoyable. */
    private fun majBoutonToutEnvoyer(toutes: List<Sortie>) {
        val autorise = GeoNatureConfig(requireContext()).occtaxPeutCreer
        val nb = sortiesEnvoyables(toutes).size
        val visible = ongletCourant == 0 && autorise && nb > 0 && !envoiEnCours
        binding.btnToutEnvoyer.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnToutEnvoyer.isEnabled = !envoiEnCours
        binding.btnToutEnvoyer.text = "Tout envoyer ($nb)"
    }

    private fun updateTabCounts(toutes: List<Sortie>) {
        val aEnvoyer = toutes.count { !it.envoyeGeoNature && !it.estImportee }
        val envoyees = toutes.count { it.envoyeGeoNature }
        val importees = toutes.count { it.estImportee }
        binding.tabLayout.getTabAt(0)?.text = "À envoyer ($aEnvoyer)"
        binding.tabLayout.getTabAt(1)?.text = "Envoyées ($envoyees)"
        binding.tabLayout.getTabAt(2)?.text = "Importées ($importees)"
    }

    private fun confirmerSuppression(sortie: Sortie) {
        if (envoiEnCours) {
            android.widget.Toast.makeText(requireContext(), "Un envoi est en cours — réessayez ensuite.",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(sortie.date))
        val nbObs = sortie.observations.size
        val descr = when {
            nbObs == 0 -> "saisie du $date"
            nbObs == 1 -> "saisie du $date (1 observation)"
            else       -> "saisie du $date ($nbObs observations)"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Supprimer la saisie ?")
            .setMessage("Supprimer la $descr ? Cette action est définitive.")
            .setPositiveButton("Supprimer") { _, _ ->
                sortieStore.supprimer(sortie.id)
                refreshList()
            }
            .setNegativeButton(R.string.annuler, null)
            .show()
    }

    /** true pendant un envoi : les actions concurrentes (2ᵉ envoi, suppression) sont refusées
     *  avec un toast — la liste reste consultable, contrairement à l'ancien modal bloquant. */
    private var envoiEnCours = false

    /** Envoie une seule sortie vers GeoNature (bouton "Envoyer" de la ligne). Progression
     *  INLINE (même patron que « Mes visites ») — l'utilisateur peut continuer à consulter la
     *  liste pendant l'envoi. Marque la sortie envoyée en cas de succès et rafraîchit. */
    private fun envoyerSortie(sortie: Sortie) {
        if (envoiEnCours) {
            android.widget.Toast.makeText(requireContext(), "Un envoi est déjà en cours…",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val gnConfig = GeoNatureConfig(requireContext())
        if (!gnConfig.connexionConfiguree) {
            AlertDialog.Builder(requireContext())
                .setTitle("Configuration requise")
                .setMessage("La connexion GeoNature n'est pas configurée. Ouvrez la configuration (⚙️) avant d'envoyer.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        envoiEnCours = true
        binding.progressEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.text = "Envoi de la saisie vers GeoNature…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Envoi + mise à jour du store (succès/erreur persistée) factorisés —
                // cf. envoyerSortieVersGeoNature, partagé par les 4 écrans d'envoi.
                val res = envoyerSortieVersGeoNature(sortie, sortieStore, gnConfig)
                if (!isAdded || _binding == null) return@launch
                refreshList()
                AlertDialog.Builder(requireContext())
                    // « Envoi » : même titre de récap que Mes visites / Mes stations.
                    .setTitle(if (res.succes) "Envoi" else "Erreur d'envoi")
                    .setMessage(res.message)
                    .setPositiveButton("OK", null).show()
            } finally {
                envoiEnCours = false
                _binding?.let {
                    it.progressEnvoi.visibility = View.GONE
                    it.tvMessageEnvoi.visibility = View.GONE
                }
            }
        }
    }

    /** « Tout envoyer » : confirme, puis pousse séquentiellement toutes les sorties envoyables
     *  (chacune via [envoyerSortieVersGeoNature], anti-doublon par observation déjà partie).
     *  Récapitulatif agrégé à la fin ; la liste se met à jour au fil des envois. */
    private fun confirmerEnvoiTout() {
        if (envoiEnCours) {
            android.widget.Toast.makeText(requireContext(), "Un envoi est déjà en cours…",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val gnConfig = GeoNatureConfig(requireContext())
        if (!gnConfig.connexionConfiguree) {
            AlertDialog.Builder(requireContext())
                .setTitle("Configuration requise")
                .setMessage("La connexion GeoNature n'est pas configurée. Ouvrez la configuration (⚙️) avant d'envoyer.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val aEnvoyer = sortiesEnvoyables(sortieStore.charger())
        if (aEnvoyer.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("Tout envoyer")
            .setMessage("Envoyer les ${aEnvoyer.size} saisie(s) en attente vers GeoNature ?")
            .setPositiveButton("Envoyer") { _, _ -> lancerEnvoiTout(aEnvoyer, gnConfig) }
            .setNegativeButton(R.string.annuler, null)
            .show()
    }

    private fun lancerEnvoiTout(sorties: List<Sortie>, gnConfig: GeoNatureConfig) {
        envoiEnCours = true
        binding.btnToutEnvoyer.visibility = View.GONE
        binding.progressEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            var succes = 0
            var echecs = 0
            val messages = mutableListOf<String>()
            try {
                sorties.forEachIndexed { i, sortie ->
                    _binding?.tvMessageEnvoi?.text = "Envoi ${i + 1}/${sorties.size} vers GeoNature…"
                    val res = envoyerSortieVersGeoNature(sortie, sortieStore, gnConfig)
                    if (res.succes) succes++ else { echecs++; messages.add(res.message) }
                    if (isAdded && _binding != null) refreshList()
                }
                if (!isAdded || _binding == null) return@launch
                val recap = buildString {
                    append("$succes saisie(s) envoyée(s)")
                    if (echecs > 0) append(", $echecs échec(s)")
                    if (messages.isNotEmpty()) { append("\n\n"); append(messages.joinToString("\n")) }
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(if (echecs == 0) "Envoi" else "Erreur d'envoi")
                    .setMessage(recap)
                    .setPositiveButton("OK", null).show()
            } finally {
                envoiEnCours = false
                _binding?.let {
                    it.progressEnvoi.visibility = View.GONE
                    it.tvMessageEnvoi.visibility = View.GONE
                }
                if (isAdded && _binding != null) refreshList()
            }
        }
    }

    /** Récapitulatif des espèces d'une saisie (bouton "liste" de la ligne), au même format que
     *  l'écran de détail : une espèce par ligne, avec le nombre total d'individus. */
    private fun montrerListeEspeces(sortie: Sortie) {
        val agregees = sortie.observations
            .filterNot { it.releveSansEspece }   // le placeholder « relevé sans espèce » n'est pas une espèce
            .groupBy { it.espece }
            .map { (espece, obs) -> espece to obs.sumOf { it.nombre } }
            .sortedBy { it.first }

        val lignes = agregees.map { (espece, nombre) -> "$espece — $nombre ind." }
        val titre = "${agregees.size} espèce${if (agregees.size > 1) "s" else ""}"
        AlertDialog.Builder(requireContext())
            .setTitle(titre)
            .setItems(lignes.toTypedArray(), null)
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun lancerImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/xml", "text/xml", "text/plain"))
        }
        importLauncher.launch(intent)
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.erreur_import)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class SortieAdapter(
    private val onClick: (Sortie) -> Unit,
    private val onDelete: (Sortie) -> Unit,
    private val onEdit: (Sortie) -> Unit = {},
    private val onEnvoyer: (Sortie) -> Unit = {},
    private val onListe: (Sortie) -> Unit = {},
    /** CRUVED C du module OCCTAX : false → bouton d'envoi masqué (le POST serait refusé). */
    private val envoiAutorise: Boolean = true,
) : RecyclerView.Adapter<SortieAdapter.ViewHolder>() {
    private var items: List<Sortie> = emptyList()
    private val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

    fun submitList(list: List<Sortie>) {
        items = list
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemSortieBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSortieBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sortie = items[position]
        with(holder.binding) {
            tvDate.text = fmt.format(Date(sortie.date))
            tvDistance.text = "%.0f m".format(sortie.distanceTotale)
            // On ne compte que les vraies observations (le placeholder « relevé sans espèce » n'en
            // est pas une) ; un relevé sans espèce affiche donc « 0 obs. ».
            tvNbObs.text = "${sortie.observations.count { !it.releveSansEspece }} obs."
            tvNbPts.text = "${sortie.pointsParcours.size} pts"
            // Dernier envoi en échec → cadre rouge + message, pour que l'échec reste visible
            // après la fermeture du dialog d'erreur. Reset explicite sinon (recyclage).
            val erreur = sortie.derniereErreurEnvoi
            if (erreur != null) {
                root.background = cadreColore(couleurErreur(root.context), root.resources.displayMetrics.density)
                tvErreurEnvoi.visibility = android.view.View.VISIBLE
                tvErreurEnvoi.text = "⚠ ${erreur.lineSequence().first()}"
            } else {
                root.background = null
                tvErreurEnvoi.visibility = android.view.View.GONE
            }
            // « ✅ Envoyée » : même marqueur d'état que « Mes stations » — l'onglet le dit déjà,
            // mais le marqueur ligne rend l'état lisible aussi hors contexte (recherche visuelle).
            if (sortie.envoyeGeoNature) {
                tvEtat.visibility = android.view.View.VISIBLE
                tvEtat.setTextColor(couleurSucces(root.context))
                tvEtat.text = "✅ Envoyée"
            } else {
                tvEtat.visibility = android.view.View.GONE
            }
            root.setOnClickListener { onClick(sortie) }
            btnSupprimer.setOnClickListener { onDelete(sortie) }
            // Bouton "Lister les espèces" : dès qu'il y a au moins une observation, ouvre le
            // même récapitulatif (espèce · cd_nom · nb ind.) que l'écran de détail, sans avoir
            // à ouvrir la carte.
            val aDesObs = sortie.observations.any { !it.releveSansEspece }
            btnListe.visibility = if (aDesObs) android.view.View.VISIBLE else android.view.View.GONE
            btnListe.setOnClickListener { onListe(sortie) }
            // Bouton "Continuer la saisie" : visible uniquement pour les sorties à envoyer
            // (= non envoyées et non importées). Une sortie déjà envoyée n'a pas vocation
            // à être ré-éditée côté local ; une sortie importée (GPX externe) idem.
            val peutEditer = !sortie.envoyeGeoNature && !sortie.estImportee
            btnEditer.visibility = if (peutEditer) android.view.View.VISIBLE else android.view.View.GONE
            btnEditer.setOnClickListener { onEdit(sortie) }
            // Bouton "Envoyer au serveur" : même conditions que l'édition (non envoyée, non
            // importée), droit de création OCCTAX (CRUVED C, comme OccHab) ET au moins une
            // obs déterminée (cd_nom résolu) — sinon rien à envoyer.
            val peutEnvoyer = peutEditer && envoiAutorise &&
                sortie.observations.any { it.cdNom != null || it.releveSansEspece }
            btnEnvoyer.visibility = if (peutEnvoyer) android.view.View.VISIBLE else android.view.View.GONE
            btnEnvoyer.setOnClickListener { onEnvoyer(sortie) }
        }
    }

    override fun getItemCount() = items.size
}