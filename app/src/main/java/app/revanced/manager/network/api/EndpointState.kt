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
    enum class ActiveEndpoint { PRIMARY, BACKUP }

    private val _active = MutableStateFlow(ActiveEndpoint.PRIMARY)
    val active: StateFlow<ActiveEndpoint> = _active.asStateFlow()

    private val _primaryRecoveryAvailable = MutableStateFlow(false)
    val primaryRecoveryAvailable: StateFlow<Boolean> = _primaryRecoveryAvailable.asStateFlow()

    suspend fun primaryUrl(): String = prefs.api.get().trimEnd('/')
    suspend fun backupUrl(): String = prefs.apiBackup.get().trimEnd('/')
    fun switchToBackup(): Boolean = _active.compareAndSet(ActiveEndpoint.PRIMARY, ActiveEndpoint.BACKUP)

    suspend fun markBackupResponseSucceeded() {
        if (prefs.lastSessionUsedFallback.get()) return
        prefs.lastSessionUsedFallback.update(true)
    }

    suspend fun markPrimaryResponseSucceeded() {
        if (!prefs.lastSessionUsedFallback.get()) return
        prefs.lastSessionUsedFallback.update(false)
    }

    suspend fun updateBackupFromAbout(advertised: String?) {
        val normalized = advertised?.trim()?.trimEnd('/').orEmpty()
        if (normalized.isEmpty()) return
        if (!normalized.startsWith("https://")) {
            Log.w(tag, "EndpointState: ignoring non-HTTPS backup URL from /about: $normalized")
            return
        }
        if (normalized == backupUrl()) return
        Log.i(tag, "EndpointState: updating persisted backup endpoint to $normalized")
        prefs.apiBackup.update(normalized)
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
        const val DEFAULT_BACKUP_API_URL = "https://backup-api.revanced.app"
    }
}
