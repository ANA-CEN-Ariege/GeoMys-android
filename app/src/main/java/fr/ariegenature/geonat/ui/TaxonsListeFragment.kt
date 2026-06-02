/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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
        
        val allEntries = TaxRefCache.entreesParCdNom()
        // Deux modes :
        //  - idListe > 0 : tous les taxons d'une liste donnée (depuis « Détails → par liste ») ;
        //  - sinon taxonName : un groupe taxonomique, filtré par la liste configurée (parcours historique).
        val idListe = arguments?.getInt("idListe", -1)?.takeIf { it > 0 }
        val (titre, cdNoms) = if (idListe != null) {
            val nom = arguments?.getString("nomListe")?.takeIf { it.isNotEmpty() } ?: "Liste $idListe"
            nom to TaxRefCache.cdNomsDansListe(idListe).toList()
        } else {
            val taxonName = arguments?.getString("taxonName") ?: return
            val taxon = try { Taxon.valueOf(taxonName) } catch (_: Exception) { null }
            val idListeFiltre = GeoNatureConfig(requireContext()).taxaListeId.trim().toIntOrNull()
            taxonName to (taxon?.let { TaxRefCache.indexParTaxon(it, idListeFiltre) } ?: emptyList())
        }
        val entries = cdNoms.mapNotNull { allEntries[it] }.sortedBy { it.nomFrOriginal ?: it.sciNom }

        binding.tvTitre.text = "$titre (${entries.size})"
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