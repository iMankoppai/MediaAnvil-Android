package com.imankoppai.mediaanvil.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Writes must reach the DataStore in the order their callers issued them.
 *
 * This is checked here rather than on a device because it is a *concurrency* property:
 * the failure needs two writes to be in flight at once and to finish out of order, so a
 * device test only catches it when the scheduler happens to cooperate. CI did catch it
 * once — a restarted play history of `[uri-1]` after playing uri-1, uri-2, uri-1, i.e.
 * the write holding the *shortest* list landing last and destroying the record of the
 * later plays — but six further attempts on a phone never reproduced it. A test that
 * fails only sometimes is not a guard, so the DataStore below makes the interleaving
 * happen on purpose.
 */
class SettingsStoreWriteOrderTest {

    /**
     * A DataStore whose first write is slow to apply.
     *
     * Every write captures the values it was given, so the write that applies *last*
     * decides the final state. Making the first-issued write the slowest therefore
     * means that if writes are allowed to overlap, the oldest value wins — exactly the
     * corruption CI observed. With writes properly ordered, the first write finishes
     * before the next one starts and the newest value survives.
     *
     * A delay is used rather than a gate between the two writes because a gate cannot
     * distinguish the two cases: it would deadlock the correct implementation, which
     * never starts the second write until the first has finished.
     */
    private class SlowFirstWriteDataStore(
        private val firstWriteDelayMs: Long = 150,
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private var issued = 0

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val index = synchronized(this) { issued++ }
            if (index == 0) delay(firstWriteDelayMs)
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private fun storedValue(dataStore: DataStore<Preferences>, name: String): Any? =
        runBlocking { dataStore.data.first() }
            .asMap()
            .entries
            .firstOrNull { it.key.name == name }
            ?.value

    @Test
    fun aLaterWriteIsNotOverwrittenByAnEarlierOneThatFinishesAfterIt() {
        val dataStore = SlowFirstWriteDataStore()
        val store = SettingsStore.forTests(dataStore)

        // Two writes to the same key, issued in order: "second" must win.
        store.put("play_history", "first")
        store.put("play_history", "second")
        store.flush()

        assertEquals(
            "the earlier write landed last and undid the later one",
            "second",
            storedValue(dataStore, "play_history"),
        )
        assertEquals("second", store.getString("play_history", null))
    }

    @Test
    fun manySuccessiveWritesLeaveTheNewestValueStored() {
        val dataStore = SlowFirstWriteDataStore()
        val store = SettingsStore.forTests(dataStore)

        repeat(50) { index -> store.put("play_history", "value-$index") }
        store.flush()

        assertEquals("value-49", storedValue(dataStore, "play_history"))
    }

    @Test
    fun aSynchronousWriteDoesNotJumpAheadOfAnAlreadyQueuedWrite() {
        val dataStore = SlowFirstWriteDataStore()
        val store = SettingsStore.forTests(dataStore)

        store.put("k", "async")
        // The synchronous write is issued second, so it must also land second even
        // though it blocks its caller and the async one does not.
        store.put("k", "sync", synchronous = true)

        assertEquals("sync", storedValue(dataStore, "k"))
    }
}
