package io.github.scdouglas1999.tally

import io.github.scdouglas1999.tally.media.library.albumCoverFallback
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.ImageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The song card's picture falls back to the album cover, against payloads captured from the dev server:
 * `songs-list.json` (the Music library's 10 songs, `/Items?includeItemTypes=Audio`) and `movies-provider-ids.json`.
 */
class SongCoverTest {
    @Test
    fun `a song without a picture of its own shows its album's cover`() {
        assertTrue(songs.isNotEmpty())
        songs.forEach { song ->
            assertTrue(song.imageTags.orEmpty().isEmpty())
            val cover = albumCoverFallback(song, ImageType.PRIMARY)
            assertEquals(song.albumId, cover?.first)
            assertEquals(song.albumPrimaryImageTag, cover?.second)
        }
    }

    @Test
    fun `the songs of one album share one cover`() {
        val covers = songs.groupBy { it.album }.mapValues { (_, list) -> list.map { albumCoverFallback(it, ImageType.PRIMARY) }.toSet() }
        assertEquals(2, covers.size)
        covers.values.forEach { assertEquals(1, it.size) }
    }

    @Test
    fun `films keep their own picture`() {
        movies.forEach { assertNull(albumCoverFallback(it, ImageType.PRIMARY)) }
    }

    @Test
    fun `a song that has the picture type asked for keeps its own`() {
        val song = songs.first()
        val withPrimary = song.copy(imageTags = mapOf(ImageType.PRIMARY to "tag"))
        assertNull(albumCoverFallback(withPrimary, ImageType.PRIMARY))
        // Asked for another type it does not have, the album cover still stands in.
        assertEquals(song.albumId, albumCoverFallback(withPrimary, ImageType.THUMB)?.first)
    }

    private companion object {
        fun load(name: String): List<BaseItemDto> {
            val text =
                SongCoverTest::class.java
                    .getResource("/tally/$name")!!
                    .readText()
            return ApiSerializer.json.decodeFromString<BaseItemDtoQueryResult>(text).items
        }

        val songs by lazy { load("songs-list.json") }
        val movies by lazy { load("movies-provider-ids.json") }
    }
}
