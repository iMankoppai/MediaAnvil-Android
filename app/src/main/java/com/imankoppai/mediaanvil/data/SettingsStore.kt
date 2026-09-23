package com.imankoppai.mediaanvil.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList

private const val DATA_STORE_NAME = "mediaanvil_settings"

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = DATA_STORE_NAME)

/** Writes [entries] into a DataStore transaction, removing keys mapped to null. */
private fun MutablePreferences.writeEntries(entries: Map<String, Any?>) {
    entries.forEach { (name, value) ->
        when (value) {
            null -> asMap().keys
                .firstOrNull { it.name == name }
                ?.let { remove(it) }

            is String -> set(stringPreferencesKey(name), value)
            is Boolean -> set(booleanPreferencesKey(name), value)
            is Int -> set(intPreferencesKey(name), value)
            is Long -> set(longPreferencesKey(name), value)
            is Float -> set(floatPreferencesKey(name), value)
            is Double -> set(doublePreferencesKey(name), value)
            is Collection<*> ->
                set(stringSetPreferencesKey(name), value.filterIsInstance<String>().toSet())

            else -> Unit
        }
    }
}

/**
 * Durable settings storage built on Preferences DataStore.
 *
 * DataStore replaces SharedPreferences because it writes asynchronously without
 * rewriting the whole file, detects corruption instead of silently returning
 * defaults, and has no `commit()` that blocks whichever thread happens to call it.
 *
 * Reads stay synchronous because every caller ([PlaybackPreferences], the playback
 * service's notification buttons, the theme controller at startup) expects a plain
 * value. That is achieved with an in-memory snapshot loaded exactly once:
 * the first construction performs a single blocking read, which is the same
 * one-time disk cost `getSharedPreferences()` already paid on first use, and every
 * read afterwards is a map lookup. Writes update the snapshot immediately and are
 * persisted asynchronously, except for the few callers that ask for a synchronous
 * write because the process may die right after it.
 *
 * The process-wide instance holds the [DataStore] and the snapshot — never a
 * `Context` — so the singleton cannot leak one.
 */
internal class SettingsStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val values: MutableMap<String, Any?>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * Writes that have been accepted but not necessarily flushed yet. SharedPreferences
     * `apply()` was flushed by the framework at process shutdown; DataStore's async
     * writes need their own handle so a caller that must be durable can wait.
     */
    private val pendingWrites = CopyOnWriteArrayList<Job>()

    fun getString(name: String, fallback: String?): String? =
        synchronized(values) { values[name] as? String } ?: fallback

    fun getBoolean(name: String, fallback: Boolean): Boolean =
        synchronized(values) { values[name] as? Boolean } ?: fallback

    fun getInt(name: String, fallback: Int): Int =
        synchronized(values) { values[name] as? Int } ?: fallback

    fun getLong(name: String, fallback: Long): Long =
        synchronized(values) { values[name] as? Long } ?: fallback

    fun getFloat(name: String, fallback: Float): Float =
        synchronized(values) { values[name] as? Float } ?: fallback

    fun getStringSet(name: String, fallback: Set<String>): Set<String>? =
        synchronized(values) {
            when (val value = values[name]) {
                is Collection<*> -> value.filterIsInstance<String>().toSet()
                else -> null
            }
        } ?: fallback

    /**
     * Stores one value. [synchronous] blocks until the write is on disk and is
     * reserved for the callers that must not lose the value if the process dies
     * immediately afterwards.
     */
    fun put(name: String, value: Any?, synchronous: Boolean = false) =
        putAll(mapOf(name to value), synchronous)

    /** Stores several values in one DataStore transaction. */
    fun putAll(entries: Map<String, Any?>, synchronous: Boolean = false) {
        if (entries.isEmpty()) return
        synchronized(values) { values.putAll(entries) }
        entries.keys.forEach(::notifyListeners)
        val persist: suspend () -> Unit = { dataStore.edit { it.writeEntries(entries) } }
        if (synchronous) {
            runCatching { runBlocking { persist() } }
        } else {
            val job = scope.launch { runCatching { persist() } }
            pendingWrites += job
            job.invokeOnCompletion { pendingWrites -= job }
        }
    }

    /**
     * Blocks until every accepted write has reached disk. Used when the process is
     * about to stop mattering — the playback service being destroyed, or a test
     * asserting durability — so an in-flight write is not silently dropped.
     */
    fun flush() {
        pendingWrites.toList().forEach { job -> runCatching { runBlocking { job.join() } } }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    /** Same-process notification, matching what SharedPreferences listeners did. */
    private fun notifyListeners(name: String) {
        listeners.forEach { listener -> runCatching { listener(name) } }
    }

    companion object {
        @Volatile
        private var instance: SettingsStore? = null

        /** One store per process, so the snapshot is shared and loaded only once. */
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        /**
         * Drops the cached store so the next [get] re-reads from disk and re-runs the
         * migration check. Instrumented tests use this to stand in for an app restart,
         * which is the only way to prove the migration is idempotent in-process.
         */
        @androidx.annotation.VisibleForTesting
        internal fun resetForTests() {
            synchronized(this) { instance = null }
        }

        /** Path of the DataStore file, so tests can start from a clean slate. */
        @androidx.annotation.VisibleForTesting
        internal fun dataStoreFile(context: Context): java.io.File =
            java.io.File(java.io.File(context.filesDir, "datastore"), "$DATA_STORE_NAME.preferences_pb")

        /** Reads the DataStore once and runs the legacy migration if it has not run
         * yet. A failure to read falls back to an empty snapshot rather than crashing
         * the app at startup; the next write recreates the file. */
        private fun create(context: Context): SettingsStore {
            val dataStore = context.settingsDataStore
            val snapshot = LinkedHashMap<String, Any?>()
            runCatching { runBlocking { dataStore.data.first() } }
                .getOrNull()
                ?.asMap()
                ?.forEach { (key, value) -> snapshot[key.name] = value }

            val legacy: Map<String, Any?> = runCatching {
                context.getSharedPreferences(
                    SettingsMigration.LEGACY_PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ).all
            }.getOrDefault(emptyMap())

            SettingsMigration.plan(snapshot, legacy)?.let { plan ->
                runCatching {
                    runBlocking { dataStore.edit { it.writeEntries(plan) } }
                }
                snapshot.putAll(plan)
            }
            return SettingsStore(dataStore, snapshot)
        }
    }
}
