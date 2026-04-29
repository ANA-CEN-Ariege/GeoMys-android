package com.example.birdstrace.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.birdstrace.R
import com.example.birdstrace.databinding.FragmentObservationsBinding
import com.example.birdstrace.databinding.ItemObservationBinding
import com.example.birdstrace.gpx.genererGPX
import com.example.birdstrace.model.Observation
import com.example.birdstrace.model.Taxon
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ObservationsFragment : Fragment() {
    private var _binding: FragmentObservationsBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()
    private lateinit var adapter: ObservationAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObservationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ObservationAdapter(
            onDelete = { obs ->
                traceViewModel.supprimerObservation(obs.id)
            },
            onRepositionner = { obs ->
                findNavController().navigateUp()
                // Signal to TraceFragment to enter reposition mode
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnFermer.setOnClickListener { findNavController().navigateUp() }

        binding.btnExporterGpx.setOnClickListener { exporterGpx() }

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
            val sorted = obs.sortedByDescending { it.date }
            adapter.submitList(sorted)
            binding.tvTitle.text = getString(R.string.observations_count, obs.size)
            binding.emptyView.visibility = if (obs.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (obs.isEmpty()) View.GONE else View.VISIBLE
            binding.btnExporterGpx.isEnabled = obs.isNotEmpty() || traceViewModel.locationTracker.parcours.value?.isNotEmpty() == true
            binding.btnEffacerTout.isEnabled = obs.isNotEmpty() || traceViewModel.locationTracker.parcours.value?.isNotEmpty() == true
        }
    }

    private fun exporterGpx() {
        val obs = traceViewModel.observations.value ?: emptyList()
        val parcours = traceViewModel.locationTracker.parcours.value ?: emptyList()
        val gpxContent = genererGPX(obs, parcours)
        val file = File(requireContext().cacheDir, "bioscope_${System.currentTimeMillis()}.gpx")
        file.writeText(gpxContent)

        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.provider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.partager_gpx)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class ObservationAdapter(
    private val onDelete: (Observation) -> Unit,
    private val onRepositionner: (Observation) -> Unit
) : RecyclerView.Adapter<ObservationAdapter.ViewHolder>() {
    private var items: List<Observation> = emptyList()
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    fun submitList(list: List<Observation>) {
        items = list
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemObservationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemObservationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val obs = items[position]
        with(holder.binding) {
            tvEspece.text = obs.espece
            tvDate.text = fmt.format(Date(obs.date))
            tvCoord.text = "%.4f, %.4f".format(obs.latitude, obs.longitude)
            tvNombre.visibility = if (obs.nombre > 1) View.VISIBLE else View.GONE
            tvNombre.text = "×${obs.nombre}"
            tvNotes.visibility = if (obs.notes.isNotEmpty()) View.VISIBLE else View.GONE
            tvNotes.text = obs.notes
            btnSupprimer.setOnClickListener { onDelete(obs) }
            val ctx = holder.itemView.context
            when (obs.taxon ?: Taxon.OISEAU) {
                Taxon.OISEAU -> {
                    ivTaxonIcon.setImageResource(R.drawable.ic_bird_small)
                    ivTaxonIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.orange))
                }
                Taxon.MAMMIFERE -> {
                    ivTaxonIcon.setImageResource(R.drawable.ic_paw_small)
                    ivTaxonIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.brown))
                }
                Taxon.REPTILE -> {
                    ivTaxonIcon.setImageResource(R.drawable.ic_reptile_small)
                    ivTaxonIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.colorSecondary))
                }
            }
        }
    }

    override fun getItemCount() = items.size
}