package com.imankoppai.mediaanvil.model

/**
 * A local playlist-like group. Audio files remain in their original folders.
 *
 * [trackUris] is an ordered list, not a set: the order the user adds tracks in is
 * the order the group plays them back in.
 */
data class TrackGroup(
    val id: String,
    val name: String,
    val trackUris: List<String>,
)
