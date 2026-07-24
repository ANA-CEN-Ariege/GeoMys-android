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
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.network.GNErreur
import fr.ariegenature.geomys.network.OccHabUpload
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OccHabStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** « Mes stations » OccHab : liste des stations saisies localement, avec envoi/édition/
 *  suppression par ligne (calqué sur [SortiesFragment]). L'envoi se fait ici (pas au formulaire). */
class OccHabStationsFragment : Fragment() {
    private var _binding: FragmentOcchabStationsBinding? = null
    private val binding get() = _binding!!
    private lateinit var occHabStore: OccHabStore
    private lateinit var adapter: OccHabStationAdapter
    private val occhabViewModel: OccHabViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOcchabStationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "Mes stations")
        occHabStore = OccHabStore(requireContext())

        adapter = OccHabStationAdapter(
            onDelete = { confirmerSuppression(it) },
            onEdit = { station ->
                occhabViewModel.reprendre(station)
                findNavController().naviguerSur(R.id.action_occhab_stations_to_station)
            },
            onEnvoyer = { envoyerStation(it) },
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        rafraichir()
    }

    override fun onResume() { super.onResume(); rafraichir() }

    private fun rafraichir() {
        val stations = occHabStore.charger().sortedByDescending { it.date }
        adapter.submitList(stations)
        binding.emptyView.visibility = if (stations.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (stations.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmerSuppression(station: OccHabStation) {
        AlertDialog.Builder(requireContext())
            .setTitle("Supprimer la station ?")
            .setMessage("Cette action est définitive.")
            .setPositiveButton("Supprimer") { _, _ ->
                occHabStore.supprimer(station.id)
                rafraichir()
            }
            .setNegativeButton(R.string.annuler, null)
            .show()
    }

    private fun envoyerStation(station: OccHabStation) {
        val gnConfig = GeoNatureConfig(requireContext())
        if (!gnConfig.estConfiguree) {
            AlertDialog.Builder(requireContext())
                .setTitle("Configuration requise")
                .setMessage("La connexion GeoNature n'est pas configurée. Ouvre la configuration (⚙️) avant d'envoyer.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val dialogEnvoi = AlertDialog.Builder(requireContext())
            .setTitle("Envoi en cours…")
            .setMessage("Envoi de la station vers GeoNature.")
            .setCancelable(false)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = OccHabUpload.envoyer(station, gnConfig)
                occHabStore.marquerEnvoyee(station.id, res.idStationServeur)
                if (!isAdded) return@launch
                rafraichir()
                AlertDialog.Builder(requireContext())
                    .setTitle("OccHab")
                    .setMessage("Station envoyée (${res.nbHabitats} habitat(s)).")
                    .setPositiveButton("OK", null).show()
            } catch (e: Exception) {
                val msg = (e as? GNErreur)?.message ?: e.message ?: "Erreur d'envoi"
                occHabStore.marquerErreurEnvoi(station.id, msg)
                if (!isAdded) return@launch
                rafraichir()
                AlertDialog.Builder(requireContext())
                    .setTitle("Erreur d'envoi")
                    .setMessage(msg)
                    .setPositiveButton("OK", null).show()
            } finally {
                runCatching { dialogEnvoi.dismiss() }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class OccHabStationAdapter(
    private val onDelete: (OccHabStation) -> Unit,
    private val onEdit: (OccHabStation) -> Unit,
    private val onEnvoyer: (OccHabStation) -> Unit,
) : RecyclerView.Adapter<OccHabStationAdapter.ViewHolder>() {
    private var items: List<OccHabStation> = emptyList()
    private val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

    fun submitList(list: List<OccHabStation>) {
        items = list
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemOcchabStationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOcchabStationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = items[position]
        with(holder.binding) {
            tvDate.text = fmt.format(Date(station.date))
            val geom = if (station.geometryType == "Polygon") "polygone" else "point"
            val nbHab = station.habitats.size
            tvInfos.text = "$geom · $nbHab habitat${if (nbHab > 1) "s" else ""}"

            val erreur = station.derniereErreurEnvoi
            when {
                station.envoyeGeoNature -> {
                    root.background = null
                    tvEtat.visibility = View.VISIBLE
                    tvEtat.setTextColor(0xFF2E7D32.toInt())
                    tvEtat.text = "✅ Envoyée"
                }
                erreur != null -> {
                    root.background = cadreColore(couleurErreur(root.context), root.resources.displayMetrics.density)
                    tvEtat.visibility = View.VISIBLE
                    tvEtat.setTextColor(couleurErreur(root.context))
                    tvEtat.text = "⚠ ${erreur.lineSequence().first()}"
                }
                else -> {
                    root.background = null
                    tvEtat.visibility = View.GONE
                }
            }

            btnSupprimer.setOnClickListener { onDelete(station) }
            val peutEditer = !station.envoyeGeoNature
            btnEditer.visibility = if (peutEditer) View.VISIBLE else View.GONE
            btnEditer.setOnClickListener { onEdit(station) }
            val peutEnvoyer = peutEditer && station.habitats.any { it.cdHab > 0 }
            btnEnvoyer.visibility = if (peutEnvoyer) View.VISIBLE else View.GONE
            btnEnvoyer.setOnClickListener { onEnvoyer(station) }
        }
    }

    override fun getItemCount() = items.size
}
