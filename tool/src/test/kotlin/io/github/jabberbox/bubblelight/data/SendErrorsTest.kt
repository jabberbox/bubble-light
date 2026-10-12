package io.github.jabberbox.bubblelight.data

import io.github.jabberbox.bubblelight.api.ApiException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendErrorsTest {
    @Test
    fun failuresToConnectNeverReachedTheServer() {
        assertTrue(SendErrors.neverReachedServer(UnknownHostException("no address for host")))
        assertTrue(SendErrors.neverReachedServer(ConnectException("failed to connect to /10.0.0.2 (port 1234)")))
        assertTrue(SendErrors.neverReachedServer(NoRouteToHostException("no route")))
        assertTrue(SendErrors.neverReachedServer(SocketTimeoutException("failed to connect to /10.0.0.2 (port 1234) after 15000ms")))
    }

    @Test
    fun aSendThatMayHaveArrivedIsNotQueuedBecauseItCouldGoTwice() {
        // Waiting for the answer timed out, so the message could already be on the Mac.
        assertFalse(SendErrors.neverReachedServer(SocketTimeoutException("timeout")))
        assertFalse(SendErrors.neverReachedServer(SocketTimeoutException("Read timed out")))
        // The connection dropped part way through.
        assertFalse(SendErrors.neverReachedServer(SocketException("Connection reset")))
        assertFalse(SendErrors.neverReachedServer(EOFException()))
        assertFalse(SendErrors.neverReachedServer(IOException("unexpected end of stream")))
        // The server answered, with an error.
        assertFalse(SendErrors.neverReachedServer(ApiException(500, "send failed (500)")))
    }
}
