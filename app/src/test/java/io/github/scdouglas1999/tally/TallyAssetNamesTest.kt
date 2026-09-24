package io.github.scdouglas1999.tally

import com.github.damontecres.wholphin.services.getDownloadUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** The in-app updater picks Tally's own asset names first and falls back to Wholphin's. */
class TallyAssetNamesTest {
    private fun assets(vararg names: String): JsonArray =
        Json
            .parseToJsonElement(
                names.joinToString(",", "[", "]") { """{"name":"$it","browser_download_url":"https://example.com/$it"}""" },
            ).jsonArray

    private val both =
        assets(
            "Tally.apk",
            "Tally-arm64-v8a.apk",
            "Tally-armeabi-v7a.apk",
            "Tally-x86_64.apk",
            "Wholphin-release.apk",
            "Wholphin-release-arm64-v8a.apk",
            "Wholphin-release-armeabi-v7a.apk",
            "Wholphin-release-x86_64.apk",
        )

    @Test
    fun `a TV picks the Tally build for its chip`() {
        assertEquals("https://example.com/Tally-arm64-v8a.apk", getDownloadUrl(both, false, listOf("arm64-v8a")))
        assertEquals("https://example.com/Tally-armeabi-v7a.apk", getDownloadUrl(both, false, listOf("armeabi-v7a")))
    }

    @Test
    fun `an unknown chip gets the universal Tally apk`() {
        assertEquals("https://example.com/Tally.apk", getDownloadUrl(both, false, listOf("mips")))
    }

    @Test
    fun `a release with only Wholphin names still updates`() {
        val old = assets("Wholphin-release.apk", "Wholphin-release-arm64-v8a.apk")
        assertEquals("https://example.com/Wholphin-release-arm64-v8a.apk", getDownloadUrl(old, false, listOf("arm64-v8a")))
    }
}
