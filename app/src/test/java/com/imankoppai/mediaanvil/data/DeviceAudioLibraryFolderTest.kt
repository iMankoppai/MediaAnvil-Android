package com.imankoppai.mediaanvil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAudioLibraryFolderTest {
    @Test
    fun `media library relative paths keep a trailing slash`() {
        assertEquals("Music/", DeviceAudioLibrary.normalizeRelativeFolder("Music"))
        assertEquals("Music/sub/", DeviceAudioLibrary.normalizeRelativeFolder("Music/sub"))
        assertEquals("Music/", DeviceAudioLibrary.normalizeRelativeFolder("Music/"))
        assertEquals("Music/", DeviceAudioLibrary.normalizeRelativeFolder("/Music/"))
    }

    @Test
    fun `the storage root maps to an empty relative folder`() {
        assertEquals("", DeviceAudioLibrary.normalizeRelativeFolder(""))
        assertEquals("", DeviceAudioLibrary.normalizeRelativeFolder("/"))
    }

    @Test
    fun `legacy absolute paths convert to media library relative folders`() {
        val root = "/storage/emulated/0"
        assertEquals(
            "Music/",
            DeviceAudioLibrary.relativeFolderFromAbsolute("$root/Music/song.mp3", root),
        )
        assertEquals(
            "Music/sub/",
            DeviceAudioLibrary.relativeFolderFromAbsolute("$root/Music/sub/song.mp3", root),
        )
        // A removable volume keeps its own prefix so it cannot collide with primary.
        assertEquals(
            "34FE-19F0/Music/",
            DeviceAudioLibrary.relativeFolderFromAbsolute("/storage/34FE-19F0/Music/song.mp3", root),
        )
        assertEquals("", DeviceAudioLibrary.relativeFolderFromAbsolute("", root))
    }

    @Test
    fun `empty folder set allows everything`() {
        assertTrue(DeviceAudioLibrary.isFolderAllowed("Recordings/", emptySet()))
        assertTrue(DeviceAudioLibrary.isFolderAllowed("", emptySet()))
    }

    @Test
    fun `selected folders include their subfolders`() {
        val folders = setOf("Music/")
        assertTrue(DeviceAudioLibrary.isFolderAllowed("Music/", folders))
        assertTrue(DeviceAudioLibrary.isFolderAllowed("Music/sub/", folders))
        assertFalse(DeviceAudioLibrary.isFolderAllowed("Recordings/", folders))
        assertFalse(DeviceAudioLibrary.isFolderAllowed("", folders))
    }

    @Test
    fun `tree document ids convert to relative folders`() {
        assertEquals("Music/sub/", DeviceAudioLibrary.treeDocumentIdToRelativeFolder("primary:Music/sub"))
        assertEquals("Music/", DeviceAudioLibrary.treeDocumentIdToRelativeFolder("primary:Music"))
        assertEquals("34FE-19F0/Music/", DeviceAudioLibrary.treeDocumentIdToRelativeFolder("34FE-19F0:Music"))
        assertEquals(null, DeviceAudioLibrary.treeDocumentIdToRelativeFolder("primary:"))
    }

    @Test
    fun `similarly named folders do not match by prefix`() {
        val folders = setOf("Music/")
        assertFalse(DeviceAudioLibrary.isFolderAllowed("MusicVideos/", folders))
        assertTrue(DeviceAudioLibrary.isFolderAllowed("MusicVideos/", setOf("MusicVideos/")))
    }

    @Test
    fun `the audio read permission matches the running Android version`() {
        // Build.VERSION is populated in unit tests from the compile SDK, so this
        // asserts the branch logic rather than the exact runtime value.
        val expected = if (android.os.Build.VERSION.SDK_INT >= 33) {
            "android.permission.READ_MEDIA_AUDIO"
        } else {
            "android.permission.READ_EXTERNAL_STORAGE"
        }
        assertEquals(expected, DeviceAudioLibrary.readPermission)
    }
}
