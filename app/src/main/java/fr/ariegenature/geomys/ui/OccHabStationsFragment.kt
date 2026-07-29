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
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentOcchabStationsBinding
import fr.ariegenature.geomys.databinding.ItemOcchabStationBinding
import fr.ariegenature.geomys.model.OccHabSaisie
import fr.ariegenature.geomys.network.envoyerSaisieOccHabVersGeoNature
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OccHabStore
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** « Mes stations » OccHab : liste des SAISIES saisies localement (chacune regroupe 1..N stations),
 *  avec envoi/édition/suppression par saisie (calqué sur [SortiesFragment]). L'envoi transfère
 *  TOUTE la saisie (envoi partiel sans perte) ; l'édition rouvre la carte pour ajouter/rééditer
 *  ses stations. */
class OccHabStationsFragment : Fragment() {
    private var _binding: FragmentOcchabStationsBinding? = null
    private val binding get() = _binding!!
    private lateinit var occHabStore: OccHabStore
    private lateinit var adapter: OccHabSaisieAdapter
    private val occhabViewModel: OccHabViewModel by activityViewModels()
    private var ongletCourant = 0

    /** true pendant un envoi : les actions concurrentes sont refusées avec un toast — la liste
     *  reste consultable, pas de modal bloquant. */
    private var envoiEnCours = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOcchabStationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "Mes stations")
        occHabStore = OccHabStore(requireContext())

        adapter = OccHabSaisieAdapter(
            onDelete = { confirmerSuppression(it) },
            onEdit = { saisie ->
                // Réédition : on reprend la SAISIE puis on passe par la CARTE (ses stations
                // affichées, « Valider » ajoute une station, taper une station l'édite).
                occhabViewModel.reprendreSaisie(saisie)
                findNavController().naviguerSur(R.id.action_occhab_stations_to_carte)
            },
            onEnvoyer = { envoyerSaisie(it) },
            // CRUVED C du module OCCHAB (détecté à la synchro) : sans droit de création, le POST
            // partirait en 403 — on masque le bouton d'envoi.
            envoiAutorise = GeoNatureConfig(requireContext()).occhabPeutCreer,
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        setupTabs()
        rafraichir()
    }

    override fun onResume() { super.onResume(); rafraichir() }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("À envoyer"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Envoyées"))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { ongletCourant = tab.position; rafraichir() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun rafraichir() {
        val toutes = occHabStore.charger().sortedByDescending { it.date }
        val filtrees = when (ongletCourant) {
            1 -> toutes.filter { it.envoyeGeoNature }
            else -> toutes.filter { !it.envoyeGeoNature }
        }
        updateTabCounts(toutes)
        adapter.submitList(filtrees)
        val vide = filtrees.isEmpty()
        binding.emptyView.visibility = if (vide) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (vide) View.GONE else View.VISIBLE
        binding.tvEmpty.text = if (ongletCourant == 1)
            "Aucune saisie envoyée."
        else "Aucune saisie en attente d'envoi.\nCréez-en une depuis « OccHab » sur l'accueil."
    }

    private fun updateTabCounts(toutes: List<OccHabSaisie>) {
        binding.tabLayout.getTabAt(0)?.text = "À envoyer (${toutes.count { !it.envoyeGeoNature }})"
        binding.tabLayout.getTabAt(1)?.text = "Envoyées (${toutes.count { it.envoyeGeoNature }})"
    }

    private fun confirmerSuppression(saisie: OccHabSaisie) {
        if (envoiEnCours) {
            android.widget.Toast.makeText(requireContext(), "Un envoi est en cours — réessayez ensuite.",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(saisie.date))
        val n = saisie.stations.size
        val descr = if (n == 1) "saisie du $date (1 station)" else "saisie du $date ($n stations)"
        AlertDialog.Builder(requireContext())
            .setTitle("Supprimer la saisie ?")
            .setMessage("Supprimer la $descr ? Cette action est définitive.")
            .setPositiveButton("Supprimer") { _, _ ->
                occHabStore.supprimer(saisie.id)
                rafraichir()
            }
            .setNegativeButton(R.string.annuler, null)
            .show()
    }

    private fun envoyerSaisie(saisie: OccHabSaisie) {
        if (envoiEnCours) {
            android.widget.Toast.makeText(requireContext(), "Un envoi est déjà en cours…",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val gnConfig = GeoNatureConfig(requireContext())
        if (!gnConfig.estConfiguree) {
            AlertDialog.Builder(requireContext())
                .setTitle("Configuration requise")
                .setMessage("La connexion GeoNature n'est pas configurée. Ouvrez la configuration (⚙️) avant d'envoyer.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        // Progression INLINE (patron « Mes visites ») : la liste reste consultable.
        envoiEnCours = true
        binding.progressEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.visibility = View.VISIBLE
        binding.tvMessageEnvoi.text = "Envoi de la saisie vers GeoNature…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = envoyerSaisieOccHabVersGeoNature(saisie, occHabStore, gnConfig)
                if (!isAdded || _binding == null) return@launch
                rafraichir()
                AlertDialog.Builder(requireContext())
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

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class OccHabSaisieAdapter(
    private val onDelete: (OccHabSaisie) -> Unit,
    private val onEdit: (OccHabSaisie) -> Unit,
    private val onEnvoyer: (OccHabSaisie) -> Unit,
    /** CRUVED C du module OCCHAB : false → bouton d'envoi masqué (le POST serait refusé). */
    private val envoiAutorise: Boolean = true,
) : RecyclerView.Adapter<OccHabSaisieAdapter.ViewHolder>() {
    private var items: List<OccHabSaisie> = emptyList()
    private val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

    fun submitList(list: List<OccHabSaisie>) {
        items = list
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemOcchabStationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOcchabStationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val saisie = items[position]
        val density = holder.binding.root.resources.displayMetrics.density
        with(holder.binding) {
            tvDate.text = fmt.format(Date(saisie.date))
            val n = saisie.stations.size
            tvInfos.text = "$n station${if (n > 1) "s" else ""}"

            val erreur = saisie.derniereErreurEnvoi
            when {
                saisie.envoyeGeoNature -> {
                    root.background = null
                    tvEtat.visibility = View.VISIBLE
                    tvEtat.setTextColor(couleurSucces(root.context))
                    tvEtat.text = "✅ Envoyée"
                }
                erreur != null -> {
                    root.background = cadreColore(couleurErreur(root.context), density)
                    tvEtat.visibility = View.VISIBLE
                    tvEtat.setTextColor(couleurErreur(root.context))
                    tvEtat.text = "⚠ ${erreur.lineSequence().first()}"
                }
                else -> {
                    root.background = null
                    tvEtat.visibility = View.GONE
                }
            }

            btnSupprimer.setOnClickListener { onDelete(saisie) }
            val peutEditer = !saisie.envoyeGeoNature
            btnEditer.visibility = if (peutEditer) View.VISIBLE else View.GONE
            btnEditer.setOnClickListener { onEdit(saisie) }
            val peutEnvoyer = peutEditer && envoiAutorise &&
                saisie.stations.any { st -> st.habitats.any { it.cdHab > 0 } }
            btnEnvoyer.visibility = if (peutEnvoyer) View.VISIBLE else View.GONE
            btnEnvoyer.setOnClickListener { onEnvoyer(saisie) }
        }
    }

    override fun getItemCount() = items.size
}
