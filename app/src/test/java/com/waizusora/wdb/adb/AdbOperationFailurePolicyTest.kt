package com.waizusora.wdb.adb

import io.github.rhythmcache.adb.AdbException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbOperationFailurePolicyTest {
    @Test
    fun callerCancellationDoesNotRetireSharedTransport() {
        assertFalse(shouldRetireAdbClient(CancellationException("caller cancelled"), clientClosed = false))
        assertFalse(shouldRetireAdbClient(CancellationException("disconnect"), clientClosed = true))
    }

    @Test
    fun localIoFailureDoesNotRetireHealthyTransport() {
        assertFalse(shouldRetireAdbClient(IOException("destination storage full"), clientClosed = false))
    }

    @Test
    fun remoteOperationAndProtocolFailuresDoNotRetireHealthyTransport() {
        assertFalse(shouldRetireAdbClient(AdbException.RemoteFailure("package not found"), clientClosed = false))
        assertFalse(shouldRetireAdbClient(AdbException.Protocol("invalid response"), clientClosed = false))
    }

    @Test
    fun transportFailuresRetireClient() {
        assertTrue(shouldRetireAdbClient(AdbException.Transport("socket failed"), clientClosed = false))
        assertTrue(shouldRetireAdbClient(AdbException.Io("transport io failed"), clientClosed = false))
        assertTrue(shouldRetireAdbClient(AdbException.StreamClosed(), clientClosed = false))
    }

    @Test
    fun closedClientIsRetiredAfterNonCancellationFailure() {
        assertTrue(shouldRetireAdbClient(IllegalStateException("operation failed"), clientClosed = true))
    }
}
