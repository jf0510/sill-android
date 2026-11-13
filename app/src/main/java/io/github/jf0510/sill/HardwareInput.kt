// SPDX-License-Identifier: Apache-2.0
package io.github.jf0510.sill

import android.view.KeyEvent

/** USB HID usages; repeat comes from the host when it advertises keyRepeat. */
class HardwareInput(private val client: SillClient) {
    private val held = mutableMapOf<Int, Int>()

    fun handle(e: KeyEvent): Boolean {
        if (
            e.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD ||
                !client.state.value.connected
        )
            return false
        val hid = usage(e.keyCode) ?: return false
        val mods = modifiers(e) or client.modifierKeys
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0 || client.state.value.keyRepeat < 1) {
                    held[e.keyCode] = hid
                    client.input(Wire.key(hid, true, mods))
                }
            }
            KeyEvent.ACTION_UP -> {
                held.remove(e.keyCode)
                client.input(Wire.key(hid, false, mods))
            }
            else -> return false
        }
        return true
    }

    fun release() {
        held.values.toList().forEach { client.input(Wire.key(it, false)) }
        held.clear()
    }

    companion object {
        fun modifiers(e: KeyEvent): Long =
            (if (e.isShiftPressed) 1L shl 17 else 0) or
                (if (e.isCtrlPressed) 1L shl 18 else 0) or
                (if (e.isAltPressed) 1L shl 19 else 0) or
                (if (e.isMetaPressed) 1L shl 20 else 0) or
                (if (e.isCapsLockOn) 1L shl 16 else 0)

        fun usage(key: Int): Int? =
            when (key) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 4 + key - KeyEvent.KEYCODE_A
                in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 30 + key - KeyEvent.KEYCODE_1
                KeyEvent.KEYCODE_0 -> 39
                in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 58 + key - KeyEvent.KEYCODE_F1
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> 40
                KeyEvent.KEYCODE_ESCAPE -> 41
                KeyEvent.KEYCODE_DEL -> 42
                KeyEvent.KEYCODE_TAB -> 43
                KeyEvent.KEYCODE_SPACE -> 44
                KeyEvent.KEYCODE_MINUS -> 45
                KeyEvent.KEYCODE_EQUALS -> 46
                KeyEvent.KEYCODE_LEFT_BRACKET -> 47
                KeyEvent.KEYCODE_RIGHT_BRACKET -> 48
                KeyEvent.KEYCODE_BACKSLASH -> 49
                KeyEvent.KEYCODE_SEMICOLON -> 51
                KeyEvent.KEYCODE_APOSTROPHE -> 52
                KeyEvent.KEYCODE_GRAVE -> 53
                KeyEvent.KEYCODE_COMMA -> 54
                KeyEvent.KEYCODE_PERIOD -> 55
                KeyEvent.KEYCODE_SLASH -> 56
                KeyEvent.KEYCODE_CAPS_LOCK -> 57
                KeyEvent.KEYCODE_INSERT -> 73
                KeyEvent.KEYCODE_MOVE_HOME -> 74
                KeyEvent.KEYCODE_PAGE_UP -> 75
                KeyEvent.KEYCODE_FORWARD_DEL -> 76
                KeyEvent.KEYCODE_MOVE_END -> 77
                KeyEvent.KEYCODE_PAGE_DOWN -> 78
                KeyEvent.KEYCODE_DPAD_RIGHT -> 79
                KeyEvent.KEYCODE_DPAD_LEFT -> 80
                KeyEvent.KEYCODE_DPAD_DOWN -> 81
                KeyEvent.KEYCODE_DPAD_UP -> 82
                KeyEvent.KEYCODE_CTRL_LEFT -> 224
                KeyEvent.KEYCODE_SHIFT_LEFT -> 225
                KeyEvent.KEYCODE_ALT_LEFT -> 226
                KeyEvent.KEYCODE_META_LEFT -> 227
                KeyEvent.KEYCODE_CTRL_RIGHT -> 228
                KeyEvent.KEYCODE_SHIFT_RIGHT -> 229
                KeyEvent.KEYCODE_ALT_RIGHT -> 230
                KeyEvent.KEYCODE_META_RIGHT -> 231
                else -> null
            }
    }
}
