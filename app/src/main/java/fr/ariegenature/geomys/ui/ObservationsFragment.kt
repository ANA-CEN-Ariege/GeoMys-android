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

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentObservationsBinding
import fr.ariegenature.geomys.databinding.ItemObservationBinding
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.Taxon
import java.text.SimpleDateFormat
import java.util.*

/** Un relevé = un point + un instant + une session de saisie (mono-taxon ou multi-taxons).
 *  Les obs sans releveId (saisie rapide, import GPX) deviennent chacune un relevé solo. */
data class Releve(
    /** Clé de groupage. Null pour les solos (saisie mono-taxon historique). */
    val releveId: String?,
    val observations: List<Observation>,
) {
    val premier: Observation get() = observations.first()
    val date: Long get() = observations.minOf { it.date }
    val totalIndividus: Int get() = observations.sumOf { it.nombre }
}

/** Regroupe une liste d'obs par releveId. Préserve l'ordre — les obs sans releveId restent
 *  des relevés solos, celles avec le même releveId fusionnent en un seul. */
fun List<Observation>.grouperEnReleves(): List<Releve> {
    val groupes = LinkedHashMap<String, MutableList<Observation>>()
    val solos = mutableListOf<Releve>()
    forEach { obs ->
        val rid = obs.releveId
        if (rid.isNullOrEmpty()) solos.add(Releve(null, listOf(obs)))
        else groupes.getOrPut(rid) { mutableListOf() }.add(obs)
    }
    val groupes2 = groupes.map { (rid, obs) -> Releve(rid, obs) }
    return (groupes2 + solos).sortedByDescending { it.date }
}

class ObservationsFragment : Fragment() {
    private var _binding: FragmentObservationsBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()
    private lateinit var adapter: ReleveAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObservationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), traceViewModel.typeSaisieLabel)

        adapter = ReleveAdapter(
            onDelete = { releve -> demanderConfirmationSuppression(releve) },
            onEditer = { releve -> ouvrirEdition(releve) },
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnFermer.setOnClickListener { findNavController().navigateUp() }
        binding.btnEffacerTout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.tout_reinitialiser)
                .setMessage(R.string.action_irreversible)
                .setPositiveButton(R.string.effacer_trace_obs) { _, _ ->
                    traceViewModel.reinitialiser()
                    findNavController().navigateUp()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        traceViewModel.observations.observe(viewLifecycleOwner) { obs ->
            val releves = obs.grouperEnReleves()
            adapter.submitList(releves)
            binding.tvTitle.text = if (releves.size == obs.size) {
                getString(R.string.observations_count, obs.size)
            } else {
                "${releves.size} relevé${if (releves.size > 1) "s" else ""} · ${obs.size} obs."
            }
            binding.emptyView.visibility = if (obs.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (obs.isEmpty()) View.GONE else View.VISIBLE
            val nonVide = obs.isNotEmpty() || traceViewModel.locationTracker.parcours.value?.isNotEmpty() == true
            binding.btnEffacerTout.isEnabled = nonVide
        }
    }

    private fun demanderConfirmationSuppression(releve: Releve) {
        if (releve.observations.size == 1) {
            // Comportement historique : suppression immédiate sans confirmation pour les solos.
            val rid = releve.releveId
            if (!rid.isNullOrEmpty()) traceViewModel.supprimerReleve(rid)
            else traceViewModel.supprimerObservation(releve.premier.id)
            return
        }
        val especes = releve.observations.joinToString("\n") { "• ${it.espece}" }
        AlertDialog.Builder(requireContext())
            .setTitle("Supprimer ce relevé ?")
            .setMessage("${releve.observations.size} espèces seront supprimées :\n$especes")
            .setPositiveButton("Supprimer") { _, _ ->
                val rid = releve.releveId
                if (!rid.isNullOrEmpty()) traceViewModel.supprimerReleve(rid)
                else traceViewModel.supprimerObservations(releve.observations.map { it.id })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun ouvrirEdition(releve: Releve) {
        val bundle = Bundle()
        val rid = releve.releveId
        if (!rid.isNullOrEmpty()) bundle.putString("releveId", rid)
        else bundle.putString("obsId", releve.premier.id)
        findNavController().navigate(R.id.action_observations_to_saisie, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class ReleveAdapter(
    private val onDelete: (Releve) -> Unit,
    private val onEditer: (Releve) -> Unit,
) : RecyclerView.Adapter<ReleveAdapter.ViewHolder>() {
    private var items: List<Releve> = emptyList()
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    fun submitList(list: List<Releve>) {
        items = list
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemObservationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemObservationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val r = items[position]
        val rep = r.premier
        with(holder.binding) {
            // tv_espece : 1 nom si solo, "N espèces : a, b, c" si relevé multi
            if (r.observations.size == 1) {
                tvEspece.text = rep.espece
                tvNotes.visibility = if (rep.notes.isNotEmpty()) View.VISIBLE else View.GONE
                tvNotes.text = rep.notes
            } else {
                val especes = r.observations.joinToString(", ") { it.espece }
                tvEspece.text = "${r.observations.size} espèces"
                tvNotes.visibility = View.VISIBLE
                tvNotes.text = especes
                tvNotes.maxLines = 2
            }
            tvDate.text = fmt.format(Date(r.date))
            tvCoord.text = "%.4f, %.4f".format(rep.latitude, rep.longitude)
            tvNombre.visibility = if (r.totalIndividus > 1) View.VISIBLE else View.GONE
            tvNombre.text = "×${r.totalIndividus}"
            btnModifier.setOnClickListener { onEditer(r) }
            btnSupprimer.setOnClickListener { onDelete(r) }
            iconePourTaxon(holder.binding, rep.taxon ?: Taxon.OISEAU)
        }
    }

    private fun iconePourTaxon(b: ItemObservationBinding, taxon: Taxon) {
        val ctx = b.root.context
        when (taxon) {
            Taxon.OISEAU      -> setIcone(b, R.drawable.oiseaux,     R.color.orange, ctx)
            Taxon.MAMMIFERE   -> setIcone(b, R.drawable.mammiferes2, R.color.brown, ctx)
            Taxon.REPTILE     -> setIcone(b, R.drawable.reptiles2,   R.color.colorSecondary, ctx)
            Taxon.BATRACIEN   -> setIcone(b, R.drawable.amphibiens,  R.color.blue_batracien, ctx)
            Taxon.POISSON     -> setIcone(b, R.drawable.poissons,    R.color.blue_poisson, ctx)
            Taxon.INSECTE     -> setIcone(b, R.drawable.insectes,    R.color.amber_insecte, ctx)
            Taxon.FONGE       -> setIcone(b, R.drawable.champignons2,R.color.brown_fonge, ctx)
            Taxon.MOLLUSQUE   -> setIcone(b, R.drawable.mollusques,  R.color.purple_invertebres, ctx)
            Taxon.INVERTEBRES -> setIcone(b, R.drawable.araignees,   R.color.purple_invertebres, ctx)
            Taxon.PLANTE      -> setIcone(b, R.drawable.fleurs,      R.color.teal, ctx)
        }
    }

    private fun setIcone(b: ItemObservationBinding, drawable: Int, color: Int, ctx: android.content.Context) {
        b.ivTaxonIcon.setImageResource(drawable)
        b.ivTaxonIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, color))
    }

    override fun getItemCount() = items.size
}
