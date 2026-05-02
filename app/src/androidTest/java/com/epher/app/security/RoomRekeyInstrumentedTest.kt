package com.epher.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.epher.app.data.model.InviteExpiryPreset
import java.security.GeneralSecurityException
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRekeyInstrumentedTest {
    private lateinit var ownerStore: SecureBlobStore
    private lateinit var memberStore: SecureBlobStore
    private lateinit var ownerRoomIdentity: RoomIdentityManager
    private lateinit var memberRoomIdentity: RoomIdentityManager
    private lateinit var ownerRoomSecurity: SecureRoomService
    private lateinit var memberRoomSecurity: SecureRoomService
    private lateinit var ownerPairwise: PairwiseProtocolService
    private lateinit var memberPairwise: PairwiseProtocolService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        ownerStore = SecureBlobStore(context, "rekey-owner-$suffix")
        memberStore = SecureBlobStore(context, "rekey-member-$suffix")
        ownerRoomIdentity = RoomIdentityManager(ownerStore)
        memberRoomIdentity = RoomIdentityManager(memberStore)
        ownerRoomSecurity = SecureRoomService(
            secureBlobStore = ownerStore,
            identityManager = DeviceIdentityManager(ownerStore),
            roomIdentityManager = ownerRoomIdentity,
        )
        memberRoomSecurity = SecureRoomService(
            secureBlobStore = memberStore,
            identityManager = DeviceIdentityManager(memberStore),
            roomIdentityManager = memberRoomIdentity,
        )
        ownerPairwise = PairwiseProtocolService(ownerRoomSecurity, ownerStore, ownerRoomIdentity)
        memberPairwise = PairwiseProtocolService(memberRoomSecurity, memberStore, memberRoomIdentity)
    }

    @After
    fun tearDown() {
        clearStore(ownerStore)
        clearStore(memberStore)
    }

    @Test
    fun ownerRekey_incrementsEpochAndChangesTransportTopic() {
        val roomId = createJoinedRoom()
        val originalTopic = ownerRoomSecurity.transportTopicHex(roomId)

        val rekey = ownerRoomSecurity.rotateRoomSecret(roomId)

        assertEquals(1L, rekey.epoch)
        assertEquals(1L, ownerRoomSecurity.roomEpoch(roomId))
        assertNotEquals(originalTopic, ownerRoomSecurity.transportTopicHex(roomId))
    }

    @Test
    fun staleRekey_isRejected() {
        val roomId = createJoinedRoom()
        val rekey = ownerRoomSecurity.rotateRoomSecret(roomId)
        memberRoomSecurity.applyRoomRekey(roomId, rekey.roomPassword, rekey.epoch)

        val stale = runCatching {
            memberRoomSecurity.applyRoomRekey(roomId, "stale-${UUID.randomUUID()}", rekey.epoch)
        }.exceptionOrNull()

        assertTrue(stale is GeneralSecurityException)
        assertEquals(rekey.epoch, memberRoomSecurity.roomEpoch(roomId))
    }

    @Test
    fun existingPairwiseSessionSurvivesRoomRekeyUntilPeerAppliesNewEpoch() {
        val roomId = createJoinedRoom()
        exchangePeerCards(roomId)

        val memberFingerprint = memberRoomSecurity.roomFingerprint(roomId)
        val ownerFingerprint = ownerRoomSecurity.roomFingerprint(roomId)

        val beforeRekey = ownerPairwise.encryptForPeer(
            roomId = roomId,
            recipientFingerprint = memberFingerprint,
            senderName = "Owner",
            plaintext = "before rekey",
        )
        assertEquals(
            "before rekey",
            memberPairwise.decryptEnvelope(roomId, beforeRekey.encode())?.body,
        )

        val rekey = ownerRoomSecurity.rotateRoomSecret(roomId)
        val encryptedRekey = ownerPairwise.encryptForPeer(
            roomId = roomId,
            recipientFingerprint = memberFingerprint,
            senderName = "Owner",
            plaintext = "room rekey payload",
        )

        assertEquals(
            "room rekey payload",
            memberPairwise.decryptEnvelope(roomId, encryptedRekey.encode())?.body,
        )

        memberRoomSecurity.applyRoomRekey(roomId, rekey.roomPassword, rekey.epoch)

        val afterRekey = memberPairwise.encryptForPeer(
            roomId = roomId,
            recipientFingerprint = ownerFingerprint,
            senderName = "Member",
            plaintext = "after rekey",
        )
        assertEquals(
            "after rekey",
            ownerPairwise.decryptEnvelope(roomId, afterRekey.encode())?.body,
        )
    }

    private fun createJoinedRoom(): String {
        val created = ownerRoomSecurity.createRoom(
            label = "Rekey Test",
            inviteExpiryPreset = InviteExpiryPreset.Day,
        )
        memberRoomSecurity.joinRoom(created.invitePackage.inviteToken)
        return created.roomId
    }

    private fun exchangePeerCards(roomId: String) {
        val ownerCard = ownerPairwise.createLocalPeerCard(roomId, "Owner")
        val memberCard = memberPairwise.createLocalPeerCard(roomId, "Member")
        val ownerTransportKey = ownerRoomSecurity.deviceTransportPublicKeyHex()
        val memberTransportKey = memberRoomSecurity.deviceTransportPublicKeyHex()

        memberPairwise.ingestPeerCard(roomId, ownerCard, ownerTransportKey)
        ownerPairwise.ingestPeerCard(roomId, memberCard, memberTransportKey)
        memberPairwise.establishSession(roomId, ownerRoomSecurity.roomFingerprint(roomId))
        ownerPairwise.establishSession(roomId, memberRoomSecurity.roomFingerprint(roomId))
    }

    private fun clearStore(store: SecureBlobStore) {
        TEST_KEYS.forEach(store::remove)
    }

    private companion object {
        val TEST_KEYS = listOf(
            "device_identity_v1",
            "room.identities.v1",
            "secure_room_sessions_v1",
            "pairwise.peers.v4",
            "pairwise.prekeys.v4",
            "pairwise.sessions.v4",
        )
    }
}
