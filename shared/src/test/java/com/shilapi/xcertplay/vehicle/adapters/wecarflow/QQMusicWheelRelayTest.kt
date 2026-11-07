package com.shilapi.xcertplay.vehicle.adapters.wecarflow

import android.os.Parcel
import com.shilapi.xcertplay.vehicle.api.MediaCommand
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class QQMusicWheelRelayTest {
    private val commands = mutableListOf<MediaCommand>()
    private val signature = UUID.randomUUID().toString()
    private fun client(accept: Boolean = true) = QQMusicPlaybackGuardClient(
        RuntimeEnvironment.getApplication(), {}, {}, { commands.add(it); accept }, {},
    )

    private fun deliver(client: QQMusicPlaybackGuardClient, command: Int, code: Int = 2,
                        event: String = signature): Boolean {
        val field = client.javaClass.getDeclaredField("callback").apply { isAccessible = true }
        val callback = field.get(client)
        val method = callback.javaClass.getDeclaredMethod("onTransact", Int::class.javaPrimitiveType,
            Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.shilapi.xcertplay.vehicle.LocalPlaybackGuard.v1")
            data.writeInt(command)
            if (code == 2) data.writeString(event)
            data.setDataPosition(0)
            assertEquals(true, method.invoke(callback, code, data, reply, 0))
            reply.setDataPosition(0)
            reply.readException()
            return reply.readInt() == 1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test fun forwardsNextAndPreviousFromCompanionWheel() {
        val client = client()
        assertTrue(deliver(client, 4, event = "$signature:next"))
        assertTrue(deliver(client, 5, event = "$signature:previous"))
        assertEquals(listOf(MediaCommand.NEXT, MediaCommand.PREVIOUS), commands)
    }

    @Test fun acknowledgesDuplicateWithoutSkippingAnotherTrack() {
        val client = client()
        assertTrue(deliver(client, 4))
        assertTrue(deliver(client, 4))
        assertTrue(deliver(client, 4, event = "$signature:another-press"))
        assertEquals(listOf(MediaCommand.NEXT, MediaCommand.NEXT), commands)
    }

    @Test fun explicitUiCommandsAreNotDeduplicated() {
        val client = client()
        assertTrue(deliver(client, 1, code = 1))
        assertTrue(deliver(client, 1, code = 1))
        assertTrue(deliver(client, 2, code = 1))
        assertEquals(listOf(MediaCommand.PLAY, MediaCommand.PLAY, MediaCommand.PAUSE), commands)
    }

    @Test fun returnsDispatchRejectionToCompanion() {
        assertFalse(deliver(client(accept = false), 4))
        assertEquals(listOf(MediaCommand.NEXT), commands)
    }

    @Test fun closedClientDoesNotSendWheelCommands() {
        val client = client()
        client.close()
        assertFalse(deliver(client, 4))
        assertTrue(commands.isEmpty())
    }

    @Test fun unknownCommandIsRejected() {
        assertFalse(deliver(client(), 99))
        assertTrue(commands.isEmpty())
    }
}
