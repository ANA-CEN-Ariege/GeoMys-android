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

package fr.ariegenature.geonat.store

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class GeoNatureConfig(context: Context) {
    private val prefs = context.getSharedPreferences("gn_config", Context.MODE_PRIVATE)

    private val encryptedPrefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "gn_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback non chiffré : le mot de passe sera en clair. On le signale
        // explicitement pour ne pas masquer un problème silencieux côté Keystore.
        Log.w("GeoNatureConfig", "EncryptedSharedPreferences indisponible — fallback non chiffré", e)
        prefs
    }

    var urlServeur: String
        get() = prefs.getString("gn_url", "") ?: ""
        set(v) = prefs.edit().putString("gn_url", v).apply()

    var login: String
        get() = prefs.getString("gn_login", "") ?: ""
        set(v) = prefs.edit().putString("gn_login", v).apply()

    var motDePasse: String
        get() = encryptedPrefs.getString("gn_mdp", "") ?: ""
        set(v) = encryptedPrefs.edit().putString("gn_mdp", v).apply()

    var idDataset: String
        get() = prefs.getString("gn_dataset", "") ?: ""
        set(v) = prefs.edit().putString("gn_dataset", v).apply()

    /** Nom du jeu de données sélectionné (affiché dans Config GeoNature à la place de l'id). */
    var nomDataset: String
        get() = prefs.getString("gn_dataset_nom", "") ?: ""
        set(v) = prefs.edit().putString("gn_dataset_nom", v).apply()

    var taxaListeId: String
        get() = prefs.getString("gn_taxa_liste", "") ?: ""
        set(v) = prefs.edit().putString("gn_taxa_liste", v).apply()

    /** Nom complet (prénom + nom) renvoyé par GeoNature lors de la dernière connexion réussie.
     *  Mis à jour par GeoNatureAuth.testerConnexion. Fallback pour le déterminateur si
     *  aucun observateur par défaut n'est sélectionné. */
    var nomUtilisateur: String
        get() = prefs.getString("gn_nom_utilisateur", "") ?: ""
        set(v) = prefs.edit().putString("gn_nom_utilisateur", v).apply()

    /** id_role de l'utilisateur actuellement connecté (renvoyé par GeoNature au login).
     *  Sert à pré-sélectionner cet utilisateur dans les champs observers/datalist de saisie.
     *  -1 si pas encore connecté ou non détecté. */
    var idRoleUtilisateur: Int
        get() = prefs.getInt("gn_id_role_utilisateur", -1)
        set(v) = prefs.edit().putInt("gn_id_role_utilisateur", v).apply()

    /** Nom complet de l'observateur sélectionné comme déterminateur par défaut. */
    var observateurDefautNom: String
        get() = prefs.getString("gn_obs_defaut_nom", "") ?: ""
        set(v) = prefs.edit().putString("gn_obs_defaut_nom", v).apply()

    /** id_role de l'observateur sélectionné comme déterminateur par défaut. */
    var observateurDefautId: String
        get() = prefs.getString("gn_obs_defaut_id", "") ?: ""
        set(v) = prefs.edit().putString("gn_obs_defaut_id", v).apply()

    /** Cache JSON des datasets chargés depuis le serveur (List<GeoNatureDataset>). */
    var datasetsCacheJson: String
        get() = prefs.getString("gn_cache_datasets", "") ?: ""
        set(v) = prefs.edit().putString("gn_cache_datasets", v).apply()

    /** Cache JSON des listes de taxons chargées (List<GeoNatureListe>). */
    var listesCacheJson: String
        get() = prefs.getString("gn_cache_listes", "") ?: ""
        set(v) = prefs.edit().putString("gn_cache_listes", v).apply()

    /** Cache JSON des observateurs chargés (List<GeoNatureObservateur>). */
    var observateursCacheJson: String
        get() = prefs.getString("gn_cache_obs", "") ?: ""
        set(v) = prefs.edit().putString("gn_cache_obs", v).apply()

    /** Cache JSON des champs additionnels Occtax (List<AdditionalFieldDef>) — tous niveaux confondus. */
    var additionalFieldsOcctaxJson: String
        get() = prefs.getString("gn_cache_add_fields_occtax", "") ?: ""
        set(v) = prefs.edit().putString("gn_cache_add_fields_occtax", v).apply()

    val connexionConfiguree: Boolean
        get() = urlServeur.trim().isNotEmpty() && login.trim().isNotEmpty() && motDePasse.isNotEmpty()

    val estConfiguree: Boolean
        get() = connexionConfiguree && idDataset.trim().isNotEmpty()
}