package app.revanced.manager.domain.repository

import android.util.Log
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.manager.base.Preference
import app.revanced.manager.network.api.ApiResponseCache
import app.revanced.manager.network.api.EndpointState
import app.revanced.manager.network.api.EndpointState.ActiveEndpoint
import app.revanced.manager.network.api.FailureClass
import app.revanced.manager.network.api.NetworkFailureClassifier
import app.revanced.manager.network.dto.ReVancedAnnouncement
import app.revanced.manager.network.dto.ReVancedAsset
import app.revanced.manager.network.dto.ReVancedAssetHistory
import app.revanced.manager.network.dto.ReVancedGitRepository
import app.revanced.manager.network.dto.ReVancedInfo
import app.revanced.manager.network.service.HttpService
import app.revanced.manager.network.service.RawResponse
import app.revanced.manager.network.utils.APIFailure
import app.revanced.manager.network.utils.APIResponse
import app.revanced.manager.util.tag
import io.ktor.client.request.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

class ReVancedRepository(
    private val http: HttpService,
    private val prefs: PreferencesManager,
    private val cache: ApiResponseCache,
    private val endpointState: EndpointState,
) {
    private val json get() = http.json
    private val defaultApiVersion = "v5"

    suspend fun getAnnouncements(): APIResponse<List<ReVancedAnnouncement>> =
        resilientGet<List<ReVancedAnnouncement>>("announcements").response

    suspend fun getLatestAppInfo(): APIResponse<ReVancedAsset> =
        resilientGet<ReVancedAsset>("manager${prefs.useManagerPrereleases.prereleaseString()}").response

    suspend fun getAppHistory(): APIResponse<List<ReVancedAssetHistory>> =
        resilientGet<List<ReVancedAssetHistory>>("manager/history${prefs.useManagerPrereleases.prereleaseString()}").response

    suspend fun getPatchesUpdate(): APIResponse<ReVancedAsset> =
        resilientGet<ReVancedAsset>("patches${prefs.usePatchesPrereleases.prereleaseString()}").response

    suspend fun getPatchesHistory(
        apiUrl: String,
        prerelease: Boolean,
    ): APIResponse<List<ReVancedAssetHistory>> {
        val route = "patches/history${prerelease.prereleaseString()}"
        val normalized = apiUrl.trimEnd('/')
        return if (normalized == endpointState.primaryUrl()) {
            resilientGet<List<ReVancedAssetHistory>>(route).response
        } else {
            directGet<List<ReVancedAssetHistory>>(normalized, defaultApiVersion, route)
        }
    }

    suspend fun getDownloaderUpdate(): APIResponse<ReVancedAsset> =
        resilientGet<ReVancedAsset>("manager/downloaders${prefs.useDownloaderPrerelease.prereleaseString()}").response

    suspend fun getContributors(): APIResponse<List<ReVancedGitRepository>> =
        resilientGet<List<ReVancedGitRepository>>("contributors").response

    suspend fun getInfo(): APIResponse<ReVancedInfo> {
        val result = resilientGet<ReVancedInfo>("about")
        if (result.response is APIResponse.Success && result.servedBy == ActiveEndpoint.PRIMARY) {
            endpointState.updateBackupFromAbout(result.response.data.api?.fallback)
        }
        return result.response
    }

    suspend fun probePrimary(): Boolean = withContext(Dispatchers.IO) {
        val url = "${endpointState.primaryUrl()}/$defaultApiVersion/about"
        val raw = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching { http.requestRaw { url(url) } }.getOrNull()
        }
        if (raw !is APIResponse.Success) return@withContext false
        runCatching { json.decodeFromString<ReVancedInfo>(raw.data.body) }.isSuccess
    }

    private data class ResilientResult<T>(
        val response: APIResponse<T>,
        val servedBy: ActiveEndpoint?,
    )

    private suspend inline fun <reified T> resilientGet(
        route: String,
        apiVersion: String = defaultApiVersion,
    ): ResilientResult<T> = withContext(Dispatchers.IO) {
        val cacheKey = "$apiVersion/$route"
        val attemptOrder = if (endpointState.active.value == ActiveEndpoint.PRIMARY) {
            listOf(
                ActiveEndpoint.PRIMARY to endpointState.primaryUrl(),
                ActiveEndpoint.BACKUP to endpointState.backupUrl(),
            )
        } else {
            listOf(ActiveEndpoint.BACKUP to endpointState.backupUrl())
        }

        var lastFailure: APIResponse<T> = APIResponse.Failure(
            APIFailure(IllegalStateException("No request attempts were made"), null)
        )

        for ((endpointKind, baseUrl) in attemptOrder) {
            val fullUrl = "$baseUrl/$apiVersion/$route"

            attempts@ for (attempt in 1..MAX_ATTEMPTS) {
                if (attempt > 1) delay(BACKOFF_BASE_MS shl (attempt - 2))
                Log.d(tag, "ReVancedRepository: $endpointKind attempt $attempt/$MAX_ATTEMPTS -> $fullUrl")
                val raw = http.requestRaw { url(fullUrl) }

                if (raw is APIResponse.Success) {
                    val decoded = decode<T>(raw.data.body)
                    if (decoded is APIResponse.Success) {
                        cache.write(cacheKey, raw.data.body, raw.data.cacheControlMaxAgeMillis)
                        when (endpointKind) {
                            ActiveEndpoint.PRIMARY -> endpointState.markPrimaryResponseSucceeded()
                            ActiveEndpoint.BACKUP -> {
                                endpointState.switchToBackup()
                                endpointState.markBackupResponseSucceeded()
                            }
                        }
                        return@withContext ResilientResult(decoded, endpointKind)
                    }
                    lastFailure = decoded
                    Log.w(tag, "ReVancedRepository: $endpointKind 200-but-decode-failed at $fullUrl, treating as transient")
                    continue@attempts
                }

                val typedFailure = raw.coerceFailureType<T>()
                lastFailure = typedFailure
                when (NetworkFailureClassifier.classify(raw)) {
                    FailureClass.PERMANENT, FailureClass.DNS_FAILURE -> break@attempts
                    FailureClass.TRANSIENT -> continue@attempts
                }
            }
        }

        readCacheOrNull<T>(cacheKey)?.let { return@withContext ResilientResult(it, servedBy = null) }
        ResilientResult(lastFailure, servedBy = null)
    }

    private suspend inline fun <reified T> directGet(
        baseUrl: String,
        apiVersion: String,
        route: String,
    ): APIResponse<T> = withContext(Dispatchers.IO) {
        val fullUrl = "$baseUrl/$apiVersion/$route"
        var lastFailure: APIResponse<T> = APIResponse.Failure(
            APIFailure(IllegalStateException("No request attempts were made"), null)
        )

        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) delay(BACKOFF_BASE_MS shl (attempt - 2))
            Log.d(tag, "ReVancedRepository: direct attempt $attempt/$MAX_ATTEMPTS -> $fullUrl")
            val raw = http.requestRaw { url(fullUrl) }

            if (raw is APIResponse.Success) {
                val decoded = decode<T>(raw.data.body)
                if (decoded is APIResponse.Success) return@withContext decoded
                lastFailure = decoded
                continue
            }

            val typedFailure = raw.coerceFailureType<T>()
            lastFailure = typedFailure
            when (NetworkFailureClassifier.classify(raw)) {
                FailureClass.PERMANENT, FailureClass.DNS_FAILURE -> return@withContext typedFailure
                FailureClass.TRANSIENT -> continue
            }
        }

        lastFailure
    }

    private suspend inline fun <reified T> readCacheOrNull(cacheKey: String): APIResponse<T>? {
        val entry = cache.read(cacheKey) ?: return null
        val decoded = decode<T>(entry.body)
        if (decoded !is APIResponse.Success) return null
        if (!entry.isFresh) {
            Log.d(tag, "ReVancedRepository: serving stale cache for $cacheKey (age=${entry.ageMillis}ms)")
        }
        return decoded
    }

    private inline fun <reified T> decode(body: String): APIResponse<T> = try {
        APIResponse.Success(json.decodeFromString<T>(body))
    } catch (t: SerializationException) {
        APIResponse.Failure(APIFailure(t, body))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R> APIResponse<RawResponse>.coerceFailureType(): APIResponse<R> {
        check(this !is APIResponse.Success) { "coerceFailureType called on Success" }
        return this as APIResponse<R>
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        const val BACKOFF_BASE_MS = 250L
        const val PROBE_TIMEOUT_MS = 5_000L

        private suspend fun Preference<Boolean>.prereleaseString() = if (get()) "/prerelease" else ""
        private fun Boolean.prereleaseString() = if (this) "/prerelease" else ""
    }
}
