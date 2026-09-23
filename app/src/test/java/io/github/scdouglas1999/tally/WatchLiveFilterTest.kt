package io.github.scdouglas1999.tally

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import io.github.scdouglas1999.tally.api.TallyBoard
import io.github.scdouglas1999.tally.api.TallyChannel
import io.github.scdouglas1999.tally.api.TallyGame
import io.github.scdouglas1999.tally.ui.home.pregameChannelIds
import io.github.scdouglas1999.tally.ui.home.withoutPregameChannels
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WatchLiveFilterTest {
    private val pregameChannel = UUID.fromString("1fcfacdb-c1fa-4309-f9ac-372a218380ed")
    private val liveChannel = UUID.fromString("2fcfacdb-c1fa-4309-f9ac-372a218380ed")
    private val newsChannel = UUID.fromString("3fcfacdb-c1fa-4309-f9ac-372a218380ed")

    private val board =
        TallyBoard(
            games =
                listOf(
                    TallyGame(id = "later", state = "pre"),
                    TallyGame(id = "now", state = "in"),
                ),
            channels =
                listOf(
                    TallyChannel(id = "a", gameId = "later", liveTvItemId = "1fcfacdbc1fa4309f9ac372a218380ed"),
                    TallyChannel(id = "b", gameId = "now", liveTvItemId = "2FCFACDBC1FA4309F9AC372A218380ED"),
                    TallyChannel(id = "c", gameId = null, liveTvItemId = "3fcfacdbc1fa4309f9ac372a218380ed"),
                    TallyChannel(id = "d", gameId = "later", liveTvItemId = null),
                ),
        )

    private fun program(channel: UUID) = BaseItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.PROGRAM, channelId = channel))

    private fun row(
        config: HomeRowConfig,
        vararg channels: UUID,
    ) = HomeRowLoadingState.Success(
        title = ResStringProvider(0),
        items = channels.map { program(it) },
        rowType = config,
    )

    @Test
    fun `only channels carrying a game that has not started are pre-game`() {
        assertEquals(setOf("1fcfacdbc1fa4309f9ac372a218380ed"), pregameChannelIds(board))
        assertTrue(pregameChannelIds(null).isEmpty())
    }

    @Test
    fun `watch live keeps live games and channels without a game`() {
        val rows = listOf(row(HomeRowConfig.TvPrograms(), pregameChannel, liveChannel, newsChannel))
        val shown = rows.withoutPregameChannels(pregameChannelIds(board)).single() as HomeRowLoadingState.Success
        assertEquals(listOf(liveChannel, newsChannel), shown.items.map { it?.data?.channelId })
    }

    @Test
    fun `a watch live row of only pre-game channels ends up empty, so it is not drawn`() {
        val rows = listOf(row(HomeRowConfig.TvPrograms(), pregameChannel))
        val shown = rows.withoutPregameChannels(pregameChannelIds(board)).single() as HomeRowLoadingState.Success
        assertTrue(shown.items.isEmpty())
    }
}
