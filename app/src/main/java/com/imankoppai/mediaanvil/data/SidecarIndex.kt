package com.imankoppai.mediaanvil.data

/** Keep the existing double-extension and provider .txt naming precedence. */
internal object SidecarIndex {
    data class Audio<K>(val key: K, val folder: String, val fileName: String)

    fun <K, T> matchAll(audio: List<Audio<K>>, readFolder: (String) -> Map<String, T>): Map<K, Pair<T, String>> = buildMap {
        audio.groupBy { it.folder.trim('/') }.forEach { (folder, tracks) ->
            val files = readFolder(folder)
            tracks.forEach { track -> find(track.fileName, files)?.let { put(track.key, it) } }
        }
    }

    fun <T> find(audioName: String, files: Map<String, T>): Pair<T, String>? {
        val base = audioName.substringBeforeLast('.', audioName)
        listOf("lrc", "srt", "vtt").forEach { extension ->
            listOf("$audioName.$extension", "$base.$extension", "$audioName.$extension.txt", "$base.$extension.txt").forEach { name ->
                files[name]?.let { return it to extension }
            }
        }
        return null
    }
}
