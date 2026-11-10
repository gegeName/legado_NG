package io.legado.app.integration.source

import io.legado.features.api.source.InstallProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class NgExtensionDownloadsTest {
    private suspend fun await(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) yield() }

    @Test fun parallelProgressAndCancellationAreIndependent() = runBlocking {
        val first = Channel<InstallProgress>(Channel.UNLIMITED)
        val second = Channel<InstallProgress>(Channel.UNLIMITED)
        val controller = NgExtensionDownloads(this) { if (it == "first") first.receiveAsFlow() else second.receiveAsFlow() }
        controller.start("pkg.first", "first")
        controller.start("pkg.second", "second")
        first.send(InstallProgress("downloading", 18))
        second.send(InstallProgress("downloading", 72))
        try {
            await { controller.states.value["pkg.first"]?.progress?.percent == 18 && controller.states.value["pkg.second"]?.progress?.percent == 72 }
            controller.cancel("pkg.first")
            await { controller.states.value["pkg.first"]?.running == false }
            assertEquals("cancelled", controller.states.value["pkg.first"]?.progress?.state)
            assertTrue(controller.states.value.getValue("pkg.second").running)
            second.send(InstallProgress("downloading", 95))
            await { controller.states.value["pkg.second"]?.progress?.percent == 95 }
            second.send(InstallProgress("installed"))
            second.close()
            await { controller.states.value["pkg.second"]?.running == false }
            assertEquals("installed", controller.states.value["pkg.second"]?.progress?.state)
        } finally { controller.cancel("pkg.first"); controller.cancel("pkg.second") }
    }

    @Test fun duplicateClicksAndChangedListingIdsCannotStartTwoJobsForOnePackage() = runBlocking {
        var calls = 0
        val gate = Channel<InstallProgress>(Channel.UNLIMITED)
        val controller = NgExtensionDownloads(this) { calls++; gate.receiveAsFlow() }
        controller.start("pkg.first", "available-id")
        controller.start("pkg.first", "installed-id")
        try {
            await { calls == 1 }
            assertEquals(1, controller.states.value.size)
            gate.send(InstallProgress("installed")); gate.close()
            await { controller.states.value["pkg.first"]?.running == false }
            assertEquals(1, calls)
        } finally { controller.cancel("pkg.first") }
    }

    @Test fun failuresStayOnTheirRowAndCanBeRetried() = runBlocking {
        var attempts = 0
        val controller = NgExtensionDownloads(this) {
            flow {
                attempts++
                if (attempts == 1) throw IllegalStateException("fixture HTTP failure")
                emit(InstallProgress("installed"))
            }
        }
        controller.start("pkg.first", "first")
        await { controller.states.value["pkg.first"]?.running == false }
        assertEquals("fixture HTTP failure", controller.states.value["pkg.first"]?.progress?.error)
        controller.start("pkg.first", "first")
        await { controller.states.value["pkg.first"]?.running == false }
        assertEquals(2, attempts)
        assertEquals("installed", controller.states.value["pkg.first"]?.progress?.state)
    }

    @Test fun endingWithoutInstallResultIsAnError() = runBlocking {
        val controller = NgExtensionDownloads(this) { emptyFlow() }
        controller.start("pkg.first", "first")
        await { controller.states.value["pkg.first"]?.running == false }
        assertEquals("error", controller.states.value["pkg.first"]?.progress?.state)
    }

    @Test fun immediateCancellationBeforeCollectionDoesNotBlockRetry() = runBlocking {
        val controller = NgExtensionDownloads(this) { flowOf(InstallProgress("installed")) }
        controller.start("pkg.first", "first")
        controller.cancel("pkg.first")
        await { controller.states.value["pkg.first"]?.running == false }
        assertEquals("cancelled", controller.states.value["pkg.first"]?.progress?.state)
        controller.start("pkg.first", "first")
        await { controller.states.value["pkg.first"]?.running == false }
        assertEquals("installed", controller.states.value["pkg.first"]?.progress?.state)
    }
}
