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

package fr.ariegenature.geomys.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stockage chiffré du mot de passe GeoNature — remplaçant « maison », minimal et pérenne, de
 * `EncryptedSharedPreferences` (androidx.security:security-crypto, bibliothèque DÉPRÉCIÉE).
 *
 * Principe : une clé AES-256-GCM générée DANS l'Android Keystore (alias `geomys_mdp`, jamais
 * exportable) chiffre le mot de passe ; le résultat `base64(iv + ciphertext)` est stocké dans des
 * SharedPreferences ORDINAIRES (fichier `gn_secure_v2`, clé `gn_mdp_chiffre`). Ce fichier est
 * exclu des sauvegardes (backup_rules.xml / data_extraction_rules.xml) : la clé Keystore ne
 * quitte jamais l'appareil, un blob restauré ailleurs serait indéchiffrable — et c'est un secret.
 *
 * Toute erreur Keystore est avalée (→ null / false), JAMAIS d'exception qui remonte : l'appelant
 * ([GeoNatureConfig]) bascule alors sur son repli mémoire — le mot de passe n'est jamais écrit
 * en clair sur disque.
 */
object MdpChiffre {

    private const val TAG = "MdpChiffre"
    private const val ALIAS_CLE = "geomys_mdp"
    private const val FICHIER = "gn_secure_v2"
    private const val CLE_PREF = "gn_mdp_chiffre"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_GCM_BITS = 128
    // IV généré par le Keystore au chiffrement — toujours 12 octets en GCM (préfixe du blob).
    private const val IV_OCTETS = 12

    // Cache process-wide du mot de passe déchiffré : évite un aller-retour Keystore (Binder) à
    // chaque lecture, alors que GeoNatureConfig — seul appelant — est instancié très fréquemment,
    // souvent sur le thread UI. Invalidé/mis à jour par [ecrire] et [effacer].
    @Volatile private var cache: String? = null
    @Volatile private var cacheValide = false

    /** Mot de passe déchiffré, ou null s'il n'y en a pas OU si le Keystore est indisponible /
     *  le blob illisible (l'appelant ne distingue pas : dans tous les cas, repli mémoire puis
     *  re-saisie par l'utilisateur). Ne lève jamais d'exception. */
    @Synchronized
    fun lire(context: Context): String? {
        if (cacheValide) return cache
        return try {
            val stocke = prefs(context).getString(CLE_PREF, null)
            if (stocke == null) {
                cache = null
                cacheValide = true
                return null
            }
            val brut = Base64.decode(stocke, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, cle() ?: return null,
                GCMParameterSpec(TAG_GCM_BITS, brut, 0, IV_OCTETS),
            )
            String(cipher.doFinal(brut, IV_OCTETS, brut.size - IV_OCTETS), Charsets.UTF_8)
                .also { cache = it; cacheValide = true }
        } catch (e: Exception) {
            Log.w(TAG, "Mot de passe stocké illisible (Keystore ?) — repli mémoire", e)
            null
        }
    }

    /** Chiffre puis stocke [mdp]. Renvoie false si le Keystore est indisponible — rien n'est
     *  alors écrit (surtout pas de clair) : l'appelant garde le mot de passe en mémoire seule.
     *  Ne lève jamais d'exception. */
    @Synchronized
    fun ecrire(context: Context, mdp: String): Boolean {
        return try {
            val cle = cle() ?: return false
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, cle)
            val chiffre = cipher.doFinal(mdp.toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(cipher.iv + chiffre, Base64.NO_WRAP)
            prefs(context).edit().putString(CLE_PREF, blob).apply()
            cache = mdp
            cacheValide = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Chiffrement du mot de passe impossible (Keystore ?) — repli mémoire", e)
            false
        }
    }

    /** Efface le mot de passe stocké (best-effort, jamais d'exception). La clé Keystore —
     *  inutilisable sans blob — est conservée pour un prochain [ecrire]. */
    @Synchronized
    fun effacer(context: Context) {
        try {
            prefs(context).edit().remove(CLE_PREF).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Effacement du mot de passe stocké impossible", e)
        }
        cache = null
        cacheValide = true
    }

    /** true si le chiffrement est opérationnel (clé Keystore accessible ou créable). Sert à
     *  [GeoNatureConfig] pour purger un ancien mot de passe en clair dès que possible. */
    fun disponible(): Boolean = cle() != null

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)

    /** Clé AES-256-GCM du Keystore Android (créée au premier usage, jamais exportable).
     *  null si le Keystore est KO — jamais d'exception. */
    private fun cle(): SecretKey? = try {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keystore.getKey(ALIAS_CLE, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            ALIAS_CLE,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                }
                .generateKey()
    } catch (e: Exception) {
        Log.w(TAG, "Android Keystore indisponible", e)
        null
    }
}
