package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.ariegenature.geonat.databinding.FragmentTaxonsListeBinding
import fr.ariegenature.geonat.databinding.ItemTaxonListBinding
import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.TaxRefCache
import fr.ariegenature.geonat.store.TaxRefEntry

class TaxonsListeFragment : Fragment() {
    private var _binding: FragmentTaxonsListeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTaxonsListeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val taxonName = arguments?.getString("taxonName") ?: return
        val taxon = try { Taxon.valueOf(taxonName) } catch (_: Exception) { null }

        // Filtre par la liste sélectionnée pour rester cohérent avec ce que la saisie propose.
        val idListeFiltre = GeoNatureConfig(requireContext()).taxaListeId.trim().toIntOrNull()
        val cdNoms = taxon?.let { TaxRefCache.indexParTaxon(it, idListeFiltre) } ?: emptyList()
        val allEntries = TaxRefCache.entreesParCdNom()
        val entries = cdNoms.mapNotNull { allEntries[it] }.sortedBy { it.nomFrOriginal ?: it.sciNom }

        binding.tvTitre.text = "$taxonName (${entries.size})"
        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }

        binding.rvTaxons.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTaxons.adapter = TaxonAdapter(entries)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class TaxonAdapter(private val items: List<TaxRefEntry>) : RecyclerView.Adapter<TaxonAdapter.ViewHolder>() {
        class ViewHolder(val binding: ItemTaxonListBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemTaxonListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.tvNomFr.text = item.nomFrOriginal ?: "—"
            holder.binding.tvNomSci.text = item.sciNom
            holder.binding.tvCdNom.text = "cd_nom: ${item.cdNom}"
        }

        override fun getItemCount() = items.size
    }
}