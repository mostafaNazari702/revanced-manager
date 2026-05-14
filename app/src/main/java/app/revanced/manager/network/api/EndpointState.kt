package app.revanced.manager.network.api

import android.util.Log
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.util.tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EndpointState(
    private val prefs: PreferencesManager,
) {
    enum class ActiveEndpoint { PRIMARY, FALLBACK }

    private val _active = MutableStateFlow(ActiveEndpoint.PRIMARY)
    val active: StateFlow<ActiveEndpoint> = _active.asStateFlow()

    private val _primaryRecoveryAvailable = MutableStateFlow(false)
    val primaryRecoveryAvailable: StateFlow<Boolean> = _primaryRecoveryAvailable.asStateFlow()

    suspend fun primaryUrl(): String = prefs.api.get().trimEnd('/')
    suspend fun fallbackUrl(): String = prefs.apiFallback.get().trimEnd('/')

    suspend fun endpoints(): List<Pair<ActiveEndpoint, String>> {
        val primary = ActiveEndpoint.PRIMARY to primaryUrl()
        val fallback = ActiveEndpoint.FALLBACK to fallbackUrl()
        return when (_active.value) {
            ActiveEndpoint.PRIMARY -> listOf(primary, fallback)
            ActiveEndpoint.FALLBACK -> listOf(fallback)
        }
    }

    fun switchToFallback(): Boolean =
        _active.compareAndSet(ActiveEndpoint.PRIMARY, ActiveEndpoint.FALLBACK)

    suspend fun markEndpointResponseSucceeded(endpoint: ActiveEndpoint) {
        val usedFallback = endpoint == ActiveEndpoint.FALLBACK
        if (prefs.lastSessionUsedFallback.get() != usedFallback) {
            prefs.lastSessionUsedFallback.update(usedFallback)
        }
    }

    suspend fun updateFallbackFromAbout(advertised: String?) {
        val normalized = advertised?.trim()?.trimEnd('/').orEmpty()
        if (normalized.isEmpty()) return
        if (!normalized.startsWith("https://")) {
            Log.w(tag, "EndpointState: ignoring non-HTTPS fallback URL from /about: $normalized")
            return
        }
        if (normalized == fallbackUrl()) return
        Log.i(tag, "EndpointState: updating persisted fallback endpoint to $normalized")
        prefs.apiFallback.update(normalized)
    }

    fun signalPrimaryRecoveryDetected() {
        _primaryRecoveryAvailable.value = true
    }

    fun dismissPrimaryRecoveryPrompt() {
        _primaryRecoveryAvailable.value = false
    }

    suspend fun previousSessionUsedFallback(): Boolean = prefs.lastSessionUsedFallback.get()

    companion object {
        const val DEFAULT_PRIMARY_API_URL = "https://api.revanced.app"
        const val DEFAULT_FALLBACK_API_URL = "https://backup-api.revanced.app"
    }
}
