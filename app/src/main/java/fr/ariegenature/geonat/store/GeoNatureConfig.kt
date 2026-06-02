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

    /** Le jeu de données configuré existe-t-il dans le cache des datasets du serveur courant ?
     *  Un id_dataset hérité d'un autre serveur (ex. après changement d'URL) part en clé
     *  étrangère invalide → 500 opaque côté GeoNature au lieu d'une vraie erreur de validation.
     *  Si le cache est vide (datasets jamais synchronisés), on ne peut pas trancher → on
     *  considère valide pour ne pas bloquer à tort un utilisateur en cours de configuration. */
    val datasetValide: Boolean
        get() {
            val id = idDataset.trim().toIntOrNull()?.takeIf { it > 0 } ?: return false
            val json = datasetsCacheJson.takeIf { it.isNotEmpty() } ?: return true
            return try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).any { arr.getJSONObject(it).optInt("id", -1) == id }
            } catch (_: Exception) {
                true
            }
        }

    /** Config complète ET cohérente avec le serveur courant : pré-requis pour démarrer ou
     *  envoyer une saisie (connexion + jeu de données réellement présent côté serveur). */
    val saisiePossible: Boolean
        get() = estConfiguree && datasetValide

    /** Un id est-il présent dans un cache JSON (liste d'objets) sous la clé [champ] ?
     *  Renvoie false si le cache est vide/illisible OU si l'id est absent — interprétation
     *  STRICTE (à l'inverse de [datasetValide], permissif) : sert à n'AUTORISER une saisie que
     *  lorsqu'on a la preuve que la sélection existe sur le serveur courant. */
    private fun idPresentDansCache(json: String, id: Int?, champ: String): Boolean {
        if (id == null || id <= 0) return false
        val j = json.takeIf { it.isNotEmpty() } ?: return false
        return try {
            val arr = org.json.JSONArray(j)
            (0 until arr.length()).any { arr.getJSONObject(it).optInt(champ, -1) == id }
        } catch (_: Exception) {
            false
        }
    }

    /** Le jeu de données sélectionné est présent dans le cache datasets du serveur courant. */
    val datasetPresentDansCache: Boolean
        get() = idPresentDansCache(datasetsCacheJson, idDataset.trim().toIntOrNull(), "id")

    /** Le jeu de données [id] est-il acceptable pour un envoi ? Permissif si le cache datasets est
     *  vide (on ne peut pas trancher → on n'empêche pas l'envoi), strict sinon. Sert à valider un
     *  override de relevé (« Détails du relevé ») AVANT le POST, pour éviter le 500 opaque sur FK
     *  invalide — même garde que [datasetValide], mais pour un id arbitraire. */
    fun datasetAcceptablePourEnvoi(id: Int): Boolean {
        if (datasetsCacheJson.isEmpty()) return true
        return idPresentDansCache(datasetsCacheJson, id, "id")
    }

    /** La liste de taxons sélectionnée est présente dans le cache des listes. */
    val listePresenteDansCache: Boolean
        get() = idPresentDansCache(listesCacheJson, taxaListeId.trim().toIntOrNull(), "id")

    /** L'observateur (déterminateur) par défaut est présent dans le cache des observateurs. */
    val observateurPresentDansCache: Boolean
        get() = idPresentDansCache(observateursCacheJson, observateurDefautId.trim().toIntOrNull(), "idRole")

    /** Saisie OCCTAX (multi/mono) réellement réalisable : connexion configurée ET les trois
     *  sélections (jeu de données, liste de taxons, observateur par défaut) sont chargées dans
     *  les caches du serveur courant. Faux tant que les caches sont vides (rien synchronisé) ou
     *  qu'une sélection est fantôme — pilote l'activation des boutons de saisie sur l'accueil. */
    val saisieOcctaxValide: Boolean
        get() = connexionConfiguree && datasetPresentDansCache &&
            listePresenteDansCache && observateurPresentDansCache
}