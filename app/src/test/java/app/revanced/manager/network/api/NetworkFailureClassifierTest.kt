package app.revanced.manager.network.api

import app.revanced.manager.network.utils.APIError
import app.revanced.manager.network.utils.APIFailure
import app.revanced.manager.network.utils.APIResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException

class NetworkFailureClassifierTest {

    private fun failure(t: Throwable): APIResponse<Unit> =
        APIResponse.Failure(APIFailure(t, null))

    private fun httpError(code: Int): APIResponse<Unit> =
        APIResponse.Error(APIError(HttpStatusCode.fromValue(code), null))

    @Test
    fun `unknown host is DNS failure`() {
        assertEquals(
            FailureClass.DNS_FAILURE,
            NetworkFailureClassifier.classify(failure(UnknownHostException("api.revanced.app")))
        )
    }

    @Test
    fun `nested unknown host is DNS failure`() {
        val nested = IOException("wrapped", UnknownHostException("api.revanced.app"))
        assertEquals(FailureClass.DNS_FAILURE, NetworkFailureClassifier.classify(failure(nested)))
    }

    @Test
    fun `socket timeout is transient`() {
        assertEquals(
            FailureClass.TRANSIENT,
            NetworkFailureClassifier.classify(failure(SocketTimeoutException("timed out")))
        )
    }

    @Test
    fun `ssl handshake failure is transient`() {
        assertEquals(
            FailureClass.TRANSIENT,
            NetworkFailureClassifier.classify(failure(SSLHandshakeException("bad cert")))
        )
    }

    @Test
    fun `serialization failure is permanent`() {
        assertEquals(
            FailureClass.PERMANENT,
            NetworkFailureClassifier.classify(failure(SerializationException("malformed")))
        )
    }

    @Test
    fun `http 5xx is transient`() {
        assertEquals(FailureClass.TRANSIENT, NetworkFailureClassifier.classify(httpError(503)))
    }

    @Test
    fun `http 408 is transient`() {
        assertEquals(FailureClass.TRANSIENT, NetworkFailureClassifier.classify(httpError(408)))
    }

    @Test
    fun `http 404 is permanent`() {
        assertEquals(FailureClass.PERMANENT, NetworkFailureClassifier.classify(httpError(404)))
    }

    @Test
    fun `cancellation exception is rethrown`() {
        val response = failure(CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) {
            NetworkFailureClassifier.classify(response)
        }
    }

    @Test
    fun `unknown throwable defaults to transient`() {
        assertEquals(
            FailureClass.TRANSIENT,
            NetworkFailureClassifier.classify(failure(RuntimeException("???")))
        )
    }
}
