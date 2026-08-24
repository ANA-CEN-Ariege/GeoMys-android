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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentSuivisBinding
import fr.ariegenature.geomys.network.MonitoringApi
import fr.ariegenature.geomys.network.MonitoringModule
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.PictoCache
import kotlinx.coroutines.launch

/** Liste des protocoles (modules) du gn_module_monitoring de l'instance GeoNature. */
class SuivisFragment : Fragment() {
    private var _binding: FragmentSuivisBinding? = null
    private val binding get() = _binding!!
    private lateinit var gnConfig: GeoNatureConfig

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSuivisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "Monitoring")
        gnConfig = GeoNatureConfig(requireContext())

        binding.btnSaisiesAttente.setOnClickListener {
            findNavController().naviguerSur(fr.ariegenature.geomys.R.id.action_suivis_to_attente)
        }

        chargerModules()
    }

    override fun onResume() {
        super.onResume()
        // Rafraîchit le compteur "Saisies en attente" à chaque retour sur l'écran : il
        // change après une saisie ajoutée, après un envoi réussi, ou une suppression.
        majBandeauAttente()
    }

    private fun majBandeauAttente() {
        val n = fr.ariegenature.geomys.store.OutboxMonitoring.countEnAttente()
        val total = fr.ariegenature.geomys.store.OutboxMonitoring.tout().size
        android.util.Log.i("SuivisFragment",
            "majBandeauAttente : countEnAttente=$n, totalOutbox=$total")
        if (n > 0) {
            binding.btnSaisiesAttente.visibility = View.VISIBLE
            binding.btnSaisiesAttente.text = "Données en attente d'envoi ($n)"
        } else {
            binding.btnSaisiesAttente.visibility = View.GONE
        }
    }

    private fun chargerModules() {
        binding.progressSuivis.visibility = View.VISIBLE
        binding.tvErreurSuivis.visibility = View.GONE
        binding.llModules.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            val modules = try {
                MonitoringApi.chargerModules(gnConfig)
            } catch (e: Exception) {
                if (!isAdded) return@launch
                binding.progressSuivis.visibility = View.GONE
                binding.tvErreurSuivis.visibility = View.VISIBLE
                binding.tvErreurSuivis.text = "Erreur de chargement : ${e.message}"
                return@launch
            }
            if (!isAdded) return@launch
            binding.progressSuivis.visibility = View.GONE
            if (modules.isEmpty()) {
                binding.tvErreurSuivis.visibility = View.VISIBLE
                binding.tvErreurSuivis.text = "Aucun protocole accessible pour ${gnConfig.login}.\n" +
                    "Vérifie que gn_module_monitoring est installé sur le serveur " +
                    "et que l'utilisateur a au moins un droit CRUVED > 0 sur les modules concernés."
            } else {
                afficherModules(modules)
            }
        }
    }

    private fun afficherModules(modules: List<MonitoringModule>) {
        val inflater = LayoutInflater.from(requireContext())
        modules.forEach { m ->
            val row = inflater.inflate(R.layout.item_suivi_module, binding.llModules, false)
            row.findViewById<TextView>(R.id.tv_label).text = m.moduleLabel
            // Code technique du module (« chronoventaire_ana ») masqué : on n'affiche que le nom
            // du protocole + sa description.
            row.findViewById<TextView>(R.id.tv_code).visibility = View.GONE
            row.findViewById<TextView>(R.id.tv_desc).apply {
                m.moduleDesc?.let { text = it; visibility = View.VISIBLE }
            }
            // Picto du protocole. Comme le web du module Suivi, l'image vient de la CONVENTION
            // media/monitorings/<module_code>/img.jpg (INDÉPENDANTE de module_picto, qui reste
            // souvent au défaut « fa-puzzle-piece »). On tente donc cette image ; en cas d'absence
            // (404), on retombe sur l'emoji FontAwesome. Si module_picto est un chemin/URL d'image
            // explicite, il prime sur la convention.
            val ivPicto = row.findViewById<ImageView>(R.id.iv_picto)
            val tvPicto = row.findViewById<TextView>(R.id.tv_picto)
            val picto = m.modulePicto
            val emojiRepli = picto?.let { PictoMonitoring.faEnEmoji(it) }
            ivPicto.visibility = View.VISIBLE
            tvPicto.visibility = View.GONE
            chargerImagePicto(ivPicto, m.moduleCode, picto, tvPicto, emojiRepli)
            row.findViewById<ImageButton>(R.id.btn_info).setOnClickListener {
                findNavController().naviguerSur(
                    R.id.action_suivis_to_detail,
                    bundleOf("moduleCode" to m.moduleCode)
                )
            }
            row.findViewById<ImageButton>(R.id.btn_carte).setOnClickListener {
                findNavController().naviguerSur(
                    R.id.action_suivis_to_carte,
                    bundleOf(
                        "moduleCode" to m.moduleCode,
                        "objectType" to "module",
                        "id" to m.idModule,
                        "titre" to m.moduleLabel,
                        // Fil de la carte = racine "Suivis › Protocole" (pas de segment objet
                        // puisqu'on affiche le protocole lui-même). Au tap d'un site, on
                        // ajoutera son segment pour donner "Suivis › Protocole › Site".
                        "fil" to encoderFil(filRacineSuivis(m.moduleLabel)),
                    )
                )
            }
            binding.llModules.addView(row)
        }
    }

    /** Charge le picto d'un protocole : d'abord depuis le CACHE DISQUE local (offline + instantané,
     *  cf. [PictoCache]), sinon téléchargement + enregistrement. En cas d'ABSENCE d'image (404 /
     *  erreur / hors-ligne sans cache), retombe sur l'emoji FontAwesome ([emojiRepli]) affiché dans
     *  [repli]. Le bitmap est sous-échantillonné à la taille d'une vignette (mémoire + reco
     *  « bitmap » de Play). */
    private fun chargerImagePicto(
        target: ImageView, moduleCode: String, modulePicto: String?, repli: TextView, emojiRepli: String?,
    ) {
        target.tag = moduleCode  // détecte les recyclages de View.
        val coteMaxPx = (128 * resources.displayMetrics.density).toInt()
        val base = gnConfig.urlServeur
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val f = PictoCache.fichierOuTelecharger(base, moduleCode, modulePicto)
                        ?: return@runCatching null
                    // Sous-échantillonnage vers une vignette (~128 dp).
                    val bornes = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f.path, bornes)
                    var ech = 1
                    while (maxOf(bornes.outWidth, bornes.outHeight) / (ech * 2) >= coteMaxPx) ech *= 2
                    android.graphics.BitmapFactory.decodeFile(
                        f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = ech })
                }.getOrNull()
            }
            if (target.tag != moduleCode) return@launch
            if (bmp != null) {
                target.setImageBitmap(bmp)
                target.visibility = View.VISIBLE
                repli.visibility = View.GONE
            } else {
                // Aucune image pour ce protocole → repli sur l'emoji FontAwesome.
                target.visibility = View.GONE
                if (emojiRepli != null) { repli.text = emojiRepli; repli.visibility = View.VISIBLE }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
