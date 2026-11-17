package com.waizusora.wdb.adb

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveClientRegistryTest {
    @Test
    fun replacingClientReturnsPreviousOwner() {
        val registry = ActiveClientRegistry<Any>()
        val first = Any()
        val second = Any()

        assertSame(null, registry.replace(first))
        assertSame(first, registry.replace(second))
        assertSame(second, registry.current())
    }

    @Test
    fun staleClientCannotClearReplacement() {
        val registry = ActiveClientRegistry<Any>()
        val oldClient = Any()
        val newClient = Any()
        registry.replace(oldClient)

        val cleanupStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        var oldCleanupClearedActive = true
        val cleanup = Thread {
            cleanupStarted.countDown()
            allowCleanup.await()
            oldCleanupClearedActive = registry.clearIfCurrent(oldClient)
        }

        cleanup.start()
        cleanupStarted.await()
        registry.replace(newClient)
        allowCleanup.countDown()
        cleanup.join()

        assertFalse(oldCleanupClearedActive)
        assertTrue(registry.isCurrent(newClient))
        assertSame(newClient, registry.current())
    }

    @Test
    fun callerCancellationDoesNotRetireHealthyClient() {
        assertFalse(shouldRetireAdbClient(CancellationException("caller cancelled"), clientClosed = false))
    }

    @Test
    fun closedClientIsRetiredWhenCancellationRaces() {
        assertTrue(shouldRetireAdbClient(CancellationException("transport closed"), clientClosed = true))
    }

    @Test
    fun onlyCurrentClientCanClearRegistry() {
        val registry = ActiveClientRegistry<Any>()
        val oldClient = Any()
        val currentClient = Any()
        registry.replace(oldClient)
        registry.replace(currentClient)

        assertFalse(registry.clearIfCurrent(oldClient))
        assertTrue(registry.clearIfCurrent(currentClient))
        assertSame(null, registry.current())
    }
}
