package app.revanced.manager.network.api

import app.revanced.manager.network.utils.APIError
import app.revanced.manager.network.utils.APIFailure
import app.revanced.manager.network.utils.APIResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.net.UnknownHostException
import javax.net.ssl.SSLException




enum class FailureClass { TRANSIENT, DNS_FAILURE, PERMANENT }

object NetworkFailureClassifier {


    fun classify(response: APIResponse<*>): FailureClass = when (response) {
        is APIResponse.Success -> error("NetworkFailureClassifier.classify called on Success")
        is APIResponse.Error -> classifyHttp(response.error)
        is APIResponse.Failure -> classifyThrown(response.error)
    }

    private fun classifyHttp(error: APIError): FailureClass = when (error.code.value) {
        in 500..599 -> FailureClass.TRANSIENT
        HttpStatusCode.RequestTimeout.value -> FailureClass.TRANSIENT
        else -> FailureClass.PERMANENT
    }

    private fun classifyThrown(failure: APIFailure): FailureClass {
        var current: Throwable? = failure.cause ?: failure
        while (current != null) {
            when (current) {
                is CancellationException -> throw current
                is SerializationException -> return FailureClass.PERMANENT
                is UnknownHostException -> return FailureClass.DNS_FAILURE
                is SocketTimeoutException -> return FailureClass.TRANSIENT
                is SSLException -> return FailureClass.TRANSIENT
                is MalformedURLException -> return FailureClass.PERMANENT
                is URISyntaxException -> return FailureClass.PERMANENT
                is IllegalArgumentException -> return FailureClass.PERMANENT
            }
            current = current.cause
        }
        return FailureClass.TRANSIENT
    }
}
