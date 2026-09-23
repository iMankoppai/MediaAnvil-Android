package com.imankoppai.mediaanvil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteTracksTest {
    @Test
    fun `toggling adds then removes a track`() {
        val added = FavoriteTracks.toggle(emptyList(), "a")
        assertEquals(listOf("a"), added)
        assertTrue(FavoriteTracks.isFavorite(added, "a"))

        val removed = FavoriteTracks.toggle(added, "a")
        assertTrue(removed.isEmpty())
        assertFalse(FavoriteTracks.isFavorite(removed, "a"))
    }

    @Test
    fun `favorites keep the order the user added them in`() {
        var favorites = emptyList<String>()
        listOf("c", "a", "b").forEach { favorites = FavoriteTracks.toggle(favorites, it) }
        assertEquals(listOf("c", "a", "b"), favorites)
    }

    @Test
    fun `a blank uri is ignored`() {
        assertTrue(FavoriteTracks.toggle(emptyList(), "").isEmpty())
    }

    @Test
    fun `availableUris keeps saved order and skips missing audio`() {
        val favorites = listOf("b", "gone", "a")
        assertEquals(listOf("b", "a"), FavoriteTracks.availableUris(favorites, setOf("a", "b")))
    }
}
