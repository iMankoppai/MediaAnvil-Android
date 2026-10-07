package com.imankoppai.mediaanvil.data

/** Portable identity. A MediaStore row id alone is never an identity across devices. */
data class BackupTrackReference(
    val uri: String,
    val fileName: String,
    val relativeFolder: String,
    val durationMs: Long,
    val sizeBytes: Long = 0L,
)

object BackupTrackMatcher {
    data class Match(val source: BackupTrackReference, val candidates: List<BackupTrackReference>) {
        val automaticUri: String? get() = candidates.singleOrNull()?.uri
    }

    fun match(source: BackupTrackReference, available: List<BackupTrackReference>, allowLegacyUri: Boolean = false): Match {
        return Index(available).match(source, allowLegacyUri)
    }

    class Index(available: List<BackupTrackReference>) {
        private val byName = available.groupBy { it.fileName }
        private val byUri = available.groupBy { it.uri }

        fun match(source: BackupTrackReference, allowLegacyUri: Boolean = false): Match {
            // Older backups have no metadata. Preserve only an existing exact URI.
            if (source.fileName.isEmpty()) return Match(source, if (allowLegacyUri) byUri[source.uri].orEmpty() else emptyList())
            val compatible = byName[source.fileName].orEmpty().filter {
                    (source.sizeBytes <= 0 || it.sizeBytes <= 0 || source.sizeBytes == it.sizeBytes) &&
                    (source.durationMs <= 0 || it.durationMs <= 0 ||
                        kotlin.math.abs(source.durationMs - it.durationMs) <= 1_000L)
            }
            val sameFolder = compatible.filter { it.relativeFolder.trim('/') == source.relativeFolder.trim('/') }
            // Never break ties using a row id: another phone can reuse the same id.
            return Match(source, sameFolder.ifEmpty { compatible })
        }
    }

    /** Retain missing records without letting an old row id refer to a different file. */
    fun missingUri(uri: String): String = if (uri.startsWith("mediaanvil-missing:")) uri else
        "mediaanvil-missing:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(uri.toByteArray(Charsets.UTF_8))
}
