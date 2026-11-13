// SPDX-License-Identifier: Apache-2.0
package io.github.jf0510.sill

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in live gate: only a previously paired, loopback synthetic host. Never picks a user's Mac.
 */
@RunWith(AndroidJUnit4::class)
class DeviceInteropTest {
    @Test
    fun syntheticHostVideoClipboardCompositionAndDisplaySwitch() {
        val port = InstrumentationRegistry.getArguments().getString("syntheticPort")?.toIntOrNull()
        assumeTrue(
            "Run with -e syntheticPort and an adb reverse to SillHost --synthetic",
            port != null,
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            scenario ->
            lateinit var client: SillClient
            scenario.onActivity { activity ->
                client = ViewModelProvider(activity)[SillClient::class.java]
                val host =
                    client.state.value.saved.lastOrNull {
                        it.host == "127.0.0.1" && it.port == port
                    }
                assertNotNull("Pair with the synthetic host before the live gate", host)
                client.choose(host!!)
            }
            fun await(check: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 15000
                while (!check() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
                assertTrue("Timed out waiting for the synthetic host", check())
            }
            await { client.state.value.connected && client.state.value.videoWidth > 0 }
            assertTrue(client.state.value.desktopDisplays >= 2)
            val display = client.state.value.desktopDisplay
            scenario.onActivity { client.desktop() }
            await {
                client.state.value.desktopDisplay != display && client.state.value.videoWidth > 0
            }
            await { client.state.value.clipboardGeneration >= 1 }
            scenario.onActivity { activity ->
                activity
                    .getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Test", "Android → Mac 你好 🌍"))
                client.paste()
            }
            await { client.state.value.clipboardNote?.startsWith("Pasted on") == true }
            scenario.onActivity { activity ->
                val input = TouchSurface(activity, client).onCreateInputConnection(EditorInfo())
                assertTrue(input.setComposingText("ni", 1))
                assertTrue(input.setComposingText("你", 1))
                assertTrue(input.commitText("你好🌍", 1))
                assertTrue(input.finishComposingText()) // Must not send committed text twice.
                val hardware = HardwareInput(client)
                val now = SystemClock.uptimeMillis()
                assertTrue(
                    hardware.handle(
                        KeyEvent(
                            now,
                            now,
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_A,
                            0,
                            KeyEvent.META_CTRL_ON,
                            0,
                            0,
                        )
                    )
                )
                hardware.release()
            }
            scenario.onActivity { client.send(17, Wire.json("maxFPS" to 60)) }
            await {
                client.state.value.settingsNote == null &&
                    client.state.value.settings?.optJSONObject("settings")?.optInt("maxFPS") == 60
            }
            val hostClipboard = InstrumentationRegistry.getArguments().getString("hostClipboard")
            if (hostClipboard != null) {
                await { client.state.value.clipboardNote?.startsWith("Copied from") == true }
                scenario.onActivity { activity ->
                    assertEquals(
                        hostClipboard,
                        activity
                            .getSystemService(ClipboardManager::class.java)
                            .primaryClip
                            ?.getItemAt(0)
                            ?.text
                            ?.toString(),
                    )
                }
            }
            Thread.sleep(500)
            assertTrue(client.state.value.connected)
            assertNull(client.state.value.audioNote)
            scenario.onActivity { client.disconnect() }
        }
    }
}
