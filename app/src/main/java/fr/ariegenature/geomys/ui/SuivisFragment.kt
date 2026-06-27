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
            findNavController().navigate(fr.ariegenature.geomys.R.id.action_suivis_to_attente)
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
            row.findViewById<TextView>(R.id.tv_code).text = m.moduleCode
            row.findViewById<TextView>(R.id.tv_desc).apply {
                m.moduleDesc?.let { text = it; visibility = View.VISIBLE }
            }
            // Picto : si module_picto ressemble à une URL/chemin d'image → ImageView,
            // sinon fallback emoji depuis la nomenclature FontAwesome.
            val ivPicto = row.findViewById<ImageView>(R.id.iv_picto)
            val tvPicto = row.findViewById<TextView>(R.id.tv_picto)
            val picto = m.modulePicto
            if (picto != null && PictoMonitoring.estImagePicto(picto)) {
                ivPicto.visibility = View.VISIBLE
                chargerImagePicto(ivPicto, urlAbsoluePicto(picto))
            } else {
                val emoji = picto?.let { PictoMonitoring.faEnEmoji(it) }
                if (emoji != null) { tvPicto.text = emoji; tvPicto.visibility = View.VISIBLE }
            }
            row.findViewById<ImageButton>(R.id.btn_info).setOnClickListener {
                findNavController().navigate(
                    R.id.action_suivis_to_detail,
                    bundleOf("moduleCode" to m.moduleCode)
                )
            }
            row.findViewById<ImageButton>(R.id.btn_carte).setOnClickListener {
                findNavController().navigate(
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

    /** Construit l'URL absolue du picto à partir de la base serveur. Si la chaîne est
     *  déjà absolue (http/https), elle est retournée telle quelle. Sinon on préfixe par
     *  l'URL du serveur GeoNature et un éventuel `/` manquant. */
    private fun urlAbsoluePicto(picto: String): String {
        val p = picto.trim()
        if (p.startsWith("http://", true) || p.startsWith("https://", true)) return p
        val base = gnConfig.urlServeur.trim().trimEnd('/')
        val rel = if (p.startsWith("/")) p else "/$p"
        return "$base$rel"
    }

    /** Charge l'image en arrière-plan dans un Bitmap et la pose sur l'ImageView. Best-effort
     *  (cache OS HttpURLConnection en place). En cas d'échec, masque l'ImageView pour ne
     *  pas laisser un trou. */
    private fun chargerImagePicto(target: ImageView, url: String) {
        target.tag = url  // détecte les recyclages de View (cas peu probable ici, on n'est
                          // pas dans un RecyclerView mais ça ne coûte rien).
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    if (conn.responseCode != 200) return@runCatching null
                    conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (target.tag == url) {
                if (bmp != null) target.setImageBitmap(bmp)
                else target.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
