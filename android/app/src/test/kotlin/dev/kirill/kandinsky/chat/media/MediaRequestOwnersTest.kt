package dev.kirill.kandinsky.chat.media

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MediaRequestOwnersTest {
    @Test
    fun aNewOwnerRacingWithLastCancellationIsEitherKeptAliveOrRefused() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val owners = MediaRequestOwners()
                val first = UUID.randomUUID()
                val next = UUID.randomUUID()
                val cancellations = AtomicInteger()
                owners.cancellation.onCancel { cancellations.incrementAndGet() }
                assertTrue(owners.insert(first))
                val start = CountDownLatch(1)
                val remove = workers.submit { start.await(); owners.remove(first) }
                val insert = workers.submit<Boolean> { start.await(); owners.insert(next) }
                start.countDown()
                remove.get(1, TimeUnit.SECONDS)
                val accepted = insert.get(1, TimeUnit.SECONDS)
                assertEquals("An accepted live owner cannot lose its transfer",
                    !accepted, owners.cancellation.isCancelled)
                if (accepted) owners.remove(next)
                assertTrue(owners.isClosed)
                assertTrue(owners.cancellation.isCancelled)
                assertFalse(owners.insert(UUID.randomUUID()))
                owners.remove(first)
                owners.close()
                assertEquals("Retirement must cancel the transfer once", 1, cancellations.get())
            }
        } finally { workers.shutdownNow() }
    }
}
