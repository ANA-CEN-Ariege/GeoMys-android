package com.example.birdstrace.store

import android.content.Context
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

    val connexionConfiguree: Boolean
        get() = urlServeur.trim().isNotEmpty() && login.trim().isNotEmpty() && motDePasse.isNotEmpty()

    val estConfiguree: Boolean
        get() = connexionConfiguree && idDataset.trim().isNotEmpty()
}