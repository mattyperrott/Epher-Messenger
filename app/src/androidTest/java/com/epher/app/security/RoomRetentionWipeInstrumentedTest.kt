package com.epher.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.epher.app.data.AttachmentFileStore
import com.epher.app.data.RoomsRepository
import com.epher.app.data.LocalRoomCacheStore
import com.epher.app.data.PendingOutboundMessage
import com.epher.app.data.RoomsSnapshot
import com.epher.app.data.model.ConnectionState
import com.epher.app.data.model.InviteExpiryPreset
import com.epher.app.data.model.Participant
import com.epher.app.data.model.RetentionPreset
import com.epher.app.data.model.RoomMessage
import com.epher.app.data.model.RoomRole
import com.epher.app.data.model.RoomSummary
import com.epher.app.engine.FakeP2PEngine
import java.security.GeneralSecurityException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRetentionWipeInstrumentedTest {
    private lateinit var context: Context
    private lateinit var secureBlobStore: SecureBlobStore
    private lateinit var localCacheStore: LocalRoomCacheStore
    private lateinit var attachmentFileStore: AttachmentFileStore
    private lateinit var roomIdentityManager: RoomIdentityManager
    private lateinit var secureRoomService: SecureRoomService
    private lateinit var pairwiseProtocol: PairwiseProtocolService
    private lateinit var scope: CoroutineScope
    private lateinit var backup: StoreBackup

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        secureBlobStore = SecureBlobStore(context)
        backup = StoreBackup.capture(secureBlobStore)
        localCacheStore = LocalRoomCacheStore(secureBlobStore)
        attachmentFileStore = AttachmentFileStore(context)
        roomIdentityManager = RoomIdentityManager(secureBlobStore)
        secureRoomService = SecureRoomService(
            secureBlobStore = secureBlobStore,
            identityManager = DeviceIdentityManager(secureBlobStore),
            roomIdentityManager = roomIdentityManager,
        )
        pairwiseProtocol = PairwiseProtocolService(secureRoomService, secureBlobStore, roomIdentityManager)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        backup.restore(secureBlobStore)
    }

    @Test
    fun leaveRoom_wipesRoomScopedState() = runBlocking {
        val repository = RoomsRepository(
            scope = scope,
            engine = FakeP2PEngine(),
            localCacheStore = localCacheStore,
            attachmentFileStore = attachmentFileStore,
            roomSecurity = secureRoomService,
            pairwiseProtocol = pairwiseProtocol,
            localDisplayName = "Test User",
        )

        val roomId = repository.createRoom(
            label = "Leave Wipe",
            retentionPreset = RetentionPreset.LeaveOnly,
            inviteExpiryPreset = InviteExpiryPreset.Day,
        )
        delay(250)

        assertTrue(storeString("room.identities.v1").contains(roomId))
        assertTrue(storeString("secure_room_sessions_v1").contains(roomId))

        repository.leaveRoom(roomId)
        delay(250)

        assertNull(repository.room(roomId))
        assertFalse(localCacheStore.load()?.snapshot?.rooms?.any { it.id == roomId } == true)
        assertFalse(storeString("room.identities.v1").contains(roomId))
        assertFalse(storeString("secure_room_sessions_v1").contains(roomId))
        assertFalse(storeString("pairwise.prekeys.v4").contains(roomId))
        assertFalse(storeString("pairwise.sessions.v4").contains(roomId))
        assertMissingRoomSession(roomId)
    }

    @Test
    fun expiredRoom_isPurgedOnRepositoryStart() = runBlocking {
        val roomId = "expired-${UUID.randomUUID()}"
        val created = secureRoomService.createRoom(
            label = "Expiring Room",
            roomId = roomId,
            roomPassword = "expire-me-${UUID.randomUUID()}",
        )
        pairwiseProtocol.createLocalPeerCard(roomId, "Tester")

        val snapshot = RoomsSnapshot(
            rooms = listOf(
                RoomSummary(
                    id = roomId,
                    localLabel = "Expiring Room",
                    participantCount = 1,
                    unreadCount = 0,
                    connectionState = ConnectionState.Connected,
                    retentionPreset = RetentionPreset.Day,
                    isOwner = true,
                    invitePackage = created.invitePackage,
                    securityProfile = secureRoomService.roomProfile(roomId),
                    lastActivityEpochMillis = System.currentTimeMillis() - (RetentionPreset.Day.maxAgeMillis!! + 60_000L),
                ),
            ),
            participants = mapOf(
                roomId to listOf(
                    Participant(
                        id = "local-$roomId",
                        displayName = "Tester",
                        isOnline = true,
                        isVerified = true,
                        role = RoomRole.Owner,
                        fingerprint = secureRoomService.roomFingerprint(roomId),
                    ),
                ),
            ),
            messages = mapOf(
                roomId to listOf(
                    RoomMessage(
                        id = "msg-$roomId",
                        roomId = roomId,
                        senderName = "Tester",
                        body = "This should be purged.",
                        sentAt = "Now",
                        isLocalUser = true,
                    ),
                ),
            ),
            logs = mapOf(roomId to listOf("expired room log")),
        )

        localCacheStore.save(
            snapshot = snapshot,
            pendingOutbound = mapOf(
                roomId to listOf(
                    PendingOutboundMessage(
                        roomId = roomId,
                        messageId = "pending-$roomId",
                        body = "pending purge",
                        createdAtEpochMillis = System.currentTimeMillis() - 5_000L,
                    ),
                ),
            ),
        )

        assertTrue(storeString("room.identities.v1").contains(roomId))
        assertTrue(storeString("secure_room_sessions_v1").contains(roomId))

        val repository = RoomsRepository(
            scope = scope,
            engine = FakeP2PEngine(),
            localCacheStore = localCacheStore,
            attachmentFileStore = attachmentFileStore,
            roomSecurity = secureRoomService,
            pairwiseProtocol = pairwiseProtocol,
            localDisplayName = "Test User",
        )
        delay(250)

        assertNull(repository.room(roomId))
        assertFalse(localCacheStore.load()?.snapshot?.rooms?.any { it.id == roomId } == true)
        assertFalse(storeString("room.identities.v1").contains(roomId))
        assertFalse(storeString("secure_room_sessions_v1").contains(roomId))
        assertFalse(storeString("pairwise.prekeys.v4").contains(roomId))
        assertFalse(storeString("pairwise.sessions.v4").contains(roomId))
        assertMissingRoomSession(roomId)
    }

    private fun assertMissingRoomSession(roomId: String) {
        val missing = runCatching { secureRoomService.transportTopicHex(roomId) }
            .exceptionOrNull() is GeneralSecurityException
        assertTrue(missing)
    }

    private fun storeString(key: String): String = secureBlobStore.getString(key).orEmpty()
}

private data class StoreBackup(
    val values: Map<String, String?>,
) {
    fun restore(secureBlobStore: SecureBlobStore) {
        values.forEach { (key, value) ->
            if (value == null) {
                secureBlobStore.remove(key)
            } else {
                secureBlobStore.putString(key, value)
            }
        }
    }

    companion object {
        private val KEYS = listOf(
            "epher.rooms.cache.v1",
            "secure_room_sessions_v1",
            "pairwise.peers.v4",
            "pairwise.prekeys.v4",
            "pairwise.sessions.v4",
            "room.identities.v1",
            "device_identity_v1",
        )

        fun capture(secureBlobStore: SecureBlobStore): StoreBackup = StoreBackup(
            values = KEYS.associateWith { key -> secureBlobStore.getString(key) },
        )
    }
}
