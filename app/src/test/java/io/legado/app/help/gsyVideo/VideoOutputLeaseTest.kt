package io.legado.app.help.video

import org.junit.Assert.*
import org.junit.Test

class VideoOutputLeaseTest {
    @Test fun outputCanBeAttachedBeforePlayerCreationAndReusedByNewPlayer() {
        val owner = Any(); val surface = Any(); val lease = VideoOutputLease<Any>()
        lease.attach(owner, surface)
        assertSame(owner, lease.owner)
        assertSame(surface, lease.output)
        assertTrue(lease.detach(owner, surface))
    }

    @Test fun outgoingActivityCannotReleaseOverlay() {
        val activity = Any(); val service = Any(); val old = Any(); val next = Any()
        val lease = VideoOutputLease<Any>()
        lease.attach(activity, old)
        lease.attach(service, next)
        assertFalse(lease.detach(activity, old))
        assertFalse(lease.release(activity))
        assertSame(service, lease.owner)
        assertSame(next, lease.output)
    }

    @Test fun recreatedTextureIgnoresLateDestroyInSameWindow() {
        val activity = Any(); val old = Any(); val next = Any()
        val lease = VideoOutputLease<Any>()
        lease.attach(activity, old); lease.attach(activity, next)
        assertFalse(lease.detach(activity, old))
        assertTrue(lease.detach(activity, next))
        assertSame(activity, lease.owner)
        assertNull(lease.output)
    }

    @Test fun cancelledTransferLeavesOriginalOwnerUsable() {
        val activity = Any(); val failedService = Any(); val output = Any()
        val lease = VideoOutputLease<Any>()
        lease.attach(activity, output)
        assertFalse(lease.release(failedService))
        assertSame(output, lease.output)
        assertTrue(lease.release(activity))
        assertFalse(lease.release(activity))
        assertNull(lease.owner)
    }

    @Test fun returningFromOverlayRejectsLateServiceCallbacks() {
        val activity = Any(); val service = Any(); val a = Any(); val s = Any()
        val lease = VideoOutputLease<Any>()
        repeat(5) {
            lease.attach(service, s); lease.attach(activity, a)
            assertFalse(lease.detach(service, s))
            assertFalse(lease.release(service))
            assertSame(a, lease.output)
        }
    }
}
