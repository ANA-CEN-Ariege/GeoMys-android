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

package fr.ariegenature.geomys.ui.saisie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import fr.ariegenature.geomys.R
import com.google.android.material.textfield.TextInputLayout

/** Dictée vocale française pour un champ de saisie d'espèce.
 *  - Bascule l'icône du `TextInputLayout` (ic_mic / ic_mic_active) pendant l'écoute.
 *  - Pousse les résultats partiels et finaux dans l'`AutoCompleteTextView`.
 *  - Le caller fournit le `micPermissionLauncher` (obligatoire avant l'écoute).
 *  - Toujours appeler [destroy] depuis `onDestroyView`. */
class SpeechToTextHelper(
    private val fragment: Fragment,
    private val til: TextInputLayout,
    private val et: AutoCompleteTextView,
    private val micPermissionLauncher: ActivityResultLauncher<String>,
    /** Invoqué après le résultat final de la reconnaissance, juste après écriture du texte
     *  dans le champ. Permet au caller de réagir spécifiquement à la dictée (vs un
     *  changement de texte manuel) — ex. déclencher un ajout automatique sur match TaxRef. */
    private val onFinalText: ((String) -> Unit)? = null,
) {
    private var recognizer: SpeechRecognizer? = null

    fun lancer() {
        val ctx = fragment.requireContext()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Toast.makeText(ctx, "Reconnaissance vocale non disponible", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        demarrerEcoute()
    }

    fun demarrerEcoute(forcerOffline: Boolean? = null) {
        // Hors couverture (zone blanche), on tente la reconnaissance EMBARQUÉE (modèle vocal
        // hors-ligne du téléphone) ; en couverture, on reste en ligne (meilleure précision sur
        // les noms d'espèces). [forcerOffline] non-null = tentative de repli (pas de re-bascule).
        val offline = forcerOffline ?: !reseauDispo()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(fragment.requireContext()).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    til.setEndIconDrawable(R.drawable.ic_mic_active)
                }
                override fun onResults(results: Bundle?) {
                    til.setEndIconDrawable(R.drawable.ic_mic)
                    val texte = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    et.setText(texte)
                    et.setSelection(texte.length)
                    onFinalText?.invoke(texte)
                }
                override fun onError(error: Int) {
                    til.setEndIconDrawable(R.drawable.ic_mic)
                    // Bascule auto une seule fois : si le mode choisi échoue sur une erreur réseau
                    // (modèle hors-ligne absent en couverture, ou réseau faible en ligne), on
                    // retente dans l'AUTRE mode avant d'abandonner.
                    if (forcerOffline == null &&
                        (error == SpeechRecognizer.ERROR_NETWORK ||
                            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
                    ) {
                        demarrerEcoute(forcerOffline = !offline)
                        return
                    }
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH       -> "Aucune correspondance — réessayez"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Aucune voix détectée"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            if (offline) "Dictée hors-ligne indisponible : installez le modèle vocal " +
                                "« Français » hors connexion (paramètres de saisie vocale du téléphone)."
                            else "Erreur réseau — connexion requise"
                        SpeechRecognizer.ERROR_AUDIO          -> "Erreur microphone"
                        else -> "Erreur reconnaissance ($error)"
                    }
                    Toast.makeText(fragment.requireContext(), msg, Toast.LENGTH_LONG).show()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val partiel = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    et.setText(partiel)
                    et.setSelection(partiel.length)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                // FREE_FORM hors-ligne (modèle de dictée embarqué) ; WEB_SEARCH en ligne (court,
                // adapté aux noms d'espèces — comportement d'origine conservé en couverture).
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    if (offline) RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    else RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            })
        }
    }

    /** Réseau internet actuellement disponible ? (permission ACCESS_NETWORK_STATE déjà déclarée). */
    private fun reseauDispo(): Boolean {
        val cm = fragment.requireContext()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
