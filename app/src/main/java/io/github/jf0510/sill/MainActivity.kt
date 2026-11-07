// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

object Palette {
    val bar = Color(0xff16181c)
    val control = Color(0xff23262c)
    val open = Color(0xff2c3550)
    val accent = Color(0xff9bb7ff)
    val text = Color(0xffeef0f3)
    val label = Color(0xffc4c9d1)
    val muted = Color(0xffa4abb5)
    val panel = Color(0xff1f2126)
}

class MainActivity : ComponentActivity() {
    private val client: SillClient by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("preview", false)) client.preview()
        if (BuildConfig.DEBUG && intent.hasExtra("usbHostPort"))
            client.usbDiagnostic(intent.getIntExtra("usbHostPort", 0))
        val homePreview =
            if (BuildConfig.DEBUG && intent.getBooleanExtra("previewHome", false))
                ClientState(
                    status = "Choose a Mac to connect.",
                    hosts =
                        listOf(
                            MacHost("MacBook Pro", "192.0.2.1", 7455),
                            MacHost("Mac mini", "192.0.2.2", 7455),
                        ),
                )
            else null
        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Palette.accent,
                        secondary = Palette.accent,
                        secondaryContainer = Palette.open,
                        onSecondaryContainer = Palette.text,
                        background = Color.Black,
                        surface = Palette.bar,
                        onSurface = Palette.text,
                        surfaceVariant = Palette.control,
                        onSurfaceVariant = Palette.muted,
                        surfaceContainer = Palette.bar,
                        surfaceContainerHigh = Palette.control,
                        surfaceContainerHighest = Palette.control,
                        surfaceTint = Color.Transparent,
                    )
            ) {
                val liveState by client.state.collectAsState()
                val s = homePreview ?: liveState
                CompositionLocalProvider(LocalContentColor provides Palette.text) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color.Black)
                            .windowInsetsPadding(
                                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            )
                    ) {
                        if (s.connected) StreamScreen(client, s) else ConnectScreen(client, s)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        client.foreground()
    }

    override fun onStop() {
        client.background()
        super.onStop()
    }
}

@Composable
private fun ConnectScreen(client: SillClient, s: ClientState) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var tiles by rememberSaveable { mutableStateOf(true) }
    var address by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("7455") }
    var code by rememberSaveable { mutableStateOf("") }
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val compactColumn = (LocalConfiguration.current.screenWidthDp - 32).coerceIn(200, 380).dp
    val connectionScroll = rememberScrollState()
    LaunchedEffect(s.error) { if (s.error != null) connectionScroll.animateScrollTo(0) }
    BackHandler(adding || s.pairing != null) {
        adding = false
        client.cancelPair()
    }
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.align(Alignment.Center)
                .widthIn(max = compactColumn)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(top = 16.dp, bottom = 64.dp)
                .verticalScroll(connectionScroll),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s.pairing != null) "Pair with ${s.pairing.name}"
                    else if (adding) "Add a Mac" else "Connect to a Mac",
                    Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = {
                        if (s.pairing != null || adding) {
                            client.cancelPair()
                            adding = false
                        } else tiles = !tiles
                    }
                ) {
                    Icon(
                        if (s.pairing != null || adding) Icons.Outlined.Close
                        else if (tiles) Icons.Outlined.List else Icons.Outlined.GridView,
                        if (s.pairing != null || adding) "Cancel" else "Change view",
                        tint = Palette.accent,
                    )
                }
            }
            Text(s.status, color = Palette.muted, fontSize = 13.sp)
            s.error?.let { Text(it, color = Color(0xffffb4ab), fontSize = 13.sp) }
            if (s.pairing != null) {
                Text(
                    "In Sill on your Mac, choose Pair iPhone or iPad… and enter its code here.",
                    color = Palette.muted,
                    fontSize = 14.sp,
                )
                OutlinedTextField(
                    code,
                    { code = PairingCodeInput.digits(it) },
                    label = { Text("Pairing code") },
                    placeholder = { Text("0000 0000 0000") },
                    visualTransformation = PairingCodeInput,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !s.busy,
                )
                Button(
                    onClick = { client.pair(code) },
                    enabled = !s.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Pair with Mac")
                }
            } else if (adding) {
                Text(
                    "Use your Mac's local IP address and Sill port.",
                    color = Palette.muted,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    address,
                    { address = it },
                    label = { Text("Mac address") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    port,
                    { port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        client.add(address, port.toInt())
                        adding = false
                    },
                    enabled = address.isNotBlank() && port.toIntOrNull() in 1..65535,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue")
                }
            } else {
                val nearby =
                    s.hosts.filter { h -> s.saved.none { it.host == h.host && it.port == h.port } }
                if (tiles) {
                    // Like the official ungrouped grid, saved and nearby Macs share the row.
                    HostGroup(s.saved + nearby, true, s.busy, client)
                } else {
                    if (s.saved.isNotEmpty()) SectionLabel("SAVED MACS")
                    HostGroup(s.saved, false, s.busy, client)
                    if (nearby.isNotEmpty()) SectionLabel("NEARBY")
                    HostGroup(nearby, false, s.busy, client)
                }
                if (s.hosts.isEmpty() && s.saved.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Palette.bar)
                            .padding(22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Computer,
                            null,
                            tint = Palette.muted,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            "Open Sill on your Mac.\nIt will appear here.",
                            color = Palette.muted,
                            fontSize = 14.sp,
                        )
                    }
                }
                Text(
                    "Your Mac has to be on the same Wi-Fi network as this ${if(tablet) "tablet" else "phone"}.",
                    color = Palette.muted,
                    fontSize = 13.sp,
                )
                TextButton(
                    onClick = {
                        adding = true
                        client.clearError()
                    },
                    enabled = !s.busy,
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add a Mac…")
                }
            }
            if (s.reconnecting)
                TextButton(onClick = { client.disconnect() }) { Text("Cancel reconnect") }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.accent)
        }
        Text(
            "Sill · Unofficial Android client",
            Modifier.align(Alignment.BottomCenter).padding(24.dp),
            color = Palette.muted,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun HostGroup(hosts: List<MacHost>, tiles: Boolean, busy: Boolean, client: SillClient) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns =
            if (tiles && maxWidth >= 320.dp && LocalConfiguration.current.fontScale < 1.5f) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            hosts.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { host ->
                        Box(Modifier.weight(1f)) {
                            HostCard(
                                host,
                                tiles,
                                busy,
                                { client.choose(host) },
                                if (host.pin != null) ({ client.forget(host) }) else null,
                            )
                        }
                    }
                    if (columns == 2 && row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HostCard(
    host: MacHost,
    tiles: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    forget: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    if (tiles) {
        Box {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.control)
                    .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(12.dp))
                    .combinedClickable(
                        enabled = !busy,
                        onClick = onClick,
                        onLongClick = { menu = true },
                    )
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .height(92.dp)
                        .background(Palette.muted.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.DesktopWindows,
                        null,
                        Modifier.size(24.dp),
                        tint = Color.White.copy(alpha = .85f),
                    )
                }
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        host.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (host.pin != null) "Paired · Connect" else "Nearby · Pair",
                        fontSize = 12.sp,
                        color = Palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (host.pin != null) "Connect" else "Pair with Mac") },
                    onClick = {
                        menu = false
                        onClick()
                    },
                )
                if (forget != null)
                    DropdownMenuItem(
                        text = { Text("Forget this Mac") },
                        onClick = {
                            menu = false
                            forget()
                        },
                    )
            }
        }
        return
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.bar)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(14.dp)
    ) {
        if (tiles)
            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.control),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DesktopWindows,
                    null,
                    Modifier.size(42.dp),
                    tint = Palette.muted,
                )
            }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!tiles) {
                Icon(Icons.Outlined.Computer, null, tint = Palette.accent)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).padding(top = if (tiles) 10.dp else 0.dp)) {
                Text(
                    host.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (host.pin != null) "Paired · Connect" else "Nearby · Pair",
                    fontSize = 12.sp,
                    color = Palette.muted,
                )
            }
            if (forget != null)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreHoriz, "Mac options", tint = Palette.muted)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Forget this Mac") },
                            onClick = {
                                menu = false
                                forget()
                            },
                        )
                    }
                }
        }
    }
}

@Composable
private fun StreamScreen(client: SillClient, s: ClientState) {
    val portrait =
        LocalConfiguration.current.screenHeightDp > LocalConfiguration.current.screenWidthDp
    val phone = portrait && LocalConfiguration.current.smallestScreenWidthDp < 600
    var panel by rememberSaveable { mutableStateOf("") }
    var scale by rememberSaveable { mutableStateOf(1f) }
    var view by remember { mutableStateOf<TouchSurface?>(null) }
    var modifiers by rememberSaveable { mutableLongStateOf(0L) }
    var exit by remember { mutableStateOf(false) }
    BackHandler { if (panel.isNotEmpty()) panel = "" else exit = true }
    fun toggle(name: String) {
        panel = if (panel == name) "" else name
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val phoneButtonWidth = (maxWidth - 60.dp) / 5
        val half =
            if (phone)
                ((maxWidth - 16.dp) / 1.6f).coerceAtMost((maxHeight - 360.dp).coerceAtLeast(0.dp)) +
                    16.dp
            else maxHeight / 2
        val barHeight = if (phone) 62.dp else if (portrait) 78.dp else 86.dp
        Column(Modifier.fillMaxSize()) {
            if (!portrait) Toolbar(s, client, false, panel, { toggle(it) }, { view?.keyboard() })
            Box(
                Modifier.fillMaxWidth()
                    .then(if (portrait) Modifier.height(half) else Modifier.weight(1f))
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { ctx -> TouchSurface(ctx, client).also { view = it } },
                    update = { it.dimensions(s.videoWidth, s.videoHeight) },
                    modifier = Modifier.fillMaxSize(),
                )
                if (s.videoWidth == 0)
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.DesktopWindows,
                            null,
                            Modifier.size(38.dp),
                            tint = Palette.muted,
                        )
                        Text(
                            if (s.active != null || s.desktop) "Waiting for the picture…"
                            else "Pick a window or the desktop",
                            color = Palette.muted,
                            fontSize = 15.sp,
                        )
                    }
                if (s.offConsole)
                    Box(
                        Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Another user started controlling this Mac.\nLog in to your account to reactivate screen sharing with Sill.",
                            Modifier.padding(30.dp),
                            color = Palette.text,
                        )
                    }
                (s.error ?: s.resolutionNotice)?.let {
                    Text(
                        it,
                        Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Palette.bar)
                            .padding(12.dp),
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                }
            }
            if (portrait)
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .background(Palette.bar)
                        .padding(horizontal = 14.dp)
                        .padding(top = if (phone) 10.dp else 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (phone) {
                        Row(
                            Modifier.fillMaxWidth().height(62.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            listOf("Apps", "Text size", "Keyboard", "Desktop", "Settings")
                                .forEach { label ->
                                    val icon =
                                        when (label) {
                                            "Apps" -> Icons.Outlined.Search
                                            "Keyboard" -> Icons.Outlined.Keyboard
                                            "Desktop" -> Icons.Outlined.DesktopWindows
                                            "Settings" -> Icons.Outlined.Settings
                                            else -> null
                                        }
                                    val selected =
                                        when (label) {
                                            "Apps" -> panel == "apps"
                                            "Text size" -> panel == "scale"
                                            "Desktop" -> s.desktop
                                            "Settings" -> panel == "settings"
                                            else -> false
                                        }
                                    Column(
                                        Modifier.weight(1f)
                                            .height(50.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                if (selected) Palette.open else Palette.control
                                            )
                                            .clickable {
                                                when (label) {
                                                    "Apps" -> toggle("apps")
                                                    "Text size" -> toggle("scale")
                                                    "Keyboard" -> view?.keyboard()
                                                    "Desktop" -> client.select(null)
                                                    else -> toggle("settings")
                                                }
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        if (icon != null)
                                            Icon(
                                                icon,
                                                label,
                                                Modifier.size(20.dp),
                                                tint =
                                                    if (selected) Palette.accent else Palette.text,
                                            )
                                        else Text("Aa", fontSize = 20.sp)
                                        Text(
                                            label,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            color = Palette.label,
                                        )
                                    }
                                }
                        }
                        Row(
                            Modifier.fillMaxWidth().height(62.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LazyRow(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(s.windows, key = { it.id }) { w ->
                                    WindowThumb(
                                        w,
                                        s,
                                        true,
                                        { client.select(w.id) },
                                        { client.windowCommand(w.id, it) },
                                        phone = true,
                                    )
                                }
                            }
                            if (s.menus != null)
                                Column(
                                    Modifier.width(phoneButtonWidth)
                                        .height(50.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Palette.control)
                                        .clickable {
                                            client.send(27, JSONObject())
                                            toggle("menus")
                                        },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(Icons.Outlined.Menu, "Menus", Modifier.size(20.dp))
                                    Text("Menus", fontSize = 10.sp)
                                }
                        }
                    } else Toolbar(s, client, true, panel, { toggle(it) }, { view?.keyboard() })
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val keys =
                            if (phone)
                                listOf(
                                    "esc" to 41,
                                    "tab" to 43,
                                    "ctrl" to -18,
                                    "opt" to -19,
                                    "cmd" to -20,
                                    "shift" to -17,
                                )
                            else
                                listOf(
                                    "esc" to 41,
                                    "tab" to 43,
                                    "⌃" to -18,
                                    "⌥" to -19,
                                    "⇧" to -17,
                                    "⌘" to -20,
                                    "←" to 80,
                                    "↓" to 81,
                                    "↑" to 82,
                                    "→" to 79,
                                    "⌫" to 42,
                                )
                        keys.forEach { (label, key) ->
                            val mask = if (key < 0) 1L shl -key else 0L
                            Box(
                                Modifier.weight(1f)
                                    .height(if (phone) 44.dp else 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (mask != 0L && modifiers and mask != 0L) Palette.open
                                        else Palette.control
                                    )
                                    .clickable {
                                        if (key < 0) modifiers = modifiers xor mask
                                        else {
                                            client.key(key, modifiers)
                                            modifiers = 0
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    fontSize = if (phone) 14.sp else 17.sp,
                                    color = Palette.label,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        if (!phone)
                            Box(
                                Modifier.weight(1.3f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Palette.control)
                                    .clickable { view?.keyboard() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Keyboard, "Show keyboard", tint = Palette.label)
                            }
                    }
                    AndroidView(
                        factory = { TouchSurface(it, client, true) },
                        modifier =
                            Modifier.fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = .07f),
                                    RoundedCornerShape(16.dp),
                                ),
                    )
                }
        }
        if (panel.isNotEmpty()) {
            val top = if (portrait) half + 12.dp + barHeight + 8.dp else barHeight + 8.dp
            if (portrait) Box(Modifier.fillMaxWidth().height(half).clickable { panel = "" })
            Box(
                Modifier.fillMaxSize()
                    .padding(top = if (portrait) half + barHeight + 12.dp else barHeight)
                    .background(
                        if (panel == "apps") Color.Black.copy(alpha = .58f) else Color.Transparent
                    )
                    .clickable { panel = "" }
            )
            Box(
                Modifier.align(if (panel == "apps") Alignment.TopStart else Alignment.TopEnd)
                    .padding(
                        top = top,
                        start = if (portrait) 14.dp else 22.dp,
                        end = if (portrait) 14.dp else 22.dp,
                        bottom = 14.dp,
                    )
                    .widthIn(max = if (panel == "apps") 380.dp else 360.dp)
                    .fillMaxWidth()
            ) {
                when (panel) {
                    "apps" ->
                        AppDrawer(
                            s,
                            {
                                client.select(it)
                                panel = ""
                            },
                            {
                                client.launch(it)
                                panel = ""
                            },
                        )
                    "settings" -> SettingsPanel(s, client, { panel = "" }, { client.disconnect() })
                    "scale" ->
                        Panel {
                            Text("Text size", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Fit the Mac window to this picture.",
                                fontSize = 13.sp,
                                color = Palette.muted,
                            )
                            Text(
                                "${"%.1f".format(scale)}×",
                                color = Palette.accent,
                                fontSize = 26.sp,
                            )
                            Slider(
                                value = scale,
                                onValueChange = { scale = it },
                                valueRange = .8f..2f,
                                steps = 11,
                                onValueChangeFinished = {
                                    client.textScale = scale.toDouble()
                                    client.sendViewport()
                                },
                            )
                            TextButton(
                                onClick = {
                                    client.textScale = null
                                    client.sendViewport()
                                    panel = ""
                                }
                            ) {
                                Text("Off — keep the Mac window's size")
                            }
                        }
                    "menus" -> MenuPanel(s, client)
                }
            }
        }
    }
    if (exit)
        AlertDialog(
            onDismissRequest = { exit = false },
            title = { Text("Disconnect from ${s.macName}?") },
            text = { Text("You can reconnect from the Mac list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        exit = false
                        client.disconnect()
                    }
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = { TextButton(onClick = { exit = false }) { Text("Cancel") } },
        )
}

@Composable
private fun Toolbar(
    s: ClientState,
    client: SillClient,
    portrait: Boolean,
    panel: String,
    toggle: (String) -> Unit,
    keyboard: () -> Unit,
) {
    val compact = portrait
    Row(
        Modifier.fillMaxWidth()
            .height(if (compact) 78.dp else 86.dp)
            .background(Palette.bar)
            .padding(horizontal = if (compact) 0.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BarButton("Apps", Icons.Outlined.Search, panel == "apps", compact) { toggle("apps") }
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 16.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            items(s.windows, key = { it.id }) { w ->
                WindowThumb(
                    w,
                    s,
                    compact,
                    { client.select(w.id) },
                    { action -> client.windowCommand(w.id, action) },
                )
            }
        }
        if (s.menus != null && LocalConfiguration.current.screenWidthDp > 850)
            BarButton("Menus", Icons.Outlined.Menu, panel == "menus", compact) {
                client.send(27, JSONObject())
                toggle("menus")
            }
        BarButton("Text size", null, panel == "scale", compact) { toggle("scale") }
        if (!portrait) BarButton("Keyboard", Icons.Outlined.Keyboard, false, compact, keyboard)
        BarButton("Desktop", Icons.Outlined.DesktopWindows, s.desktop, compact) {
            client.select(null)
        }
        BarButton("Settings", Icons.Outlined.Settings, panel == "settings", compact) {
            toggle("settings")
        }
    }
}

@Composable
private fun BarButton(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.size(if (compact) 64.dp else 66.dp, if (compact) 58.dp else 66.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Palette.open else Palette.control)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null)
            Icon(
                icon,
                label,
                Modifier.size(24.dp),
                tint = if (selected) Palette.accent else Palette.text,
            )
        else Text("Aa", fontSize = 24.sp, color = if (selected) Palette.accent else Palette.text)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = Palette.label, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowThumb(
    w: MacWindow,
    s: ClientState,
    compact: Boolean,
    onClick: () -> Unit,
    command: (String) -> Unit,
    phone: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(
                    if (phone) 80.dp else if (compact) 92.dp else 104.dp,
                    if (phone) 50.dp else if (compact) 58.dp else 66.dp,
                )
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xff26282d))
                .border(
                    if (s.active == w.id) 2.dp else 1.dp,
                    if (s.active == w.id) Palette.accent else Color.White.copy(alpha = .12f),
                    RoundedCornerShape(10.dp),
                )
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
        ) {
            s.thumbnails[w.id]?.let {
                Image(
                    it.asImageBitmap(),
                    w.title,
                    Modifier.fillMaxSize().padding(2.dp),
                    contentScale = ContentScale.Fit,
                )
            }
                ?: Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(9.dp).background(Color(0xff34373d)))
                    Text(
                        w.title,
                        Modifier.padding(7.dp),
                        fontSize = 10.sp,
                        color = Palette.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            Box(
                Modifier.align(Alignment.BottomStart)
                    .padding(3.dp)
                    .size(23.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.control),
                contentAlignment = Alignment.Center,
            ) {
                s.icons[w.bundle]?.let { Image(it.asImageBitmap(), w.app, Modifier.fillMaxSize()) }
                    ?: Text(
                        w.app.take(2),
                        fontSize = 10.sp,
                        color = Palette.text,
                        fontWeight = FontWeight.Bold,
                    )
            }
        }
        DropdownMenu(menu, { menu = false }) {
            listOf("Close" to "close", "Minimize" to "minimize", "Full Screen" to "fullScreen")
                .forEach { (label, a) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menu = false
                            command(a)
                        },
                    )
                }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(Palette.bar)
            .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(18.dp))
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, color = Palette.muted, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun AppDrawer(s: ClientState, select: (Long) -> Unit, launch: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.bar)
            .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text(s.macName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            search,
            { search = it },
            placeholder = { Text("Search apps and windows", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            item { SectionLabel("OPEN WINDOWS") }
            items(
                s.windows.filter { (it.app + it.title).contains(search, true) },
                key = { it.id },
            ) { w ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (s.active == w.id) Palette.control else Color.Transparent)
                        .clickable { select(w.id) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    s.icons[w.bundle]?.let { Image(it.asImageBitmap(), null, Modifier.size(30.dp)) }
                        ?: Icon(
                            Icons.Outlined.Window,
                            null,
                            Modifier.size(30.dp),
                            tint = Palette.muted,
                        )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(w.app, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            w.title,
                            fontSize = 12.sp,
                            color = Palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                SectionLabel("ALL APPS")
            }
            items(s.apps.filter { it.name.contains(search, true) }, key = { it.bundle }) { app ->
                Text(
                    app.name,
                    Modifier.fillMaxWidth().clickable { launch(app.bundle) }.padding(14.dp),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    s: ClientState,
    client: SillClient,
    close: () -> Unit,
    disconnect: () -> Unit,
) {
    var showLicenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (showLicenses) {
        val notices = remember {
            listOf("NOTICE.txt", "THIRD_PARTY_NOTICES.txt", "LICENSE.txt").joinToString("\n\n") {
                name ->
                context.assets.open(name).bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("About Sill for Android") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} · Unofficial Android client",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(notices, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }
    var reverse by remember { mutableStateOf(client.reverseScroll) }
    val settings = s.settings?.optJSONObject("settings")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.macName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            IconButton(onClick = close) {
                Icon(Icons.Outlined.Close, "Close settings", tint = Palette.muted)
            }
        }
        SectionLabel("STREAMING")
        if (settings == null)
            Text("Waiting for the Mac's settings…", fontSize = 13.sp, color = Palette.muted)
        else {
            var qualityOpen by remember { mutableStateOf(false) }
            val qualities =
                listOf(
                    "Low" to 4,
                    "Efficient" to 8,
                    "Balanced" to 15,
                    "High" to 25,
                    "Pro" to 40,
                    "Ultra" to 80,
                    "Extreme" to 150,
                )
            val mbps = settings.optInt("bitrate") / 1_000_000
            Text("Quality", fontSize = 14.sp)
            Box {
                TextButton(onClick = { qualityOpen = true }) {
                    Text(
                        "${qualities.firstOrNull { it.second==mbps }?.first ?: "Custom"} — $mbps Mbps"
                    )
                    Icon(Icons.Outlined.ExpandMore, null)
                }
                DropdownMenu(qualityOpen, { qualityOpen = false }) {
                    qualities.forEach { (name, value) ->
                        DropdownMenuItem(
                            text = { Text("$name — $value Mbps") },
                            onClick = {
                                qualityOpen = false
                                client.send(
                                    17,
                                    Wire.json(
                                        "token" to client.nextToken(),
                                        "bitrate" to value * 1_000_000,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            Text("Frame rate", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 120).forEach { fps ->
                    FilterChip(
                        selected = settings.optInt("maxFPS") == fps,
                        onClick = {
                            client.send(
                                17,
                                Wire.json("token" to client.nextToken(), "maxFPS" to fps),
                            )
                        },
                        label = { Text("$fps fps") },
                    )
                }
            }
            Text(
                "This device requests up to ${client.panelFPS} fps; the Mac setting above limits the stream.",
                fontSize = 12.sp,
                color = Palette.muted,
            )
            Text("Resolution", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Standard" to 1.0, "Retina" to 2.0).forEach { (name, value) ->
                    FilterChip(
                        selected = settings.optDouble("captureScale") == value,
                        onClick = { client.send(17, Wire.json("captureScale" to value)) },
                        label = { Text(name) },
                    )
                }
            }
            SettingSwitch("Prioritize speed", settings.optBoolean("prioritizeSpeed")) {
                client.send(17, Wire.json("prioritizeSpeed" to it))
            }
            if (s.settings.optBoolean("virtualDisplayAvailable"))
                SettingSwitch("Virtual display", settings.optBoolean("virtualDisplay")) {
                    client.send(17, Wire.json("virtualDisplay" to it))
                }
        }
        HorizontalDivider(color = Palette.control)
        SectionLabel("THIS DEVICE")
        SettingSwitch("Reverse scrolling", reverse) {
            reverse = it
            client.reverseScroll = it
        }
        Text(
            "Touch: tap to click, hold to right-click, swipe to scroll, pinch to zoom. On the trackpad, tap then hold and move to drag.",
            fontSize = 13.sp,
            color = Palette.muted,
        )
        HorizontalDivider(color = Palette.control)
        TextButton(onClick = { showLicenses = true }, modifier = Modifier.fillMaxWidth()) {
            Text("About and open-source licenses")
        }
        TextButton(onClick = disconnect, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Logout, null)
            Spacer(Modifier.width(8.dp))
            Text("Disconnect")
        }
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = value, onCheckedChange = change)
    }
}

@Composable
private fun MenuPanel(s: ClientState, client: SillClient) {
    val menu = s.menus
    Panel {
        Text("Menus", fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { client.send(27, JSONObject()) }) { Text("All menus") }
        val items = menu?.optJSONArray("items") ?: menu?.optJSONArray("menus")
        if (items != null)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optBoolean("separator")) HorizontalDivider()
                else
                    TextButton(
                        enabled =
                            item.optBoolean("enabled", true) && menu?.optBoolean("stale") != true,
                        onClick = {
                            client.send(
                                if (item.optBoolean("submenu")) 27 else 25,
                                Wire.json(
                                    "version" to menu?.optInt("version"),
                                    "id" to item.optString("id"),
                                    "title" to item.optString("title"),
                                    "token" to client.nextToken(),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(item.optString("title"), Modifier.weight(1f))
                        Text(
                            if (item.optBoolean("submenu")) "›" else item.optString("key"),
                            color = Palette.muted,
                        )
                    }
            }
        menu
            ?.optString("note")
            ?.takeIf { it.isNotEmpty() }
            ?.let { Text(it, color = Palette.muted, fontSize = 13.sp) }
    }
}
