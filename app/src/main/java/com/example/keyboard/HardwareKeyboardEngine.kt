package com.example.keyboard

import android.view.KeyEvent
import com.example.data.local.KeyMappingEntity
import com.example.data.model.RemoteProtocol

data class TranslatedKeyPacket(
    val originalKeyCode: Int,
    val effectiveKeyCode: Int,
    val keyLabel: String,
    val ctrlActive: Boolean,
    val altActive: Boolean,
    val shiftActive: Boolean,
    val superActive: Boolean,
    val rdpScancodeHex: String,
    val rdpExtended: Boolean,
    val vncKeySymHex: String,
    val vncKeySymName: String,
    val sshAnsiEscape: String,
    val sshRawPayload: String,
    val spiceScancodeHex: String,
    val appliedMappingName: String? = null,
    val printableChar: Char? = null
)

data class RdpCharKeySpec(
    val scancode: Int,
    val shiftRequired: Boolean = false,
    val isExtended: Boolean = false,
    val useUnicodeEvent: Boolean = false,
    val unicodeCode: Int = 0
)

object HardwareKeyboardEngine {

    data class KeyMetadata(
        val keyCode: Int,
        val label: String,
        val rdpScan: Int,
        val rdpExt: Boolean,
        val vncSym: Int,
        val vncName: String,
        val sshEsc: String,
        val sshPayload: String
    )

    val standardKeysCatalog: List<KeyMetadata> = listOf(
        KeyMetadata(KeyEvent.KEYCODE_ESCAPE, "ESC", 0x01, false, 0xFF1B, "XK_Escape", "\\e (0x1B)", "\u001b"),
        KeyMetadata(KeyEvent.KEYCODE_TAB, "TAB", 0x0F, false, 0xFF09, "XK_Tab", "\\t (0x09)", "\t"),
        KeyMetadata(KeyEvent.KEYCODE_CAPS_LOCK, "CAPS_LOCK", 0x3A, false, 0xFFE5, "XK_Caps_Lock", "<CAPS>", ""),
        KeyMetadata(KeyEvent.KEYCODE_SHIFT_LEFT, "SHIFT_LEFT", 0x2A, false, 0xFFE1, "XK_Shift_L", "<LSHIFT>", ""),
        KeyMetadata(KeyEvent.KEYCODE_SHIFT_RIGHT, "SHIFT_RIGHT", 0x36, false, 0xFFE2, "XK_Shift_R", "<RSHIFT>", ""),
        KeyMetadata(KeyEvent.KEYCODE_CTRL_LEFT, "CTRL_LEFT", 0x1D, false, 0xFFE3, "XK_Control_L", "<LCTRL>", ""),
        KeyMetadata(KeyEvent.KEYCODE_CTRL_RIGHT, "CTRL_RIGHT", 0x1D, true, 0xFFE4, "XK_Control_R", "<RCTRL>", ""),
        KeyMetadata(KeyEvent.KEYCODE_ALT_LEFT, "ALT_LEFT", 0x38, false, 0xFFE9, "XK_Alt_L", "\\e prefix", ""),
        KeyMetadata(KeyEvent.KEYCODE_ALT_RIGHT, "ALT_RIGHT (AltGr)", 0x38, true, 0xFFEA, "XK_Alt_R", "\\e prefix", ""),
        KeyMetadata(KeyEvent.KEYCODE_META_LEFT, "SUPER_LEFT (Win)", 0x5B, true, 0xFFEB, "XK_Super_L", "<SUPER>", ""),
        KeyMetadata(KeyEvent.KEYCODE_META_RIGHT, "SUPER_RIGHT (Win)", 0x5C, true, 0xFFEC, "XK_Super_R", "<SUPER>", ""),
        KeyMetadata(KeyEvent.KEYCODE_ENTER, "ENTER", 0x1C, false, 0xFF0D, "XK_Return", "\\r\\n (0x0D)", "\r"),
        KeyMetadata(KeyEvent.KEYCODE_NUMPAD_ENTER, "NUMPAD_ENTER", 0x1C, true, 0xFF8D, "XK_KP_Enter", "\\r\\n (0x0D)", "\r"),
        KeyMetadata(KeyEvent.KEYCODE_DEL, "BACKSPACE", 0x0E, false, 0xFF08, "XK_BackSpace", "0x7F (DEL)", "\u007f"),
        KeyMetadata(KeyEvent.KEYCODE_FORWARD_DEL, "DELETE", 0x53, true, 0xFFFF, "XK_Delete", "\\e[3~", "\u001b[3~"),
        KeyMetadata(KeyEvent.KEYCODE_INSERT, "INSERT", 0x52, true, 0xFF63, "XK_Insert", "\\e[2~", "\u001b[2~"),
        KeyMetadata(KeyEvent.KEYCODE_MOVE_HOME, "HOME", 0x47, true, 0xFF50, "XK_Home", "\\e[H", "\u001b[H"),
        KeyMetadata(KeyEvent.KEYCODE_MOVE_END, "END", 0x4F, true, 0xFF57, "XK_End", "\\e[F", "\u001b[F"),
        KeyMetadata(KeyEvent.KEYCODE_PAGE_UP, "PAGE_UP", 0x49, true, 0xFF55, "XK_Page_Up", "\\e[5~", "\u001b[5~"),
        KeyMetadata(KeyEvent.KEYCODE_PAGE_DOWN, "PAGE_DOWN", 0x51, true, 0xFF56, "XK_Page_Down", "\\e[6~", "\u001b[6~"),
        KeyMetadata(KeyEvent.KEYCODE_DPAD_UP, "UP_ARROW", 0x48, true, 0xFF52, "XK_Up", "\\e[A", "\u001b[A"),
        KeyMetadata(KeyEvent.KEYCODE_DPAD_DOWN, "DOWN_ARROW", 0x50, true, 0xFF54, "XK_Down", "\\e[B", "\u001b[B"),
        KeyMetadata(KeyEvent.KEYCODE_DPAD_RIGHT, "RIGHT_ARROW", 0x4D, true, 0xFF53, "XK_Right", "\\e[C", "\u001b[C"),
        KeyMetadata(KeyEvent.KEYCODE_DPAD_LEFT, "LEFT_ARROW", 0x4B, true, 0xFF51, "XK_Left", "\\e[D", "\u001b[D"),
        KeyMetadata(KeyEvent.KEYCODE_F1, "F1", 0x3B, false, 0xFFBE, "XK_F1", "\\eOP", "\u001bOP"),
        KeyMetadata(KeyEvent.KEYCODE_F2, "F2", 0x3C, false, 0xFFBF, "XK_F2", "\\eOQ", "\u001bOQ"),
        KeyMetadata(KeyEvent.KEYCODE_F3, "F3", 0x3D, false, 0xFFC0, "XK_F3", "\\eOR", "\u001bOR"),
        KeyMetadata(KeyEvent.KEYCODE_F4, "F4", 0x3E, false, 0xFFC1, "XK_F4", "\\eOS", "\u001bOS"),
        KeyMetadata(KeyEvent.KEYCODE_F5, "F5", 0x3F, false, 0xFFC2, "XK_F5", "\\e[15~", "\u001b[15~"),
        KeyMetadata(KeyEvent.KEYCODE_F6, "F6", 0x40, false, 0xFFC3, "XK_F6", "\\e[17~", "\u001b[17~"),
        KeyMetadata(KeyEvent.KEYCODE_F7, "F7", 0x41, false, 0xFFC4, "XK_F7", "\\e[18~", "\u001b[18~"),
        KeyMetadata(KeyEvent.KEYCODE_F8, "F8", 0x42, false, 0xFFC5, "XK_F8", "\\e[19~", "\u001b[19~"),
        KeyMetadata(KeyEvent.KEYCODE_F9, "F9", 0x43, false, 0xFFC6, "XK_F9", "\\e[20~", "\u001b[20~"),
        KeyMetadata(KeyEvent.KEYCODE_F10, "F10", 0x44, false, 0xFFC7, "XK_F10", "\\e[21~", "\u001b[21~"),
        KeyMetadata(KeyEvent.KEYCODE_F11, "F11", 0x57, false, 0xFFC8, "XK_F11", "\\e[23~", "\u001b[23~"),
        KeyMetadata(KeyEvent.KEYCODE_F12, "F12", 0x58, false, 0xFFC9, "XK_F12", "\\e[24~", "\u001b[24~"),
        KeyMetadata(KeyEvent.KEYCODE_SYSRQ, "SYSRQ / PRTSC", 0x37, true, 0xFF61, "XK_Print", "\\e[32~", "")
    )

    fun translateKeyEvent(
        keyCode: Int,
        unicodeChar: Int,
        protocol: RemoteProtocol,
        stickyCtrl: Boolean,
        stickyAlt: Boolean,
        stickyShift: Boolean,
        stickySuper: Boolean,
        customMappings: List<KeyMappingEntity>
    ): TranslatedKeyPacket {
        // 1. Check if any active custom mapping matches this key + modifiers + protocol
        val matchedMapping = customMappings.firstOrNull { m ->
            m.isEnabled &&
                (m.targetProtocol == "ALL" || m.targetProtocol.equals(protocol.name, ignoreCase = true)) &&
                m.sourceAndroidKeyCode == keyCode &&
                (!m.requireCtrl || stickyCtrl) &&
                (!m.requireAlt || stickyAlt) &&
                (!m.requireShift || stickyShift) &&
                (!m.requireMeta || stickySuper)
        }

        val effectiveKeyCode = if (matchedMapping != null && matchedMapping.targetAndroidKeyCode != 0) {
            matchedMapping.targetAndroidKeyCode
        } else {
            keyCode
        }

        var effCtrl = stickyCtrl || ((matchedMapping?.targetModifiersMask ?: 0) and 1 != 0)
        var effAlt = stickyAlt || ((matchedMapping?.targetModifiersMask ?: 0) and 2 != 0)
        var effShift = stickyShift || ((matchedMapping?.targetModifiersMask ?: 0) and 4 != 0)
        val effSuper = stickySuper || ((matchedMapping?.targetModifiersMask ?: 0) and 8 != 0)

        // 2. Look up in catalog
        val catalogEntry = standardKeysCatalog.firstOrNull { it.keyCode == effectiveKeyCode }
        if (catalogEntry != null) {
            val rdpHex = if (catalogEntry.rdpExt) {
                "0xE0%02X".format(catalogEntry.rdpScan)
            } else {
                "0x%02X".format(catalogEntry.rdpScan)
            }
            val spiceHex = if (catalogEntry.rdpExt) {
                "E0 %02X / E0 %02X".format(catalogEntry.rdpScan, catalogEntry.rdpScan or 0x80)
            } else {
                "%02X / %02X".format(catalogEntry.rdpScan, catalogEntry.rdpScan or 0x80)
            }
            val customSeq = matchedMapping?.customSequence?.takeIf { it.isNotEmpty() }
            return TranslatedKeyPacket(
                originalKeyCode = keyCode,
                effectiveKeyCode = effectiveKeyCode,
                keyLabel = matchedMapping?.targetKeyLabel ?: catalogEntry.label,
                ctrlActive = effCtrl,
                altActive = effAlt,
                shiftActive = effShift,
                superActive = effSuper,
                rdpScancodeHex = rdpHex,
                rdpExtended = catalogEntry.rdpExt,
                vncKeySymHex = "0x%04X".format(catalogEntry.vncSym),
                vncKeySymName = catalogEntry.vncName,
                sshAnsiEscape = customSeq?.replace("\n", "\\n") ?: catalogEntry.sshEsc,
                sshRawPayload = customSeq ?: catalogEntry.sshPayload,
                spiceScancodeHex = spiceHex,
                appliedMappingName = matchedMapping?.name
            )
        }

        // 3. Handle A-Z, 0-9, and symbols
        val baseChar: Char? = when {
            unicodeChar in 32..126 -> unicodeChar.toChar()
            effectiveKeyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val offset = effectiveKeyCode - KeyEvent.KEYCODE_A
                if (effShift) ('A' + offset) else ('a' + offset)
            }
            effectiveKeyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val digit = effectiveKeyCode - KeyEvent.KEYCODE_0
                if (effShift) {
                    ")!@#$%^&*("[digit]
                } else {
                    ('0' + digit)
                }
            }
            effectiveKeyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                val digit = effectiveKeyCode - KeyEvent.KEYCODE_NUMPAD_0
                ('0' + digit)
            }
            effectiveKeyCode == KeyEvent.KEYCODE_NUMPAD_DOT -> '.'
            effectiveKeyCode == KeyEvent.KEYCODE_NUMPAD_DIVIDE -> '/'
            effectiveKeyCode == KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> '*'
            effectiveKeyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> '-'
            effectiveKeyCode == KeyEvent.KEYCODE_NUMPAD_ADD -> '+'
            effectiveKeyCode == KeyEvent.KEYCODE_NUMPAD_EQUALS -> '='
            effectiveKeyCode == KeyEvent.KEYCODE_SPACE -> ' '
            effectiveKeyCode == KeyEvent.KEYCODE_MINUS -> if (effShift) '_' else '-'
            effectiveKeyCode == KeyEvent.KEYCODE_EQUALS -> if (effShift) '+' else '='
            effectiveKeyCode == KeyEvent.KEYCODE_LEFT_BRACKET -> if (effShift) '{' else '['
            effectiveKeyCode == KeyEvent.KEYCODE_RIGHT_BRACKET -> if (effShift) '}' else ']'
            effectiveKeyCode == KeyEvent.KEYCODE_BACKSLASH -> if (effShift) '|' else '\\'
            effectiveKeyCode == KeyEvent.KEYCODE_SEMICOLON -> if (effShift) ':' else ';'
            effectiveKeyCode == KeyEvent.KEYCODE_APOSTROPHE -> if (effShift) '"' else '\''
            effectiveKeyCode == KeyEvent.KEYCODE_GRAVE -> if (effShift) '~' else '`'
            effectiveKeyCode == KeyEvent.KEYCODE_COMMA -> if (effShift) '<' else ','
            effectiveKeyCode == KeyEvent.KEYCODE_PERIOD -> if (effShift) '>' else '.'
            effectiveKeyCode == KeyEvent.KEYCODE_SLASH -> if (effShift) '?' else '/'
            effectiveKeyCode == KeyEvent.KEYCODE_AT -> '@'
            effectiveKeyCode == KeyEvent.KEYCODE_POUND -> '#'
            effectiveKeyCode == KeyEvent.KEYCODE_STAR -> '*'
            effectiveKeyCode == KeyEvent.KEYCODE_PLUS -> '+'
            else -> null
        }

        // If unicodeChar or dedicated symbol key requires Shift (e.g. '@', '#', '*', '+'), ensure Shift is set
        if (baseChar != null) {
            val charSpec = translateCharacterToRdpKeySpec(baseChar)
            if (charSpec.shiftRequired) {
                effShift = true
            }
        }

        val label = when {
            effectiveKeyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                ('A' + (effectiveKeyCode - KeyEvent.KEYCODE_A)).toString()
            effectiveKeyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                ('0' + (effectiveKeyCode - KeyEvent.KEYCODE_0)).toString()
            effectiveKeyCode == KeyEvent.KEYCODE_SPACE -> "SPACE"
            baseChar != null && baseChar != ' ' -> baseChar.toString()
            else -> try {
                KeyEvent.keyCodeToString(effectiveKeyCode).removePrefix("KEYCODE_")
            } catch (_: Throwable) {
                "KEY_$effectiveKeyCode"
            }
        }

        val rdpScan = if (baseChar != null) {
            translateCharacterToRdpKeySpec(baseChar).scancode
        } else {
            approximatePcScancode(effectiveKeyCode)
        }
        val vncSym = baseChar?.code ?: (0xFF00 + (effectiveKeyCode and 0xFF))

        val (sshEscDisplay, sshPayload) = when {
            matchedMapping != null && matchedMapping.customSequence.isNotEmpty() -> {
                matchedMapping.customSequence.replace("\n", "\\n") to matchedMapping.customSequence
            }
            effCtrl && baseChar != null && baseChar.lowercaseChar() in 'a'..'z' -> {
                val ctrlByte = (baseChar.lowercaseChar() - 'a' + 1)
                "^${baseChar.uppercaseChar()} (0x%02X)".format(ctrlByte) to ctrlByte.toChar().toString()
            }
            effAlt && baseChar != null -> {
                "\\e$baseChar" to "\u001b$baseChar"
            }
            baseChar != null -> {
                "'$baseChar' (0x%02X)".format(baseChar.code) to baseChar.toString()
            }
            else -> {
                "<$label>" to ""
            }
        }

        return TranslatedKeyPacket(
            originalKeyCode = keyCode,
            effectiveKeyCode = effectiveKeyCode,
            keyLabel = matchedMapping?.targetKeyLabel ?: label,
            ctrlActive = effCtrl,
            altActive = effAlt,
            shiftActive = effShift,
            superActive = effSuper,
            rdpScancodeHex = "0x%02X".format(rdpScan),
            rdpExtended = false,
            vncKeySymHex = "0x%04X".format(vncSym),
            vncKeySymName = if (baseChar != null) "XK_${baseChar}" else "XK_$label",
            sshAnsiEscape = sshEscDisplay,
            sshRawPayload = sshPayload,
            spiceScancodeHex = "%02X / %02X".format(rdpScan, rdpScan or 0x80),
            appliedMappingName = matchedMapping?.name,
            printableChar = if (!effCtrl && !effAlt && !effSuper) baseChar else null
        )
    }

    /**
     * Translates any character (typed via Android Soft Keyboard / IME or pasted text)
     * into its exact IBM PC Set-1 scancode + Shift state, or Unicode input fallback.
     */
    fun translateCharacterToRdpKeySpec(ch: Char): RdpCharKeySpec {
        return when (ch) {
            in 'a'..'z' -> RdpCharKeySpec(scancode = letterScancode(ch), shiftRequired = false)
            in 'A'..'Z' -> RdpCharKeySpec(scancode = letterScancode(ch.lowercaseChar()), shiftRequired = true)
            '1' -> RdpCharKeySpec(0x02, false)
            '!' -> RdpCharKeySpec(0x02, true)
            '2' -> RdpCharKeySpec(0x03, false)
            '@' -> RdpCharKeySpec(0x03, true)
            '3' -> RdpCharKeySpec(0x04, false)
            '#' -> RdpCharKeySpec(0x04, true)
            '4' -> RdpCharKeySpec(0x05, false)
            '$' -> RdpCharKeySpec(0x05, true)
            '5' -> RdpCharKeySpec(0x06, false)
            '%' -> RdpCharKeySpec(0x06, true)
            '6' -> RdpCharKeySpec(0x07, false)
            '^' -> RdpCharKeySpec(0x07, true)
            '7' -> RdpCharKeySpec(0x08, false)
            '&' -> RdpCharKeySpec(0x08, true)
            '8' -> RdpCharKeySpec(0x09, false)
            '*' -> RdpCharKeySpec(0x09, true)
            '9' -> RdpCharKeySpec(0x0A, false)
            '(' -> RdpCharKeySpec(0x0A, true)
            '0' -> RdpCharKeySpec(0x0B, false)
            ')' -> RdpCharKeySpec(0x0B, true)
            '-' -> RdpCharKeySpec(0x0C, false)
            '_' -> RdpCharKeySpec(0x0C, true)
            '=' -> RdpCharKeySpec(0x0D, false)
            '+' -> RdpCharKeySpec(0x0D, true)
            '\b' -> RdpCharKeySpec(0x0E, false)
            '\t' -> RdpCharKeySpec(0x0F, false)
            '\n', '\r' -> RdpCharKeySpec(0x1C, false)
            '[' -> RdpCharKeySpec(0x1A, false)
            '{' -> RdpCharKeySpec(0x1A, true)
            ']' -> RdpCharKeySpec(0x1B, false)
            '}' -> RdpCharKeySpec(0x1B, true)
            ';' -> RdpCharKeySpec(0x27, false)
            ':' -> RdpCharKeySpec(0x27, true)
            '\'' -> RdpCharKeySpec(0x28, false)
            '"' -> RdpCharKeySpec(0x28, true)
            '`' -> RdpCharKeySpec(0x29, false)
            '~' -> RdpCharKeySpec(0x29, true)
            '\\' -> RdpCharKeySpec(0x2B, false)
            '|' -> RdpCharKeySpec(0x2B, true)
            ',' -> RdpCharKeySpec(0x33, false)
            '<' -> RdpCharKeySpec(0x33, true)
            '.' -> RdpCharKeySpec(0x34, false)
            '>' -> RdpCharKeySpec(0x34, true)
            '/' -> RdpCharKeySpec(0x35, false)
            '?' -> RdpCharKeySpec(0x35, true)
            ' ' -> RdpCharKeySpec(0x39, false)
            else -> RdpCharKeySpec(
                scancode = 0,
                shiftRequired = false,
                useUnicodeEvent = true,
                unicodeCode = ch.code
            )
        }
    }

    private fun letterScancode(lower: Char): Int = when (lower) {
        'q' -> 0x10; 'w' -> 0x11; 'e' -> 0x12; 'r' -> 0x13; 't' -> 0x14
        'y' -> 0x15; 'u' -> 0x16; 'i' -> 0x17; 'o' -> 0x18; 'p' -> 0x19
        'a' -> 0x1E; 's' -> 0x1F; 'd' -> 0x20; 'f' -> 0x21; 'g' -> 0x22
        'h' -> 0x23; 'j' -> 0x24; 'k' -> 0x25; 'l' -> 0x26
        'z' -> 0x2C; 'x' -> 0x2D; 'c' -> 0x2E; 'v' -> 0x2F; 'b' -> 0x30
        'n' -> 0x31; 'm' -> 0x32
        else -> 0x39
    }

    fun approximatePcScancode(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_Q -> 0x10
            KeyEvent.KEYCODE_W -> 0x11
            KeyEvent.KEYCODE_E -> 0x12
            KeyEvent.KEYCODE_R -> 0x13
            KeyEvent.KEYCODE_T -> 0x14
            KeyEvent.KEYCODE_Y -> 0x15
            KeyEvent.KEYCODE_U -> 0x16
            KeyEvent.KEYCODE_I -> 0x17
            KeyEvent.KEYCODE_O -> 0x18
            KeyEvent.KEYCODE_P -> 0x19
            KeyEvent.KEYCODE_A -> 0x1E
            KeyEvent.KEYCODE_S -> 0x1F
            KeyEvent.KEYCODE_D -> 0x20
            KeyEvent.KEYCODE_F -> 0x21
            KeyEvent.KEYCODE_G -> 0x22
            KeyEvent.KEYCODE_H -> 0x23
            KeyEvent.KEYCODE_J -> 0x24
            KeyEvent.KEYCODE_K -> 0x25
            KeyEvent.KEYCODE_L -> 0x26
            KeyEvent.KEYCODE_Z -> 0x2C
            KeyEvent.KEYCODE_X -> 0x2D
            KeyEvent.KEYCODE_C -> 0x2E
            KeyEvent.KEYCODE_V -> 0x2F
            KeyEvent.KEYCODE_B -> 0x30
            KeyEvent.KEYCODE_N -> 0x31
            KeyEvent.KEYCODE_M -> 0x32
            KeyEvent.KEYCODE_SPACE -> 0x39
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x02 + (keyCode - KeyEvent.KEYCODE_1)
            KeyEvent.KEYCODE_0 -> 0x0B
            KeyEvent.KEYCODE_MINUS -> 0x0C
            KeyEvent.KEYCODE_EQUALS -> 0x0D
            KeyEvent.KEYCODE_PLUS -> 0x0D
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x1A
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x1B
            KeyEvent.KEYCODE_SEMICOLON -> 0x27
            KeyEvent.KEYCODE_APOSTROPHE -> 0x28
            KeyEvent.KEYCODE_GRAVE -> 0x29
            KeyEvent.KEYCODE_BACKSLASH -> 0x2B
            KeyEvent.KEYCODE_COMMA -> 0x33
            KeyEvent.KEYCODE_PERIOD -> 0x34
            KeyEvent.KEYCODE_SLASH -> 0x35
            KeyEvent.KEYCODE_AT -> 0x03
            KeyEvent.KEYCODE_POUND -> 0x04
            KeyEvent.KEYCODE_STAR -> 0x09
            KeyEvent.KEYCODE_NUMPAD_0 -> 0x52
            KeyEvent.KEYCODE_NUMPAD_1 -> 0x4F
            KeyEvent.KEYCODE_NUMPAD_2 -> 0x50
            KeyEvent.KEYCODE_NUMPAD_3 -> 0x51
            KeyEvent.KEYCODE_NUMPAD_4 -> 0x4B
            KeyEvent.KEYCODE_NUMPAD_5 -> 0x4C
            KeyEvent.KEYCODE_NUMPAD_6 -> 0x4D
            KeyEvent.KEYCODE_NUMPAD_7 -> 0x47
            KeyEvent.KEYCODE_NUMPAD_8 -> 0x48
            KeyEvent.KEYCODE_NUMPAD_9 -> 0x49
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x4A
            KeyEvent.KEYCODE_NUMPAD_ADD -> 0x4E
            KeyEvent.KEYCODE_NUMPAD_DOT -> 0x53
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x37
            else -> 0x39
        }
    }
}
