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

package fr.ariegenature.geonat.ui.saisie

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import fr.ariegenature.geonat.R
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

    fun demarrerEcoute() {
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
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH       -> "Aucune correspondance — réessayez"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Aucune voix détectée"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Erreur réseau — connexion requise"
                        SpeechRecognizer.ERROR_AUDIO          -> "Erreur microphone"
                        else -> "Erreur reconnaissance ($error)"
                    }
                    Toast.makeText(fragment.requireContext(), msg, Toast.LENGTH_SHORT).show()
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
