package com.tote.app.ui

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Wraps the BiometricPrompt flow for decrypting the AndroidKeyStore master key.
 *
 * Per v1.1.0-alpha1: the master key requires Class 3 STRONG biometric auth
 * for decrypt. This composable handles the prompt + cipher-authorization
 * dance.
 *
 * Flow:
 * 1. The caller invokes [requestBiometricDecrypt] with a callback.
 * 2. If biometric isn't available (no biometrics enrolled, or no hardware),
 *    the callback is invoked with `cipher = null` — the caller falls back
 *    to the no-biometric decrypt path (the master key was created without
 *    auth req via graceful degradation).
 * 3. If biometric IS available:
 *    a. We call [com.tote.app.crypto.KeystoreWrapper.prepareDecryptCipher]
 *       to get a Cipher instance.
 *    b. We wrap it in BiometricPrompt.CryptoObject.
 *    c. We call BiometricPrompt.authenticate(promptInfo, cryptoObject).
 *    d. On success, the callback receives the authorized Cipher.
 *    e. The caller passes the ciphertext to
 *       [com.tote.app.crypto.KeystoreWrapper.decryptWithCipher].
 * 4. On failure (user cancels, biometric error), the callback is invoked
 *    with `cipher = null` and a non-null error message.
 *
 * @param onResult Called when the prompt completes — either with an
 *   authorized Cipher (success) or null (failure / unavailable / no-op).
 *   The error parameter is non-null only on actual failure (not on "no
 *   biometric available" graceful degradation).
 */
class BiometricDecryptHelper(private val context: Context) {

    private val TAG = "BiometricDecryptHelper"

    /**
     * Check if biometric Class 3 STRONG auth is available on this device.
     *
     * Returns:
     *   - 0 (BIOMETRIC_SUCCESS) if biometric is available and enrolled
     *   - non-zero error code otherwise (see BiometricManager.BIOMETRIC_*)
     */
    fun biometricStatus(): Int {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    }

    /**
     * Check if the master key requires biometric auth for decrypt.
     *
     * Wraps [com.tote.app.crypto.KeystoreWrapper.masterKeyRequiresBiometric].
     */
    fun masterKeyRequiresBiometric(): Boolean {
        val ks = com.tote.app.crypto.KeystoreWrapper(context)
        return ks.masterKeyRequiresBiometric()
    }

    /**
     * Request biometric authentication for decrypt.
     *
     * @param activity The FragmentActivity hosting the BiometricPrompt
     *   (required by BiometricPrompt's API).
     * @param onResult Called with (cipher, error) — cipher is non-null on
     *   success, null otherwise. Error is null on graceful degradation
     *   (no biometric available), non-null on actual failure.
     */
    fun requestBiometricDecrypt(
        activity: FragmentActivity,
        onResult: (cipher: Cipher?, error: String?) -> Unit,
    ) {
        val status = biometricStatus()
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            // Biometric not available — graceful degradation path
            Log.i(TAG, "Biometric not available (status=$status) — skipping prompt")
            onResult(null, null)
            return
        }
        if (!masterKeyRequiresBiometric()) {
            // Master key doesn't require biometric — skip prompt
            Log.i(TAG, "Master key doesn't require biometric — skipping prompt")
            onResult(null, null)
            return
        }

        val ks = com.tote.app.crypto.KeystoreWrapper(context)
        val cipher = try {
            ks.prepareDecryptCipher()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to prepare decrypt cipher", t)
            onResult(null, "Failed to prepare decrypt cipher: ${t.message}")
            return
        }

        val executor = ContextCompat.getMainExecutor(context)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Decrypt Tote secrets")
            .setSubtitle("Authenticate to access your pairing keys + paired device list.")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w(TAG, "Biometric error: $errorCode ($errString)")
                onResult(null, "Biometric error: $errString (code $errorCode)")
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authorizedCipher = result.cryptoObject?.cipher
                if (authorizedCipher != null) {
                    Log.i(TAG, "Biometric auth succeeded — cipher authorized")
                    onResult(authorizedCipher, null)
                } else {
                    Log.e(TAG, "Biometric auth succeeded but cipher was null")
                    onResult(null, "Biometric auth succeeded but cipher was null")
                }
            }

            override fun onAuthenticationFailed() {
                // User's fingerprint/face didn't match — keep the prompt up
                // (BiometricPrompt handles this automatically). Don't call
                // onResult here — the user can retry.
                Log.i(TAG, "Biometric auth failed (no match) — user can retry")
            }
        })

        val cryptoObject = BiometricPrompt.CryptoObject(cipher)
        prompt.authenticate(promptInfo, cryptoObject)
    }
}

/**
 * Compose-side state holder for biometric-protected decrypt.
 *
 * Tracks whether the prompt is currently showing, the result cipher,
 * and any error message. Use [rememberBiometricDecryptState] in a
 * composable + [BiometricDecryptHelper.requestBiometricDecrypt] to
 * trigger the prompt.
 */
class BiometricDecryptState {
    var isShowing by mutableStateOf(false)
        internal set
    var authorizedCipher by mutableStateOf<Cipher?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set

    internal fun reset() {
        isShowing = false
        authorizedCipher = null
        error = null
    }
}

@Composable
fun rememberBiometricDecryptState(): BiometricDecryptState {
    return remember { BiometricDecryptState() }
}
