package com.example.birdstrace.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.birdstrace.R
import com.example.birdstrace.databinding.FragmentSortiesBinding
import com.example.birdstrace.databinding.ItemSortieBinding
import com.example.birdstrace.gpx.importerGPX
import com.example.birdstrace.model.Sortie
import com.example.birdstrace.store.SortieStore
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.*

class SortiesFragment : Fragment() {
    private var _binding: FragmentSortiesBinding? = null
    private val binding get() = _binding!!
    private lateinit var sortieStore: SortieStore
    private lateinit var adapter: SortieAdapter
    private var ongletCourant = 0

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
        sortieStore = SortieStore(requireContext())

        adapter = SortieAdapter(
            onClick = { sortie ->
                val bundle = Bundle().apply { putString("sortieId", sortie.id) }
                findNavController().navigate(R.id.action_sorties_to_detail, bundle)
            },
            onDelete = { sortie ->
                sortieStore.supprimer(sortie.id)
                refreshList()
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }
        binding.btnImporterGpx.setOnClickListener { lancerImport() }

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
            1 -> "Aucune sortie envoyée à GeoNature"
            2 -> "Aucune sortie importée"
            else -> "Aucune sortie en attente d'envoi"
        }
        binding.emptyView.visibility = if (filtrees.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (filtrees.isEmpty()) View.GONE else View.VISIBLE
        (binding.emptyView.getChildAt(1) as? android.widget.TextView)?.text = emptyMsg
    }

    private fun updateTabCounts(toutes: List<Sortie>) {
        val aEnvoyer = toutes.count { !it.envoyeGeoNature && !it.estImportee }
        val envoyees = toutes.count { it.envoyeGeoNature }
        val importees = toutes.count { it.estImportee }
        binding.tabLayout.getTabAt(0)?.text = "À envoyer ($aEnvoyer)"
        binding.tabLayout.getTabAt(1)?.text = "Envoyées ($envoyees)"
        binding.tabLayout.getTabAt(2)?.text = "Importées ($importees)"
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
    private val onDelete: (Sortie) -> Unit
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
            tvNbObs.text = "${sortie.observations.size} obs."
            tvNbPts.text = "${sortie.pointsParcours.size} pts"
            root.setOnClickListener { onClick(sortie) }
            btnSupprimer.setOnClickListener { onDelete(sortie) }
        }
    }

    override fun getItemCount() = items.size
}