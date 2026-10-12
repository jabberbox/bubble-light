package io.github.jabberbox.bubblelight.data

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Tells a send that certainly never reached the server from one that may have. Only the first kind is safe to queue
 * and send again by itself, because the second might already be on the Mac, and sending it again would deliver it twice.
 */
object SendErrors {
    /**
     * True for failures to connect at all: the address does not resolve, the connection is refused or unreachable, or
     * the attempt to connect timed out. A timeout while waiting for the answer is not one of these.
     */
    fun neverReachedServer(e: Throwable): Boolean = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException -> true
        is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
        else -> false
    }
}
