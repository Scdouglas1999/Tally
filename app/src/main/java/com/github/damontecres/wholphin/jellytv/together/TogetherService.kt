package com.github.damontecres.wholphin.jellytv.together

import android.os.SystemClock
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PlayerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.client.extensions.timeSyncApi
import org.jellyfin.sdk.api.sockets.SocketConnection
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.PingRequestDto
import org.jellyfin.sdk.model.api.PlayRequestDto
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateCommandMessage
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watch Together: this TV as a Jellyfin SyncPlay member. Process-wide (a party survives navigating between
 * screens); it attaches to whatever upstream player `PlayerFactory.currentPlayer` holds and keeps it in step
 * with the group by observing it — no upstream player code is wrapped or replaced. See `TogetherModels.kt` for
 * the protocol as observed and `SyncPolicy` for the correction rules.
 *
 * Group-update payloads are read from the raw socket frame. The SDK's `GroupUpdate` type drops `Data`
 * (see [TogetherFrames]); subscribing still keeps this session on the socket, which is what the server fans
 * commands out on.
 */
@Singleton
class TogetherService
    @Inject
    constructor(
        private val api: ApiClient,
        private val playerFactory: PlayerFactory,
        private val navigationManager: NavigationManager,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val clock = ServerClock()
        private val sync =
            TogetherPlayerSync(
                api = api,
                playerFactory = playerFactory,
                navigationManager = navigationManager,
                clock = clock,
                scope = scope,
                onNotice = ::emit,
                onLeftPlayback = { scope.launch { leave() } },
            )

        private val _state = MutableStateFlow<TogetherState>(TogetherState.Idle)
        val state: StateFlow<TogetherState> = _state.asStateFlow()

        private val _notices = MutableSharedFlow<TogetherNotice>(extraBufferCapacity = 16)
        val notices: SharedFlow<TogetherNotice> = _notices.asSharedFlow()

        private val incoming = Channel<String>(Channel.UNLIMITED)
        private var incomingJob: Job? = null
        private var tapWatch: Job? = null
        private var groupJob: Job? = null
        private var commandJob: Job? = null
        private var clockJob: Job? = null
        private var waitingJob: Job? = null
        private var listening = false
        private var queueOnJoin: Pair<UUID, Long>? = null
        private var tappedSocket: Any? = null
        private val seenIds = ArrayDeque<UUID>()
        private val seen = HashSet<UUID>()

        /** Groups on the server this user can join. Empty on any error. */
        suspend fun groups(): List<TogetherGroupSummary> =
            try {
                withContext(Dispatchers.IO) { api.syncPlayApi.syncPlayGetGroups().content }
                    .map { dto ->
                        TogetherGroupSummary(
                            id = dto.groupId,
                            name = dto.groupName,
                            participants = dto.participants,
                            state = dto.state,
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TOGETHER_SYNC_LOG).w(e, "group list failed")
                emptyList()
            }

        /** Create a group named [groupName] and make [itemId] at [positionMs] its queue. */
        suspend fun startParty(
            itemId: UUID,
            positionMs: Long,
            groupName: String,
        ) {
            if (_state.value is TogetherState.InGroup || _state.value is TogetherState.Joining) return
            queueOnJoin = itemId to positionMs
            _state.value = TogetherState.Joining
            try {
                arm()
                withContext(Dispatchers.IO) {
                    api.syncPlayApi.syncPlayCreateGroup(NewGroupRequestDto(groupName = groupName))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TOGETHER_SYNC_LOG).e(e, "create group failed")
                queueOnJoin = null
                if (_state.value is TogetherState.Joining) {
                    apply(TogetherUpdate.Denied(e.message?.takeIf { it.isNotBlank() } ?: "Could not start a party"))
                }
            }
        }

        /** Join an existing group; the TV then opens and holds the group's item until the group starts it. */
        suspend fun join(groupId: UUID) {
            if (_state.value is TogetherState.InGroup || _state.value is TogetherState.Joining) return
            queueOnJoin = null
            _state.value = TogetherState.Joining
            try {
                arm()
                withContext(Dispatchers.IO) {
                    api.syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(groupId = groupId))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TOGETHER_SYNC_LOG).e(e, "join group failed")
                if (_state.value is TogetherState.Joining) {
                    apply(TogetherUpdate.Denied(e.message?.takeIf { it.isNotBlank() } ?: "Could not join that party"))
                }
            }
        }

        suspend fun leave() {
            if (_state.value is TogetherState.Idle) return
            try {
                withContext(Dispatchers.IO) { api.syncPlayApi.syncPlayLeaveGroup() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TOGETHER_SYNC_LOG).w(e, "leave failed")
            }
            if (_state.value !is TogetherState.Idle) apply(TogetherUpdate.Left)
        }

        private suspend fun arm() {
            listening = true
            startIncoming()
            startTapWatch()
            ensureSubscriptions()
            awaitTap()
        }

        private fun startIncoming() {
            if (incomingJob?.isActive == true) return
            incomingJob =
                scope.launch {
                    for (text in incoming) handleRaw(text)
                }
        }

        private fun startTapWatch() {
            if (tapWatch?.isActive == true) return
            tapWatch =
                scope.launch {
                    while (isActive) {
                        if (listening) installTap()
                        delay(TAP_WATCH_MS)
                    }
                }
        }

        private suspend fun ensureSubscriptions() {
            if (groupJob?.isActive == true && commandJob?.isActive == true) return
            val groupReady = CompletableDeferred<Unit>()
            val commandReady = CompletableDeferred<Unit>()
            groupJob =
                api.webSocket
                    .subscribe<SyncPlayGroupUpdateCommandMessage>()
                    .onStart { groupReady.complete(Unit) }
                    .onEach { message ->
                        // The typed update has no payload. [handleRaw] applies it.
                        Timber.tag(TOGETHER_SYNC_LOG).v("socket group %s", message.data?.type)
                    }.catch { error ->
                        Timber.tag(TOGETHER_SYNC_LOG).e(error, "group subscription")
                    }.launchIn(scope)
            commandJob =
                api.webSocket
                    .subscribe<SyncPlayCommandMessage>()
                    .onStart { commandReady.complete(Unit) }
                    .onEach { message ->
                        val command = message.data ?: return@onEach
                        if (!listening || !claim(message.messageId)) return@onEach
                        sync.onCommand(command)
                    }.catch { error ->
                        Timber.tag(TOGETHER_SYNC_LOG).e(error, "command subscription")
                    }.launchIn(scope)
            if (withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) {
                    groupReady.await()
                    commandReady.await()
                } == null
            ) {
                Timber.tag(TOGETHER_SYNC_LOG).w("socket subscribe did not start")
            }
        }

        private suspend fun awaitTap() {
            val deadline = SystemClock.elapsedRealtime() + TAP_WAIT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (installTap()) return
                delay(50)
            }
            Timber.tag(TOGETHER_SYNC_LOG).e("raw syncplay tap was not installed")
        }

        private fun handleRaw(text: String) {
            if (!listening) return
            val frame =
                try {
                    TogetherFrames.parse(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TOGETHER_SYNC_LOG).w(e, "bad syncplay frame")
                    null
                } ?: return
            if (!claim(frame.messageId)) return
            when (frame) {
                is TogetherFrame.Group -> apply(frame.update)
                is TogetherFrame.Command -> sync.onCommand(frame.command)
            }
        }

        private fun claim(id: UUID): Boolean {
            if (!seen.add(id)) return false
            seenIds.addLast(id)
            while (seenIds.size > SEEN_LIMIT) seen.remove(seenIds.removeFirst())
            return true
        }

        private fun apply(update: TogetherUpdate) {
            val previous = _state.value
            val (next, notices) = TogetherReducer.reduce(previous, update)
            notices.forEach(::emit)
            _state.value = next
            when (update) {
                is TogetherUpdate.GroupJoined -> {
                    if (next is TogetherState.InGroup) onJoined()
                }

                is TogetherUpdate.Queue -> {
                    sync.onQueue(update.queue, update.reason)
                }

                is TogetherUpdate.State -> {
                    (next as? TogetherState.InGroup)?.let { watchWaiting(it.group) }
                }

                TogetherUpdate.Left,
                TogetherUpdate.NotInGroup,
                -> {
                    if (previous !is TogetherState.Idle) endTransport()
                }

                is TogetherUpdate.Denied -> {
                    endTransport()
                    _state.value = TogetherState.Idle
                }

                else -> {}
            }
        }

        private fun onJoined() {
            val pending = queueOnJoin
            queueOnJoin = null
            startClock()
            sync.start()
            if (pending == null) return
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        api.syncPlayApi.syncPlaySetNewQueue(
                            PlayRequestDto(
                                playingQueue = listOf(pending.first),
                                playingItemPosition = 0,
                                startPositionTicks = pending.second * SyncPolicy.TICKS_PER_MS,
                            ),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TOGETHER_SYNC_LOG).e(e, "set queue failed")
                    if (_state.value is TogetherState.InGroup) {
                        apply(TogetherUpdate.Denied("Could not start a party"))
                    }
                }
            }
        }

        private fun watchWaiting(group: TogetherGroup) {
            if (group.state != GroupStateType.WAITING) {
                waitingJob?.cancel()
                waitingJob = null
                return
            }
            if (waitingJob?.isActive == true) return
            waitingJob =
                scope.launch {
                    delay(WAITING_NOTICE_MS)
                    val current = (_state.value as? TogetherState.InGroup)?.group
                    if (current?.state == GroupStateType.WAITING) {
                        sync.noteAnnouncedHold()
                        emit(TogetherNotice.Waiting)
                    }
                }
        }

        private fun startClock() {
            if (clockJob?.isActive == true) return
            clockJob =
                scope.launch {
                    repeat(CLOCK_BURST) { index ->
                        if (!listening) return@launch
                        sampleClock()
                        if (index < CLOCK_BURST - 1) delay(CLOCK_BURST_GAP_MS)
                    }
                    while (isActive && listening) {
                        delay(ServerClock.INTERVAL_MS)
                        if (!listening) return@launch
                        sampleClock()
                    }
                }
        }

        private suspend fun sampleClock() {
            try {
                val sentAt = Instant.now()
                val utc =
                    withContext(Dispatchers.IO) {
                        api.timeSyncApi.getUtcTime().content
                    }
                val receivedAt = Instant.now()
                clock.add(
                    ClockSample(
                        sentAt = sentAt,
                        serverReceived = utc.requestReceptionTime.toTogetherInstant(),
                        serverSent = utc.responseTransmissionTime.toTogetherInstant(),
                        receivedAt = receivedAt,
                    ),
                )
                val ping = clock.pingMs
                withContext(Dispatchers.IO) {
                    api.syncPlayApi.syncPlayPing(PingRequestDto(ping = ping))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TOGETHER_SYNC_LOG).w(e, "clock sample failed")
            }
        }

        private fun endTransport() {
            listening = false
            queueOnJoin = null
            waitingJob?.cancel()
            waitingJob = null
            clockJob?.cancel()
            clockJob = null
            sync.stop()
            groupJob?.cancel()
            commandJob?.cancel()
            groupJob = null
            commandJob = null
        }

        private fun emit(notice: TogetherNotice) {
            Timber.tag(TOGETHER_SYNC_LOG).i("notice %s", notice)
            if (!_notices.tryEmit(notice)) scope.launch { _notices.emit(notice) }
        }

        private fun installTap(): Boolean =
            try {
                val connection = instanceField(api.webSocket, SocketConnection::class.java) ?: return false
                val webSocket = instanceField(connection, WebSocket::class.java) ?: return false
                if (webSocket === tappedSocket) return true
                val field =
                    declaredField(webSocket.javaClass) { WebSocketListener::class.java.isAssignableFrom(it.type) }
                        ?: run {
                            Timber.tag(TOGETHER_SYNC_LOG).e(
                                "no listener on %s [%s]",
                                webSocket.javaClass.name,
                                webSocket.javaClass.declaredFields.joinToString { it.name },
                            )
                            return false
                        }
                field.isAccessible = true
                val current = field.get(webSocket) as? WebSocketListener ?: return false
                if (current is SocketTap) {
                    tappedSocket = webSocket
                    return true
                }
                field.set(webSocket, SocketTap(current) { text -> incoming.trySend(text) })
                tappedSocket = webSocket
                Timber.tag(TOGETHER_SYNC_LOG).i("raw syncplay tap installed")
                true
            } catch (e: Exception) {
                Timber.tag(TOGETHER_SYNC_LOG).e(e, "raw syncplay tap failed")
                false
            }

        private companion object {
            const val WAITING_NOTICE_MS = 700L
            const val CLOCK_BURST = 4
            const val CLOCK_BURST_GAP_MS = 250L
            const val TAP_WAIT_MS = 2_000L
            const val TAP_WATCH_MS = 1_000L
            const val SUBSCRIBE_TIMEOUT_MS = 3_000L
            const val SEEN_LIMIT = 200
        }
    }

private class SocketTap(
    private val delegate: WebSocketListener,
    private val onSyncPlay: (String) -> Unit,
) : WebSocketListener() {
    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        delegate.onOpen(webSocket, response)
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        if (text.contains("SyncPlay")) onSyncPlay(text)
        delegate.onMessage(webSocket, text)
    }

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) {
        delegate.onMessage(webSocket, bytes)
    }

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        delegate.onClosing(webSocket, code, reason)
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        delegate.onClosed(webSocket, code, reason)
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        delegate.onFailure(webSocket, t, response)
    }
}

private fun instanceField(
    target: Any,
    type: Class<*>,
): Any? {
    val field = declaredField(target.javaClass) { type.isAssignableFrom(it.type) } ?: return null
    field.isAccessible = true
    return field.get(target)
}

private fun declaredField(
    type: Class<*>,
    predicate: (java.lang.reflect.Field) -> Boolean,
): java.lang.reflect.Field? {
    var cursor: Class<*>? = type
    while (cursor != null && cursor != Any::class.java) {
        cursor.declaredFields.firstOrNull(predicate)?.let { return it }
        cursor = cursor.superclass
    }
    return null
}
