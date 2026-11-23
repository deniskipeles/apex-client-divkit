package com.example.auth

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import engine.tools.impl.BiometricsTool
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A real supervisor credential gate the whole app can drive.
 *
 * Tool calls (`tools://biometrics/authenticate`) run without an Activity, so they cannot show
 * an OS prompt themselves. This registry is the bridge: the tool parks a request here,
 * [CredentialGateOverlay] launches the system device-credential prompt (PIN/pattern/password,
 * or biometrics when enrolled), and the verified result is handed back to the suspended caller.
 *
 * Only the OS verifies identity. The previous implementation accepted any PIN - including no
 * PIN at all - so every refund and void was "supervisor approved".
 */
object CredentialGate {
    // Compose must observe the request or the overlay never launches the OS prompt.
    private var pending by mutableStateOf<Request?>(null)

    val request: Request? get() = pending

    class Request(
        val prompt: String,
        val requiredRole: String,
        private val onResult: (BiometricsTool.Verified?) -> Unit
    ) {
        private var settled = false

        fun deliver(result: BiometricsTool.Verified?) {
            if (settled) return
            settled = true
            pending = null
            onResult(result)
        }
    }

    /**
     * Shows the device-credential prompt and suspends until it resolves. Safe to call from any
     * dispatcher; the request is published on the main thread so the overlay recomposes.
     */
    suspend fun verify(prompt: String, requiredRole: String): BiometricsTool.Verified? =
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val request = Request(prompt, requiredRole) { result ->
                if (cont.isActive) cont.resume(result)
            }
            main.post { pending = request }
            cont.invokeOnCancellation { main.post { pending = null } }
        }

    /** The intent that shows the OS credential prompt, or null when no lock screen is set. */
    fun intentFor(context: Context, request: Request): Intent? {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return null
        if (!keyguard.isDeviceSecure) return null
        @Suppress("DEPRECATION")
        return keyguard.createConfirmDeviceCredentialIntent(request.prompt, request.requiredRole)
    }

    /** True when the device can actually verify a supervisor (a lock screen is configured). */
    fun isAvailable(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard?.isDeviceSecure == true
    }
}

/**
 * Drives the OS credential prompt whenever [CredentialGate] has a pending request, and renders
 * nothing otherwise, so it is safe to place once at the root of the composition.
 */
@Composable
fun CredentialGateOverlay() {
    val request = CredentialGate.request ?: return
    val context = LocalContext.current

    BackHandler { request.deliver(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val ok = activityResult.resultCode == android.app.Activity.RESULT_OK
        request.deliver(
            if (ok) {
                BiometricsTool.Verified(
                    authenticated = true,
                    method = "DEVICE_CREDENTIAL_VERIFIED"
                )
            } else {
                BiometricsTool.Verified(
                    authenticated = false,
                    method = "DEVICE_CREDENTIAL_DENIED"
                )
            }
        )
    }

    LaunchedEffect(request) {
        val intent = CredentialGate.intentFor(context, request)
        if (intent == null) {
            // No lock screen configured: the host genuinely cannot verify anyone.
            request.deliver(null)
        } else {
            launcher.launch(intent)
        }
    }
}
