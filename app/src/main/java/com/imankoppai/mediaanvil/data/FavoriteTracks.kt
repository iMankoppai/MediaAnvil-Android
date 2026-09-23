package com.imankoppai.mediaanvil.data

/**
 * Ordered favourites. Newly favourited tracks go to the end so the list keeps the
 * order the user built, and playing a favourite always uses that saved order.
 */
object FavoriteTracks {
    fun toggle(favorites: List<String>, uri: String): List<String> {
        if (uri.isBlank()) return favorites
        return if (uri in favorites) favorites - uri else favorites + uri
    }

    fun isFavorite(favorites: List<String>, uri: String): Boolean = uri in favorites

    /** Only the favourites that are still on the device, in the saved order. */
    fun availableUris(favorites: List<String>, available: Set<String>): List<String> =
        favorites.asSequence().filter { it in available }.distinct().toList()
}
