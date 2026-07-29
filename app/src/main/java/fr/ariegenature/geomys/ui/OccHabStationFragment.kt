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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentOcchabStationBinding
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OccHabStore

/**
 * Écran LISTE des habitats d'une station OccHab : habitats déjà créés (tap = éditer, poubelle =
 * supprimer) + « Ajouter un autre habitat » (→ écran de création), bouton « Détails » (champs de
 * la station, communs à la session) et « Enregistrer la station ». La création d'un habitat se
 * fait sur [OccHabHabitatFragment].
 */
class OccHabStationFragment : Fragment() {
    private var _binding: FragmentOcchabStationBinding? = null
    private val binding get() = _binding!!
    private val occhabViewModel: OccHabViewModel by activityViewModels()
    private lateinit var occHabStore: OccHabStore
    private lateinit var gnConfig: GeoNatureConfig

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOcchabStationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.racine.applySystemBarInsets()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "OccHab")
        occHabStore = OccHabStore(requireContext())
        gnConfig = GeoNatureConfig(requireContext())

        binding.btnDetails.setOnClickListener {
            ouvrirDialogDetailsOccHab(requireContext(), occhabViewModel, gnConfig) {
                majResume()
                // Les détails modifiés s'appliquent aussi à la station courante déjà sauvée.
                sauvegarderAuFilDeLEau()
            }
        }
        binding.btnAjouterHabitat.setOnClickListener { ouvrirEcranHabitat(null) }
        // Coche verte « terminer » (comme la saisie multi-taxons) : la station est déjà
        // enregistrée au fil de l'eau — on repart simplement sur une carte vierge.
        binding.btnTerminer.setOnClickListener {
            if (occhabViewModel.station.habitats.isNotEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "Votre station est enregistrée dans « Mes stations »",
                    Toast.LENGTH_LONG,
                ).show()
            }
            occhabViewModel.nouvelleStation()
            findNavController().naviguerSur(R.id.action_occhab_station_to_carte)
        }
    }

    /** Enregistrement AU FIL DE L'EAU (comme les saisies Occtax) : la station courante —
     *  détails de session fusionnés — est (ré)écrite dans le store dès qu'elle porte au moins
     *  un habitat, ou dès qu'elle y existe déjà (mise à jour après édition/suppression). */
    private fun sauvegarderAuFilDeLEau() {
        val station = occhabViewModel.stationAEnregistrer()
        val dejaEnregistree = occHabStore.stationsDeSaisie(occhabViewModel.saisieId)
            .any { it.id == station.id }
        if (station.habitats.isNotEmpty() || dejaEnregistree) {
            occHabStore.upsertStation(occhabViewModel.saisieId, station)
        }
    }

    override fun onResume() {
        super.onResume()
        // La liste + résumé se rafraîchissent au retour de l'écran de création d'habitat.
        majResume()
        rafraichirHabitats()
    }

    /** Ouvre l'écran de création / édition d'un habitat ([existantId] null = nouvel habitat). */
    private fun ouvrirEcranHabitat(existantId: String?) {
        findNavController().naviguerSur(
            R.id.action_occhab_liste_to_habitat,
            bundleOf("habitatId" to existantId),
        )
    }

    private fun majResume() {
        val s = occhabViewModel.station
        binding.tvGeometrie.text = when (s.geometryType) {
            "Polygon" -> {
                val n = try { org.json.JSONArray(s.geometryCoordsJson).length() } catch (_: Exception) { 0 }
                "Géométrie : polygone ($n sommets)"
            }
            else -> "Géométrie : point (%.5f, %.5f)".format(s.latitude, s.longitude)
        }
        val d = occhabViewModel.details
        val nom = d.nomDataset
            ?: datasetsPourOccHab(gnConfig).firstOrNull { it.first == d.idDataset }?.second
        binding.tvJdd.text = "Jeu de données : " + (nom ?: d.idDataset?.toString() ?: "(à choisir)")
    }

    private val density get() = resources.displayMetrics.density

    private fun rafraichirHabitats() {
        val container = binding.habitatsContainer
        container.removeAllViews()
        val habitats = occhabViewModel.station.habitats
        binding.tvAucunHabitat.visibility = if (habitats.isEmpty()) View.VISIBLE else View.GONE
        habitats.forEach { h ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                val bg = android.util.TypedValue()
                requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
                setBackgroundResource(bg.resourceId)
            }
            val txt = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = h.habitatLabel.ifBlank { h.nomCite }.ifBlank { "Habitat ${h.cdHab}" }
                textSize = 14f
            }
            val suppr = ImageButton(requireContext()).apply {
                setImageResource(R.drawable.ic_delete)
                background = null
                contentDescription = "Supprimer l'habitat"
                setColorFilter(couleurErreur(requireContext()))
                setOnClickListener {
                    occhabViewModel.supprimerHabitat(h.id)
                    rafraichirHabitats()
                    sauvegarderAuFilDeLEau()
                }
            }
            row.setOnClickListener { ouvrirEcranHabitat(h.id) }
            row.addView(txt)
            row.addView(suppr)
            container.addView(row)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
