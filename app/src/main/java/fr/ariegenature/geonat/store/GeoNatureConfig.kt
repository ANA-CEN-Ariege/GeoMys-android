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

    /** Nom complet de l'observateur sélectionné comme déterminateur par défaut. */
    var observateurDefautNom: String
        get() = prefs.getString("gn_obs_defaut_nom", "") ?: ""
        set(v) = prefs.edit().putString("gn_obs_defaut_nom", v).apply()

    /** id_role de l'observateur sélectionné comme déterminateur par défaut. */
    var observateurDefautId: String
        get() = prefs.getString("gn_obs_defaut_id", "") ?: ""
        set(v) = prefs.edit().putString("gn_obs_defaut_id", v).apply()

    val connexionConfiguree: Boolean
        get() = urlServeur.trim().isNotEmpty() && login.trim().isNotEmpty() && motDePasse.isNotEmpty()

    val estConfiguree: Boolean
        get() = connexionConfiguree && idDataset.trim().isNotEmpty()
}