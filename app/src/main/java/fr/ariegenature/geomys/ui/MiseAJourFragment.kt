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
import fr.ariegenature.geomys.BuildConfig
import fr.ariegenature.geomys.databinding.FragmentMiseAJourBinding
import fr.ariegenature.geomys.network.MiseAJour
import kotlinx.coroutines.launch

/** Écran « Mise à jour » (depuis la roue dentée) : vérification MANUELLE d'une nouvelle release
 *  GitHub, puis téléchargement + installation. */
class MiseAJourFragment : Fragment() {
    private var _binding: FragmentMiseAJourBinding? = null
    private val binding get() = _binding!!
    private var urlApk: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMiseAJourBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = false)
        binding.tvVersionActuelle.text = "Version installée : ${BuildConfig.VERSION_NAME}"
        binding.btnVerifier.setOnClickListener { verifier() }
        binding.btnInstaller.setOnClickListener { telechargerEtInstaller() }
        binding.btnOk.setOnClickListener { findNavController().navigateUp() }
    }

    private fun verifier() {
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        binding.btnVerifier.isEnabled = false
        binding.btnInstaller.visibility = View.GONE
        binding.tvResultat.text = "Vérification en cours…"
        viewLifecycleOwner.lifecycleScope.launch {
            val r = MiseAJour.verifier(BuildConfig.VERSION_NAME)
            if (_binding == null) return@launch
            binding.progress.visibility = View.GONE
            binding.btnVerifier.isEnabled = true
            when (r) {
                is MiseAJour.Resultat.AJour ->
                    binding.tvResultat.text = "Vous êtes à jour (version ${r.version})."
                is MiseAJour.Resultat.Disponible -> {
                    urlApk = r.urlApk
                    binding.tvResultat.text =
                        ("Nouvelle version ${r.version} disponible.\n\n" + r.notes).trim()
                    binding.btnInstaller.visibility = View.VISIBLE
                }
                is MiseAJour.Resultat.Erreur ->
                    binding.tvResultat.text = "Échec de la vérification : ${r.message}"
            }
        }
    }

    private fun telechargerEtInstaller() {
        val url = urlApk ?: return
        binding.btnInstaller.isEnabled = false
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        binding.tvResultat.text = "Téléchargement…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apk = MiseAJour.telecharger(requireContext().applicationContext, url) { p ->
                    view?.post {
                        val b = _binding ?: return@post
                        if (p in 0..100) {
                            b.progress.isIndeterminate = false
                            b.progress.progress = p
                            b.tvResultat.text = "Téléchargement… $p %"
                        }
                    }
                }
                if (_binding == null) return@launch
                binding.progress.visibility = View.GONE
                binding.btnInstaller.isEnabled = true
                binding.tvResultat.text = "Lancement de l'installation…"
                MiseAJour.installer(requireContext(), apk)
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.progress.visibility = View.GONE
                binding.btnInstaller.isEnabled = true
                binding.tvResultat.text = "Échec du téléchargement : ${e.message}"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
