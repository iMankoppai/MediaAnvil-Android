package com.imankoppai.mediaanvil.data

/**
 * Plans the one-time move from the legacy `mediaanvil_playback` SharedPreferences
 * file into the DataStore that now backs [PlaybackPreferences].
 *
 * The plan is a pure function of two maps so it can be unit-tested without an
 * Android context, and so the migration can never depend on partial state. It is
 * deliberately conservative:
 *
 * - a version marker written *into the DataStore itself* makes it idempotent, so a
 *   crash or a second launch can never migrate twice;
 * - a value already present in the DataStore always wins, so a live edit made
 *   between the DataStore write and the legacy read is never rolled back;
 * - the legacy SharedPreferences file is only ever read, never deleted, so a
 *   failed migration leaves the original data recoverable.
 */
internal object SettingsMigration {
    /** Written into the DataStore once the legacy values have been copied over. */
    const val MARKER_KEY = "settings_store_version"

    const val CURRENT_VERSION = 1

    /** Name of the SharedPreferences file written by V1.02 and earlier. */
    const val LEGACY_PREFERENCES_NAME = "mediaanvil_playback"

    /**
     * Returns the entries that still have to be written to the DataStore, or null
     * when the migration has already run and there is nothing left to do.
     */
    fun plan(
        existing: Map<String, Any?>,
        legacy: Map<String, Any?>,
    ): Map<String, Any?>? {
        if (existing[MARKER_KEY] == CURRENT_VERSION) return null

        val migrated = LinkedHashMap<String, Any?>()
        legacy.forEach { (key, value) ->
            // The marker is ours to write, never a legacy value to copy.
            if (key == MARKER_KEY) return@forEach
            if (value == null) return@forEach
            // Never overwrite a value the app already wrote to the DataStore.
            if (existing.containsKey(key)) return@forEach
            migrated[key] = value
        }
        // The marker is written even when there was nothing to copy, so an install
        // with no legacy file does not re-read SharedPreferences on every launch.
        migrated[MARKER_KEY] = CURRENT_VERSION
        return migrated
    }
}
