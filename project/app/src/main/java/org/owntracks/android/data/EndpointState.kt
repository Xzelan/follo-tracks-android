package org.owntracks.android.data

import android.content.Context
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import org.owntracks.android.R
import org.owntracks.android.support.interfaces.ConfigurationIncompleteException
import timber.log.Timber

/** The kind of state the endpoint is in. Carries no data: see [EndpointStatus] for that. */
enum class EndpointState {
  INITIAL,
  IDLE,
  CONNECTING,
  CONNECTED,
  DISCONNECTED,
  ERROR,
  ERROR_CONFIGURATION;

  fun getLabel(context: Context): String =
      when (this) {
        INITIAL -> context.resources.getString(R.string.INITIAL)
        IDLE -> context.resources.getString(R.string.IDLE)
        CONNECTING -> context.resources.getString(R.string.CONNECTING)
        CONNECTED -> context.resources.getString(R.string.CONNECTED)
        DISCONNECTED -> context.resources.getString(R.string.DISCONNECTED)
        ERROR -> context.resources.getString(R.string.ERROR)
        ERROR_CONFIGURATION -> context.resources.getString(R.string.ERROR_CONFIGURATION)
      }

  fun withMessage(message: String): EndpointStatus = EndpointStatus(this, message = message)

  fun withError(error: Throwable): EndpointStatus = EndpointStatus(this, error = error)
}

/**
 * The endpoint's state, with whatever message or error accompanies it.
 *
 * Immutable, so a message or error belongs to exactly one state change. (They used to be mutable
 * fields on the [EndpointState] constants themselves, so one set on, say, ERROR stuck to every
 * later ERROR and was shown in place of whatever came after - and setting ERROR again with a new
 * error emitted the same object, which a StateFlow drops as unchanged.)
 */
data class EndpointStatus(
    val state: EndpointState,
    val message: String? = null,
    val error: Throwable? = null,
) {
  fun getLabel(context: Context): String = state.getLabel(context)

  fun getErrorLabel(context: Context): String =
      when (val e = error) {
        // Configuration validator has failed
        is ConfigurationIncompleteException -> {
          when (e.cause) {
            // URL isn't a URL
            is IllegalArgumentException ->
                context.getString(R.string.statusEndpointStateMessageMalformedHostPort)
            else -> e.toString()
          }
        }
        is SocketTimeoutException ->
            context.getString(R.string.statusEndpointStateMessageSocketTimeout)
        // DNS fail
        is UnknownHostException -> context.getString(R.string.statusEndpointStateMessageUnknownHost)
        // Client cert errors show up like this
        is SSLProtocolException ->
            if (e.message != null && e.message!!.contains("TLSV1_ALERT_CERTIFICATE_REQUIRED")) {
              context.getString(R.string.statusEndpointStateMessageTLSEndpointClientCertsRequired)
            } else {
              context.getString(R.string.statusEndpointStateMessageTLSError, e.message)
            }
        is SSLHandshakeException ->
            if (e.cause?.cause is CertPathValidatorException) {
              context.getString(R.string.statusEndpointStateMessageTLSEndpointCANotTrustedError)
            } else {
              context.getString(R.string.statusEndpointStateMessageTLSError, e.message)
            }
        is SSLException -> context.getString(R.string.statusEndpointStateMessageTLSError, e.message)
        is EOFException -> context.getString(R.string.statusEndpointStateMessageEOFError)
        is ConnectException ->
            if (e.message?.contains("ECONNREFUSED") == true) {
              context.getString(R.string.statusEndpointStateMessageConnectionRefused)
            } else {
              context.getString(R.string.statusEndpointStateMessageUnableToConnect)
            }
        is IOException -> e.message ?: e.toString()
        else -> e.toString()
      }.also { Timber.v(error, "Rendering error as $it") }

  override fun toString(): String =
      if (message != null) {
        "$state ($message)"
      } else {
        state.toString()
      }
}
