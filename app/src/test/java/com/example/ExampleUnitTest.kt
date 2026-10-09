package com.example

import android.view.KeyEvent
import com.example.data.local.KeyMappingEntity
import com.example.data.model.RemoteProtocol
import com.example.keyboard.HardwareKeyboardEngine
import com.example.protocol.NetworkProbeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun hardwareKeyboard_translatesFunctionAndSpecialKeysAcrossProtocols() {
        val f11Packet = HardwareKeyboardEngine.translateKeyEvent(
            keyCode = KeyEvent.KEYCODE_F11,
            unicodeChar = 0,
            protocol = RemoteProtocol.VNC,
            stickyCtrl = false,
            stickyAlt = false,
            stickyShift = false,
            stickySuper = false,
            customMappings = emptyList()
        )
        assertEquals("F11", f11Packet.keyLabel)
        assertEquals("0x57", f11Packet.rdpScancodeHex)
        assertEquals("0xFFC8", f11Packet.vncKeySymHex)
        assertEquals("XK_F11", f11Packet.vncKeySymName)
        assertEquals("\u001b[23~", f11Packet.sshRawPayload)
    }

    @Test
    fun hardwareKeyboard_appliesCustomKeyMappingRule() {
        val capsToEscRule = KeyMappingEntity(
            id = 1L,
            name = "CapsLock to Escape",
            targetProtocol = "ALL",
            sourceKeyLabel = "CAPS_LOCK",
            sourceAndroidKeyCode = KeyEvent.KEYCODE_CAPS_LOCK,
            mappedActionType = "KEY_REMAP",
            targetKeyLabel = "ESCAPE",
            targetAndroidKeyCode = KeyEvent.KEYCODE_ESCAPE,
            isEnabled = true
        )

        val packet = HardwareKeyboardEngine.translateKeyEvent(
            keyCode = KeyEvent.KEYCODE_CAPS_LOCK,
            unicodeChar = 0,
            protocol = RemoteProtocol.SSH,
            stickyCtrl = false,
            stickyAlt = false,
            stickyShift = false,
            stickySuper = false,
            customMappings = listOf(capsToEscRule)
        )

        assertEquals(KeyEvent.KEYCODE_ESCAPE, packet.effectiveKeyCode)
        assertEquals("0x01", packet.rdpScancodeHex)
        assertEquals("0xFF1B", packet.vncKeySymHex)
        assertEquals("\u001b", packet.sshRawPayload)
        assertEquals("CapsLock to Escape", packet.appliedMappingName)
    }

    @Test
    fun hardwareKeyboard_translatesCtrlCombinationToSshControlByte() {
        val ctrlCPacket = HardwareKeyboardEngine.translateKeyEvent(
            keyCode = KeyEvent.KEYCODE_C,
            unicodeChar = 'c'.code,
            protocol = RemoteProtocol.SSH,
            stickyCtrl = true,
            stickyAlt = false,
            stickyShift = false,
            stickySuper = false,
            customMappings = emptyList()
        )
        assertTrue(ctrlCPacket.ctrlActive)
        assertEquals("^C (0x03)", ctrlCPacket.sshAnsiEscape)
        assertEquals("\u0003", ctrlCPacket.sshRawPayload)
    }

    @Test
    fun resolutionParser_extractsWidthAndHeight() {
        val (w, h) = NetworkProbeEngine.parseResolutionDimensions("2560x1440")
        assertEquals(2560, w)
        assertEquals(1440, h)
    }

    @Test
    fun hardwareKeyboard_translatesTopRowAndNumpadDigitsToSshRawPayload() {
        // Verify top-row number keys produce exact single-digit SSH raw payloads even if unicodeChar == 0
        val digit5Packet = HardwareKeyboardEngine.translateKeyEvent(
            keyCode = KeyEvent.KEYCODE_5,
            unicodeChar = 0,
            protocol = RemoteProtocol.SSH,
            stickyCtrl = false,
            stickyAlt = false,
            stickyShift = false,
            stickySuper = false,
            customMappings = emptyList()
        )
        assertEquals("5", digit5Packet.sshRawPayload)

        // Verify numpad number keys produce exact single-digit SSH raw payloads
        val numpad8Packet = HardwareKeyboardEngine.translateKeyEvent(
            keyCode = KeyEvent.KEYCODE_NUMPAD_8,
            unicodeChar = 0,
            protocol = RemoteProtocol.SSH,
            stickyCtrl = false,
            stickyAlt = false,
            stickyShift = false,
            stickySuper = false,
            customMappings = emptyList()
        )
        assertEquals("8", numpad8Packet.sshRawPayload)

        // Verify Shift + top-row digit produces expected shifted symbol when unicodeChar == 0
        val shiftedDigit2Packet = HardwareKeyboardEngine.translateKeyEvent(
            keyCode = KeyEvent.KEYCODE_2,
            unicodeChar = 0,
            protocol = RemoteProtocol.SSH,
            stickyCtrl = false,
            stickyAlt = false,
            stickyShift = true,
            stickySuper = false,
            customMappings = emptyList()
        )
        assertEquals("@", shiftedDigit2Packet.sshRawPayload)
    }

    @Test
    fun remoteProtocol_parsesHttpsAndWebConsoleAliases() {
        assertEquals(RemoteProtocol.HTTPS, RemoteProtocol.fromString("HTTPS"))
        assertEquals(RemoteProtocol.HTTPS, RemoteProtocol.fromString("http"))
        assertEquals(RemoteProtocol.HTTPS, RemoteProtocol.fromString("Proxmox"))
        assertEquals(RemoteProtocol.HTTPS, RemoteProtocol.fromString("cockpit"))
        assertEquals(RemoteProtocol.HTTPS, RemoteProtocol.fromString("web"))
        assertEquals(443, RemoteProtocol.HTTPS.defaultPort)
    }
}

