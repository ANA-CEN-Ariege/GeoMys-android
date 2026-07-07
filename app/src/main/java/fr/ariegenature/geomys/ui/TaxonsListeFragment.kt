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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import fr.ariegenature.geomys.databinding.FragmentTaxonsListeBinding
import fr.ariegenature.geomys.databinding.ItemTaxonListBinding
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.store.TaxRefEntry

class TaxonsListeFragment : Fragment() {
    private var _binding: FragmentTaxonsListeBinding? = null
    private val binding get() = _binding!!

    /** Liste + clés de recherche prêtes à l'emploi (résultat de la construction lourde). */
    private class ListeTaxons(
        val titre: String,
        val entries: List<TaxRefEntry>,
        val avecCle: List<Pair<TaxRefEntry, String>>,
    )

    companion object {
        // Regex des diacritiques compilée UNE fois : `"…".toRegex()` recompile un Pattern à
        // chaque appel — sur des milliers de taxons (normalisation des clés) c'était des
        // milliers de compilations à chaque ouverture de l'écran.
        private val DIACRITIQUES = "\\p{Mn}+".toRegex()

        // Cache process-wide de la DERNIÈRE liste construite (clé = idListe/taxonName). La
        // construction (matérialisation + tri + normalisation) est identique d'une ouverture
        // à l'autre ; sans ce cache, rouvrir la même liste refaisait tout le calcul.
        @Volatile private var cleCache: String? = null
        @Volatile private var dernier: ListeTaxons? = null

        private fun normaliser(s: String): String =
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replace(DIACRITIQUES, "")
                .lowercase()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTaxonsListeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }
        val adapter = TaxonAdapter(emptyList())
        binding.rvTaxons.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTaxons.adapter = adapter

        // Deux modes :
        //  - idListe > 0 : tous les taxons d'une liste donnée (depuis « Détails → par liste ») ;
        //  - sinon taxonName : un groupe taxonomique, filtré par la liste configurée (parcours historique).
        val idListe = arguments?.getInt("idListe", -1)?.takeIf { it > 0 }
        val nomListeArg = arguments?.getString("nomListe")?.takeIf { it.isNotEmpty() }
        val taxonNameArg = arguments?.getString("taxonName")
        val idListeFiltreConfig = GeoNatureConfig(requireContext()).taxaListeId.trim().toIntOrNull()

        // Clé de cache : identifie la liste demandée (idListe ou groupe taxon).
        val cle = if (idListe != null) "L$idListe" else "T${taxonNameArg.orEmpty()}"
        // Déjà construite (réouverture de la même liste) → affichage immédiat, aucun recalcul.
        dernier?.takeIf { cleCache == cle }?.let {
            configurerListeEtRecherche(it, adapter)
            return
        }

        // Construction de la liste (matérialisation du cache TaxRef + tri + clés de recherche
        // normalisées) déportée hors du thread UI : sur un gros référentiel c'était plusieurs
        // secondes de blocage AVANT que l'écran s'affiche. On montre un spinner en attendant.
        binding.progress.visibility = View.VISIBLE
        binding.tvTitre.text = "Chargement…"
        viewLifecycleOwner.lifecycleScope.launch {
            val liste = withContext(Dispatchers.Default) {
                val allEntries = TaxRefCache.entreesParCdNom()
                val (t, cdNoms) = if (idListe != null) {
                    (nomListeArg ?: "Liste $idListe") to TaxRefCache.cdNomsDansListe(idListe).toList()
                } else {
                    val taxon = taxonNameArg?.let { try { Taxon.valueOf(it) } catch (_: Exception) { null } }
                    (taxonNameArg ?: "") to (taxon?.let { TaxRefCache.indexParTaxon(it, idListeFiltreConfig) } ?: emptyList())
                }
                val es = cdNoms.mapNotNull { allEntries[it] }.sortedBy { it.nomFrOriginal ?: it.sciNom }
                // Clé de recherche pré-calculée par taxon (nom FR + nom scientifique + cd_nom),
                // normalisée une seule fois — on ne renormalise pas toute la liste à chaque frappe.
                val cles = es.map { it to normaliser("${it.nomFrOriginal ?: ""} ${it.sciNom} ${it.cdNom}") }
                ListeTaxons(t, es, cles).also { cleCache = cle; dernier = it }
            }
            if (_binding == null) return@launch
            binding.progress.visibility = View.GONE
            configurerListeEtRecherche(liste, adapter)
        }
    }

    /** Peuple la liste et branche la recherche live (appelé une fois les données prêtes). */
    private fun configurerListeEtRecherche(donnees: ListeTaxons, adapter: TaxonAdapter) {
        val titre = donnees.titre
        val entries = donnees.entries
        val avecCle = donnees.avecCle
        binding.progress.visibility = View.GONE
        fun afficher(liste: List<TaxRefEntry>, filtre: Boolean) {
            adapter.soumettre(liste)
            // Compteur : « n » sans filtre, « n / total » quand on filtre.
            binding.tvTitre.text =
                if (filtre) "$titre (${liste.size} / ${entries.size})" else "$titre (${entries.size})"
        }
        afficher(entries, filtre = false)

        binding.etRecherche.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                val q = normaliser(e?.toString().orEmpty()).trim()
                if (q.isEmpty()) {
                    afficher(entries, filtre = false)
                } else {
                    // Chaque mot doit matcher (recherche « laiche pend » → « Laîche pendante »),
                    // sur la clé combinée nom FR / scientifique / cd_nom.
                    val mots = q.split(" ").filter { it.isNotEmpty() }
                    val filtres = avecCle.filter { (_, cle) -> mots.all { cle.contains(it) } }.map { it.first }
                    afficher(filtres, filtre = true)
                    binding.rvTaxons.scrollToPosition(0)
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class TaxonAdapter(items: List<TaxRefEntry>) : RecyclerView.Adapter<TaxonAdapter.ViewHolder>() {
        private var items: List<TaxRefEntry> = items
        class ViewHolder(val binding: ItemTaxonListBinding) : RecyclerView.ViewHolder(binding.root)

        /** Remplace la liste affichée (résultat du filtre de recherche). */
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun soumettre(nouveaux: List<TaxRefEntry>) {
            items = nouveaux
            notifyDataSetChanged()
        }

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