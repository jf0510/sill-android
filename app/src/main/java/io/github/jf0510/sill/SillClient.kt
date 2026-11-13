// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class MacHost(
    val name: String,
    val host: String,
    val port: Int,
    val pin: String? = null,
    val recognition: String? = null,
    val routes: List<String> = emptyList(),
    val issuedAt: Double = 0.0,
) {
    val address
        get() = "$host:$port"
}

data class MacWindow(val id: Long, val title: String, val app: String, val bundle: String)

data class MacApp(val name: String, val bundle: String)

data class ClientState(
    val hosts: List<MacHost> = emptyList(),
    val saved: List<MacHost> = emptyList(),
    val status: String = "Looking for Macs…",
    val busy: Boolean = false,
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val pairing: MacHost? = null,
    val macName: String = "",
    val windows: List<MacWindow> = emptyList(),
    val apps: List<MacApp> = emptyList(),
    val thumbnails: Map<Long, Bitmap> = emptyMap(),
    val icons: Map<String, Bitmap> = emptyMap(),
    val active: Long? = null,
    val desktop: Boolean = false,
    val settings: JSONObject? = null,
    val locked: Boolean = false,
    val offConsole: Boolean = false,
    val offConsoleView: Boolean = false,
    val desktopDisplays: Int = 1,
    val desktopDisplay: Int = 1,
    val desktopNames: List<String> = emptyList(),
    val clipboardGeneration: Int = 0,
    val gestures: Int = 0,
    val keyRepeat: Int = 0,
    val audioNote: String? = null,
    val clipboardNote: String? = null,
    val settingsNote: String? = null,
    val fullScreen: Boolean = false,
    val inPip: Boolean = false,
    val pairingLink: PairLink? = null,
    val cursorImage: Bitmap? = null,
    val cursorHotX: Float = 0f,
    val cursorHotY: Float = 0f,
    val cursorWidth: Float = 0f,
    val cursorHeight: Float = 0f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val decoder: String = "",
    val error: String? = null,
    val resolutionNotice: String? = null,
    val menus: JSONObject? = null,
)

private val wireToken = java.util.concurrent.atomic.AtomicInteger(0)

class SillClient(app: Application) : AndroidViewModel(app) {
    @Volatile private var inForeground = false
    private val wifiLock =
        app.getSystemService(android.net.wifi.WifiManager::class.java)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "sill:stream")
            .apply { setReferenceCounted(false) }

    @Synchronized
    private fun lowLatencyWifi(enable: Boolean) {
        runCatching {
                if (enable && inForeground) {
                    if (!wifiLock.isHeld) wifiLock.acquire()
                } else if (wifiLock.isHeld) wifiLock.release()
            }
            .onFailure {
                android.util.Log.w("SillVideo", "Could not change Wi-Fi latency mode", it)
            }
    }

    private val prefs = app.getSharedPreferences("macs", 0)
    private val _state = MutableStateFlow(ClientState(saved = loadSaved()))
    val state = _state.asStateFlow()
    val audio =
        AudioOutput(app) { note ->
            _state.update { it.copy(audioNote = note.takeIf { it.isNotEmpty() }) }
        }
    var lastUse = android.os.SystemClock.elapsedRealtime()
        private set

    var modifierKeys = 0L
    private var subscribedClipboard = false
    private var pasteReceipt: ByteArray? = null
    private var pendingSettings = 0
    private var latestMenuToken = 0
    var shareClipboard = prefs.getBoolean("shareClipboard", true)
        private set

    var mute = prefs.getBoolean("mute", false)
        private set

    var pipEnabled = prefs.getBoolean("pip", false)
        private set

    var largerPicture = prefs.getBoolean("largerPicture", false)
        private set

    var holdToDrag = prefs.getBoolean("holdToDrag", true)
        private set

    var showTour = !prefs.getBoolean("tour062", false)
        private set

    fun tourSeen() {
        showTour = false
        prefs.edit().putBoolean("tour062", true).apply()
    }

    fun preference(name: String, value: Boolean) {
        prefs.edit().putBoolean(name, value).apply()
        when (name) {
            "shareClipboard" -> {
                shareClipboard = value
                syncClipboard()
            }
            "mute" -> {
                mute = value
                updateAudio()
            }
            "pip" -> pipEnabled = value
            "largerPicture" -> largerPicture = value
            "holdToDrag" -> holdToDrag = value
        }
    }

    fun used() {
        lastUse = android.os.SystemClock.elapsedRealtime()
    }

    fun fullScreen(value: Boolean) {
        used()
        _state.update { it.copy(fullScreen = value) }
    }

    fun pip(value: Boolean) {
        _state.update { it.copy(inPip = value) }
        syncClipboard()
        updateAudio()
    }

    private fun updateAudio() {
        val s = _state.value
        audio.enabled =
            !mute && (inForeground || s.inPip) && s.connected && !s.locked && !s.offConsole
    }

    private val identity by lazy { Identity(app) }
    private val nsd = app.getSystemService(NsdManager::class.java)
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolving = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolvingNow = false
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var generation = 0
    private var job: Job? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val resolutionFallback = ResolutionFallback()
    private var fallbackTimeout: Runnable? = null
    val video =
        VideoDecoder(
            onNeedsKeyframe = { requestKeyframe() },
            onFormat = { w, h, name ->
                _state.update { it.copy(videoWidth = w, videoHeight = h, decoder = name) }
            },
            onError = { message -> _state.update { it.copy(error = message) } },
            onUnsupportedSize = { w, h ->
                val g = generation
                main.post {
                    if (g == generation)
                        handleResolution(
                            resolutionFallback.unsupported(
                                ResolutionFallback.Size(w, h),
                                captureScale(),
                            )
                        )
                }
            },
            onRecovered = {
                val g = generation
                main.post {
                    if (g == generation) {
                        keyframeRecovery.pictureBack(
                            android.os.SystemClock.elapsedRealtime(),
                            inForeground,
                        )
                        considerKeyframe()
                    }
                }
            },
            onPicture = {
                val g = generation
                main.post {
                    if (g == generation) {
                        cancelFallbackTimeout()
                        resolutionFallback.reset()
                        val note =
                            if (_state.value.resolutionNotice != null)
                                "Using Standard resolution to fit this device. This also changes the Mac's stream for other connected devices."
                            else null
                        _state.update { it.copy(error = null, resolutionNotice = note) }
                        if (note != null)
                            main.postDelayed(
                                {
                                    if (g == generation && _state.value.resolutionNotice == note)
                                        _state.update { it.copy(resolutionNotice = null) }
                                },
                                8000,
                            )
                    }
                }
            },
        )
    private val inputCount = java.util.concurrent.atomic.AtomicLong(0)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    var onPointer: ((Pair<Double, Double>?) -> Unit)? = null

    fun showPointer(position: Pair<Double, Double>?) {
        main.post { onPointer?.invoke(position) }
    }

    var reverseScroll = prefs.getBoolean("reverseScroll", false)
        set(value) {
            field = value
            prefs.edit().putBoolean("reverseScroll", value).apply()
        }

    var textScale: Double? = null
    var panelFPS = 60
        private set

    fun displayRate(rate: Float) {
        val fps = rate.toInt().coerceIn(30, 120)
        if (fps != panelFPS) {
            panelFPS = fps
            sendViewport()
        }
    }

    private var panelWidth = 1000.0
    private var panelHeight = 650.0
    private var keyframeRecovery = KeyframeRecovery()
    private var keyframeTask: Runnable? = null
    private var worstRttMs: Long? = null
    private var rttSecond = 0L
    private var rttBucketMax = 0L
    private val moves = MoveCoalescer<JSONObject>()
    private var moveTask: Runnable? = null

    private fun clearRealtime() {
        keyframeTask?.let(main::removeCallbacks)
        keyframeTask = null
        moveTask?.let(main::removeCallbacks)
        moveTask = null
        moves.reset()
        keyframeRecovery = KeyframeRecovery()
        worstRttMs = null
        rttSecond = 0
        rttBucketMax = 0
    }

    private fun considerKeyframe() {
        keyframeTask?.let(main::removeCallbacks)
        keyframeTask = null
        val s = _state.value
        val now = android.os.SystemClock.elapsedRealtime()
        when (
            val decision =
                keyframeRecovery.consider(
                    now,
                    inForeground,
                    s.connected &&
                        source(s) != null &&
                        (!s.locked && !s.offConsole || s.offConsoleView),
                    KeyframeRecovery.answerWait(worstRttMs),
                )
        ) {
            KeyframeRecovery.Decision.Nothing -> Unit
            KeyframeRecovery.Decision.Ask -> {
                android.util.Log.i(
                    "SillRecovery",
                    "Request keyframe; answer wait=${KeyframeRecovery.answerWait(worstRttMs)} ms",
                )
                send(44)
                considerKeyframe()
            }
            is KeyframeRecovery.Decision.At -> {
                val owner = generation
                keyframeTask =
                    Runnable {
                            keyframeTask = null
                            if (owner == generation) considerKeyframe()
                        }
                        .also { main.postDelayed(it, (decision.time - now).coerceAtLeast(1)) }
            }
        }
    }

    private var currentHost: MacHost? = null
    private var sourceRecovery = SourceRecovery()
    private var sourceTick: Runnable? = null
    private var reconnectTask: Runnable? = null
    private var reconnectWanted = false
    private var reconnectAttempt = 0
    private var resumeSource: SourceRecovery.Source? = null

    private class HostGoodbye(val reason: String, val retry: Boolean, message: String) :
        java.io.IOException(message)

    private fun source(s: ClientState) =
        if (s.desktop) SourceRecovery.Source() else s.active?.let { SourceRecovery.Source(it) }

    private fun recoverSource() {
        val s = _state.value
        if (!s.connected || !inForeground || ((s.locked || s.offConsole) && !s.offConsoleView))
            return
        sourceRecovery
            .observe(
                source(s),
                s.windows.map { it.id }.toSet(),
                android.os.SystemClock.elapsedRealtime(),
                worstRttMs,
            )
            ?.let {
                android.util.Log.i("SillSession", "Auto source: ${it.window ?: "Desktop"}")
                send(6, Wire.source(it.window))
                video.awaitKeyframe()
            }
    }

    private fun scheduleReconnect() {
        if (!reconnectWanted || !inForeground || reconnectTask != null) return
        val host = currentHost ?: return
        val owner = generation
        reconnectTask =
            Runnable {
                    reconnectTask = null
                    if (owner == generation && reconnectWanted && inForeground)
                        connect(host, automatic = true)
                }
                .also { main.postDelayed(it, ReconnectPolicy.delayMs(reconnectAttempt++)) }
    }

    private fun sessionEnded(e: Exception) {
        resumeSource = source(_state.value) ?: resumeSource
        lowLatencyWifi(false)
        clearRealtime()
        video.reset()
        audio.newSession()
        val goodbye = e as? HostGoodbye
        reconnectWanted =
            if (goodbye != null) ReconnectPolicy.retry(goodbye.reason, goodbye.retry)
            else
                e is java.io.IOException &&
                    e !is javax.net.ssl.SSLHandshakeException &&
                    e !is javax.net.ssl.SSLPeerUnverifiedException
        sourceTick?.let(main::removeCallbacks)
        sourceTick = null
        _state.update {
            it.copy(
                connected = false,
                busy = false,
                reconnecting = reconnectWanted,
                videoWidth = 0,
                videoHeight = 0,
                error = if (reconnectWanted) null else friendly(e),
                status =
                    if (reconnectWanted)
                        "Connection interrupted. Reconnecting to ${currentHost?.name ?: "Mac"}…"
                    else "Connection ended. Tap your Mac to reconnect.",
            )
        }
        scheduleReconnect()
    }

    init {
        browse()
    }

    private fun loadSaved(): List<MacHost> =
        runCatching {
                val a = JSONArray(prefs.getString("saved", "[]"))
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    MacHost(
                        o.getString("name"),
                        o.getString("host"),
                        o.getInt("port"),
                        o.getString("pin"),
                        o.optString("recognition").takeIf { key -> key.isNotEmpty() },
                        o.optJSONArray("routes")?.let { a ->
                            (0 until a.length()).map { a.getString(it) }
                        } ?: emptyList(),
                        o.optDouble("issuedAt", 0.0),
                    )
                }
            }
            .getOrDefault(emptyList())

    private fun save(host: MacHost) {
        val list = (_state.value.saved.filter { it.pin != host.pin } + host)
        prefs
            .edit()
            .putString(
                "saved",
                JSONArray(
                        list.map {
                            Wire.json(
                                "name" to it.name,
                                "host" to it.host,
                                "port" to it.port,
                                "pin" to it.pin,
                                "recognition" to it.recognition,
                                "routes" to JSONArray(it.routes),
                                "issuedAt" to it.issuedAt,
                            )
                        }
                    )
                    .toString(),
            )
            .apply()
        _state.update { it.copy(saved = list) }
    }

    fun forget(host: MacHost) {
        val list = _state.value.saved.filter { it.pin != host.pin }
        prefs
            .edit()
            .putString(
                "saved",
                JSONArray(
                        list.map {
                            Wire.json(
                                "name" to it.name,
                                "host" to it.host,
                                "port" to it.port,
                                "pin" to it.pin,
                                "recognition" to it.recognition,
                                "routes" to JSONArray(it.routes),
                                "issuedAt" to it.issuedAt,
                            )
                        }
                    )
                    .toString(),
            )
            .apply()
        _state.update { it.copy(saved = list) }
    }

    @Suppress("DEPRECATION")
    fun browse() {
        if (discovery != null) return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun resolveNext() {
            if (resolvingNow || resolving.isEmpty() || discovery == null) return
            resolvingNow = true
            val item = resolving.removeFirst()
            nsd.resolveService(
                item,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                        main.post {
                            resolvingNow = false
                            resolveNext()
                        }
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        main.post {
                            val address = info.host?.hostAddress
                            if (address != null && discovery != null) {
                                val tag =
                                    info.attributes.entries
                                        .firstOrNull { it.key.equals("r", true) }
                                        ?.value
                                        ?.toString(Charsets.UTF_8)
                                val saved =
                                    _state.value.saved.firstOrNull { h ->
                                        tag != null &&
                                            h.recognition?.let { RemoteInfo.recognizes(tag, it) } ==
                                                true
                                    }
                                val host =
                                    saved?.copy(host = address, port = info.port)
                                        ?: MacHost(info.serviceName, address, info.port)
                                if (saved != null) {
                                    save(host)
                                    if (currentHost?.pin == host.pin) currentHost = host
                                }
                                _state.update {
                                    it.copy(
                                        hosts = it.hosts.filter { h -> h.name != host.name } + host
                                    )
                                }
                            }
                            resolvingNow = false
                            resolveNext()
                        }
                    }
                },
            )
        }
        discovery =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}

                override fun onDiscoveryStopped(type: String) {}

                override fun onStartDiscoveryFailed(type: String, error: Int) {
                    _state.update {
                        it.copy(
                            status = "Couldn't search this network. You can add a Mac by address."
                        )
                    }
                    discovery = null
                }

                override fun onStopDiscoveryFailed(type: String, error: Int) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post {
                        resolving.add(info)
                        resolveNext()
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    _state.update {
                        it.copy(hosts = it.hosts.filter { h -> h.name != info.serviceName })
                    }
                }
            }
        nsd.discoverServices("_sill._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun choose(host: MacHost) {
        reconnectWanted = false
        resumeSource = null
        if (host.pin != null) connect(host)
        else {
            _state.update {
                it.copy(pairing = host, error = null, status = "Pair with ${host.name}")
            }
            ask(host)
        }
    }

    fun add(host: String, port: Int) {
        require(host.isNotBlank() && port in 1..65535)
        choose(MacHost("Mac", host.trim().removeSurrounding("[", "]"), port))
    }

    @Synchronized
    private fun adopt(s: SSLSocket, g: Int) {
        if (g != generation) {
            s.close()
            throw CancellationException("Dial replaced")
        }
        socket = s
    }

    @Synchronized
    private fun resetConnection(): Int {
        sourceTick?.let(main::removeCallbacks)
        sourceTick = null
        reconnectTask?.let(main::removeCallbacks)
        reconnectTask = null
        generation++
        clearRealtime()
        lowLatencyWifi(false)
        inputCount.set(0)
        showPointer(null)
        job?.cancel()
        job = null
        cancelFallbackTimeout()
        resolutionFallback.reset()
        runCatching { socket?.close() }
        socket = null
        video.reset()
        audio.newSession()
        subscribedClipboard = false
        pasteReceipt = null
        modifierKeys = 0
        pendingSettings = 0
        latestMenuToken = 0
        return generation
    }

    private fun ask(host: MacHost) {
        val g = resetConnection()
        _state.update { it.copy(busy = true) }
        job =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    identity
                        .socket(host.host, host.port, true, null) { adopt(it, g) }
                        .use { s ->
                            val answer = exchangePair(s, "ask", "")
                            if (g == generation)
                                _state.update {
                                    it.copy(
                                        status =
                                            if (answer.optString("reason") == "shown")
                                                "Enter the code shown on your Mac."
                                            else "On your Mac, open Sill → Pair iPhone or iPad…",
                                        busy = false,
                                    )
                                }
                        }
                } catch (e: Exception) {
                    if (g == generation)
                        _state.update {
                            it.copy(
                                busy = false,
                                status = "On your Mac, open Sill → Pair iPhone or iPad…",
                                error = friendly(e),
                            )
                        }
                }
            }
    }

    private fun exchangePair(s: SSLSocket, method: String, proof: String): JSONObject {
        val req =
            Wire.json(
                "v" to 1,
                "method" to method,
                "proof" to proof,
                "name" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "model" to Build.MODEL,
            )
        s.outputStream.write(Wire.bytes(19, req.toString().toByteArray()))
        val response = Wire.read(DataInputStream(s.inputStream))
        require(response.kind == 20) { "Unexpected pairing response" }
        return JSONObject(response.payload.toString(Charsets.UTF_8))
    }

    fun pair(code: String) {
        val host = _state.value.pairing ?: return
        val normalized =
            try {
                PairProof.normalize(code)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
                return
            }
        val g = resetConnection()
        _state.update { it.copy(busy = true, error = null, status = "Pairing with ${host.name}…") }
        job =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val paired =
                        identity
                            .socket(host.host, host.port, true, null) { adopt(it, g) }
                            .use { s ->
                                val pin = Identity.pin(s)
                                val key = PairProof.key(normalized, pin)
                                val answer =
                                    exchangePair(
                                        s,
                                        "code",
                                        Identity.b64(
                                            PairProof.proof(key, identity.fingerprint, pin)
                                        ),
                                    )
                                require(answer.optBoolean("ok")) {
                                    when (answer.optString("reason")) {
                                        "code" ->
                                            "That code didn't match. Check the code on your Mac."
                                        "expired",
                                        "closed" -> "Open a fresh pairing code in Sill on your Mac."
                                        "busy" -> "Too many attempts. Wait before trying again."
                                        "locked" -> "Unlock your Mac to pair."
                                        else ->
                                            answer.optString("message", "Pairing was not accepted.")
                                    }
                                }
                                require(
                                    MessageDigest.isEqual(
                                        PairProof.proof(key, identity.fingerprint, pin, true),
                                        Identity.unb64(answer.getString("proof")),
                                    )
                                ) {
                                    "The Mac's pairing proof did not match."
                                }
                                key.fill(0)
                                host.copy(
                                    name = answer.optString("name", host.name),
                                    pin = Identity.b64(pin),
                                    recognition =
                                        answer.optString("recognitionKey").takeIf {
                                            it.isNotEmpty()
                                        },
                                )
                            }
                    withContext(Dispatchers.Main) {
                        if (g == generation) {
                            save(paired)
                            connect(paired)
                        }
                    }
                } catch (e: Exception) {
                    if (g == generation)
                        _state.update {
                            it.copy(
                                busy = false,
                                error = friendly(e),
                                status = "Pair with ${host.name}",
                            )
                        }
                }
            }
    }

    fun offerPairLink(text: String) {
        try {
            val link = PairLink.parse(text)
            _state.update { it.copy(pairingLink = link, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Invalid pairing link") }
        }
    }

    fun dismissPairLink() {
        _state.value.pairingLink?.secret?.fill(0)
        _state.update { it.copy(pairingLink = null) }
    }

    fun pairLink() {
        val link = _state.value.pairingLink ?: return
        _state.update {
            it.copy(
                pairingLink = null,
                busy = true,
                error = null,
                status = "Pairing with ${link.name}…",
            )
        }
        val owner = resetConnection()
        job =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    var paired: MacHost? = null
                    var failure: Exception = java.io.IOException("Mac is unreachable")
                    for ((host, port) in link.routes) {
                        if (owner != generation) throw CancellationException()
                        val connection =
                            try {
                                identity.socket(host, port, true, link.pin) { adopt(it, owner) }
                            } catch (e: Exception) {
                                if (
                                    e is CancellationException ||
                                        e is javax.net.ssl.SSLHandshakeException
                                )
                                    throw e
                                failure = e
                                continue
                            }
                        connection.use { socket ->
                            val reply =
                                exchangePair(
                                    socket,
                                    "qr",
                                    Identity.b64(
                                        PairProof.proof(link.secret, identity.fingerprint, link.pin)
                                    ),
                                )
                            require(reply.optBoolean("ok")) {
                                "Pairing link expired or was refused. Open a fresh pairing code on the Mac."
                            }
                            require(
                                MessageDigest.isEqual(
                                    PairProof.proof(
                                        link.secret,
                                        identity.fingerprint,
                                        link.pin,
                                        true,
                                    ),
                                    Identity.unb64(reply.getString("proof")),
                                )
                            ) {
                                "Mac pairing proof did not match."
                            }
                            paired =
                                MacHost(
                                    reply.optString("name", link.name),
                                    host,
                                    port,
                                    Identity.b64(link.pin),
                                    reply.optString("recognitionKey").takeIf { it.isNotEmpty() },
                                    link.routes.map { "${it.first}|${it.second}" },
                                )
                        }
                        break
                    }
                    val result = paired ?: throw failure
                    withContext(Dispatchers.Main) {
                        if (owner == generation) {
                            save(result)
                            connect(result)
                        }
                    }
                } catch (e: Exception) {
                    if (owner == generation && e !is CancellationException)
                        _state.update {
                            it.copy(
                                busy = false,
                                error = friendly(e),
                                status = "Pairing did not finish.",
                            )
                        }
                } finally {
                    link.secret.fill(0)
                }
            }
    }

    fun cancelPair() {
        reconnectWanted = false
        resetConnection()
        _state.update {
            it.copy(pairing = null, busy = false, error = null, status = "Looking for Macs…")
        }
    }

    fun connect(host: MacHost, automatic: Boolean = false) {
        if (!automatic) {
            used()
            reconnectAttempt = 0
            resumeSource = null
        }
        reconnectWanted = false
        sourceRecovery = SourceRecovery(if (automatic) resumeSource else null)
        val g = resetConnection()
        currentHost = host
        lowLatencyWifi(true)
        _state.update {
            it.copy(
                connected = false,
                busy = true,
                reconnecting = automatic,
                pairing = null,
                error = null,
                resolutionNotice = null,
                settings = null,
                locked = false,
                offConsole = false,
                offConsoleView = false,
                clipboardGeneration = 0,
                gestures = 0,
                keyRepeat = 0,
                desktopDisplays = 1,
                desktopDisplay = 1,
                desktopNames = emptyList(),
                audioNote = null,
                clipboardNote = null,
                settingsNote = null,
                cursorImage = null,
                videoWidth = 0,
                videoHeight = 0,
                active = null,
                desktop = false,
                windows = emptyList(),
                thumbnails = emptyMap(),
                icons = emptyMap(),
                menus = null,
                status = "Connecting to ${host.name}…",
            )
        }
        job =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val s = dial(host, g)
                    if (g != generation) {
                        s.close()
                        return@launch
                    }
                    s.outputStream.write(
                        Wire.bytes(
                            23,
                            Wire.json(
                                    "appVersion" to Wire.COMPATIBILITY_VERSION,
                                    "build" to BuildConfig.VERSION_NAME,
                                    "protocol" to Wire.PROTOCOL_VERSION,
                                    "device" to Build.MODEL,
                                    "offConsoleView" to 1,
                                    "audio" to
                                        if (audio.supported) JSONArray(listOf("aac-eld")) else null,
                                )
                                .toString()
                                .toByteArray(),
                        )
                    )
                    sendViewport()
                    send(27, JSONObject())
                    val reader =
                        DataInputStream(java.io.BufferedInputStream(s.inputStream, 64 * 1024))
                    coroutineScope {
                        val ping = launch {
                            while (isActive) {
                                delay(250)
                                send(
                                    10,
                                    ByteBuffer.allocate(8)
                                        .putDouble(System.nanoTime() / 1e9)
                                        .array(),
                                )
                            }
                        }
                        try {
                            while (isActive && g == generation) receive(Wire.read(reader), g)
                        } finally {
                            ping.cancel()
                        }
                    }
                } catch (e: Exception) {
                    main.post {
                        if (g == generation && e !is CancellationException) sessionEnded(e)
                    }
                } finally {
                    if (g == generation) {
                        runCatching { socket?.close() }
                        socket = null
                    }
                }
            }
    }

    @Synchronized
    private fun receive(m: Wire.Message, sessionGeneration: Int) {
        if (sessionGeneration != generation) return
        fun json() = JSONObject(m.payload.toString(Charsets.UTF_8))
        when (m.kind) {
            0 -> video.configure(m.payload)
            1 -> video.frame(m.payload, m.key)
            2 -> {
                val o = json()
                val ws = o.getJSONArray("windows")
                val a = o.getJSONObject("active")
                val windows =
                    (0 until ws.length()).map {
                        val w = ws.getJSONObject(it)
                        MacWindow(
                            w.getLong("id"),
                            w.getString("title"),
                            w.getString("appName"),
                            w.getString("bundleID"),
                        )
                    }
                val owner = generation
                main.post {
                    if (owner != generation) return@post
                    _state.update { old ->
                        old.copy(
                            connected = true,
                            busy = false,
                            reconnecting = false,
                            macName = o.getString("macName"),
                            windows = windows,
                            active = a.optJSONObject("window")?.optLong("_0"),
                            desktop = a.has("desktop"),
                            locked = o.optBoolean("locked"),
                            offConsole = o.optBoolean("offConsole"),
                            offConsoleView =
                                ParityPolicy.viewingOffConsole(
                                    o.optBoolean("locked"),
                                    o.optBoolean("offConsole"),
                                    o.optBoolean("offConsoleView"),
                                ),
                            clipboardGeneration = o.optInt("clipboard"),
                            gestures = o.optInt("gestures"),
                            keyRepeat = o.optInt("keyRepeat"),
                            desktopDisplays = o.optInt("desktopDisplays", 1).coerceIn(1, 64),
                            desktopDisplay =
                                o.optInt("desktopDisplay", 1)
                                    .coerceIn(1, o.optInt("desktopDisplays", 1).coerceIn(1, 64)),
                            desktopNames =
                                o.optJSONArray("desktopDisplayNames")?.let { a ->
                                    (0 until minOf(a.length(), 64)).map { a.optString(it) }
                                } ?: emptyList(),
                            apps = if (o.optBoolean("locked")) emptyList() else old.apps,
                            menus = if (o.optBoolean("locked")) null else old.menus,
                            cursorImage = if (o.optBoolean("locked")) null else old.cursorImage,
                            thumbnails =
                                old.thumbnails.filterKeys { id -> windows.any { it.id == id } },
                        )
                    }
                    reconnectAttempt = 0
                    if (_state.value.offConsole) {
                        moves.reset()
                        moveTask?.let(main::removeCallbacks)
                        moveTask = null
                    }
                    syncClipboard()
                    updateAudio()
                    recoverSource()
                    considerKeyframe()
                    if (sourceTick == null) {
                        sourceTick =
                            object : Runnable {
                                    override fun run() {
                                        if (owner != generation) return
                                        recoverSource()
                                        main.postDelayed(this, 500)
                                    }
                                }
                                .also { main.postDelayed(it, 500) }
                    }
                }
            }
            3 ->
                if (m.payload.size > 4) {
                    val id = ByteBuffer.wrap(m.payload).int.toLong() and 0xffffffffL
                    decodeBitmap(m.payload, 4)?.let { b ->
                        _state.update { it.copy(thumbnails = it.thumbnails + (id to b)) }
                    }
                }
            4 ->
                if (m.payload.size > 2) {
                    val n = ByteBuffer.wrap(m.payload).short.toInt() and 65535
                    if (n + 2 < m.payload.size) {
                        val id = m.payload.copyOfRange(2, n + 2).toString(Charsets.UTF_8)
                        decodeBitmap(m.payload, n + 2)?.let { b ->
                            _state.update { it.copy(icons = it.icons + (id to b)) }
                        }
                    }
                }
            5 -> {
                val a = JSONArray(m.payload.toString(Charsets.UTF_8))
                _state.update {
                    it.copy(
                        apps =
                            (0 until a.length()).map { i ->
                                val o = a.getJSONObject(i)
                                MacApp(o.getString("name"), o.getString("bundleID"))
                            }
                    )
                }
            }
            11 ->
                if (m.payload.size == 16) {
                    val owner = generation
                    main.post {
                        if (owner == generation && pasteReceipt?.contentEquals(m.payload) == true) {
                            pasteReceipt = null
                            main.postDelayed(
                                {
                                    val st = _state.value
                                    if (
                                        owner == generation &&
                                            inForeground &&
                                            shareClipboard &&
                                            st.clipboardGeneration >= 1 &&
                                            !st.locked &&
                                            !st.offConsole
                                    ) {
                                        key(25, 1L shl 20)
                                        _state.update {
                                            it.copy(clipboardNote = "Pasted on ${it.macName}.")
                                        }
                                    }
                                },
                                50,
                            )
                        }
                    }
                } else if (m.payload.size == 8) {
                    val elapsed =
                        (System.nanoTime() / 1e9 - ByteBuffer.wrap(m.payload).double) * 1000
                    if (elapsed.isFinite() && elapsed >= 0) {
                        val owner = generation
                        main.post {
                            if (owner == generation) {
                                val second = android.os.SystemClock.elapsedRealtime() / 1000
                                if (second != rttSecond) {
                                    rttSecond = second
                                    rttBucketMax = 0
                                }
                                rttBucketMax =
                                    maxOf(rttBucketMax, elapsed.coerceAtMost(86400000.0).toLong())
                                worstRttMs = rttBucketMax
                            }
                        }
                    }
                }
            14 ->
                if (m.payload.size > 16 && !_state.value.locked) {
                    val b = ByteBuffer.wrap(m.payload)
                    val x = b.float
                    val y = b.float
                    val w = b.float
                    val h = b.float
                    if (
                        listOf(x, y, w, h).all { it.isFinite() } &&
                            w > 0 &&
                            h > 0 &&
                            w <= 512 &&
                            h <= 512
                    )
                        decodeBitmap(m.payload, 16)?.let { bitmap ->
                            _state.update {
                                it.copy(
                                    cursorImage = bitmap,
                                    cursorHotX = x,
                                    cursorHotY = y,
                                    cursorWidth = w,
                                    cursorHeight = h,
                                )
                            }
                        }
                }
            18 -> {
                val envelope = json()
                val host = currentHost
                val verified = host?.pin?.let { RemoteInfo.verified(envelope, Identity.unb64(it)) }
                val owner = generation
                if (host != null && verified != null)
                    main.post { if (owner == generation) rememberRemote(host, verified) }
            }
            32 -> {
                val text = json().optString("text")
                val owner = generation
                main.post {
                    val st = _state.value
                    if (
                        owner == generation &&
                            inForeground &&
                            !_state.value.inPip &&
                            subscribedClipboard &&
                            shareClipboard &&
                            !st.locked &&
                            !st.offConsole &&
                            ParityPolicy.clipboard(text) != null
                    ) {
                        getApplication<Application>()
                            .getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(
                                android.content.ClipData.newPlainText("From ${st.macName}", text)
                            )
                        _state.update { it.copy(clipboardNote = "Copied from ${it.macName}.") }
                    }
                }
            }
            43 -> audio.accept(m.payload)
            16 -> {
                val settings = json()
                _state.update {
                    it.copy(
                        settings = settings,
                        settingsNote =
                            if (settings.optInt("answering", -1) == pendingSettings) null
                            else it.settingsNote,
                    )
                }
                val g = generation
                main.post {
                    if (g == generation)
                        handleResolution(resolutionFallback.settings(captureScale()))
                }
            }
            22 -> {
                val o = json()
                throw HostGoodbye(
                    o.optString("reason"),
                    o.optBoolean("reconnect"),
                    o.optString("message", "The Mac ended this session (${o.optString("reason")})."),
                )
            }
            24 -> {
                val incoming = json()
                if (!_state.value.locked)
                    _state.update { old ->
                        val previous = old.menus
                        val same = previous?.optInt("version", -1) == incoming.optInt("version", -2)
                        if (
                            incoming.has("answering") &&
                                incoming.optInt("answering") < latestMenuToken
                        )
                            old
                        else {
                            val merged =
                                if (same && !incoming.has("menus")) JSONObject(previous.toString())
                                else JSONObject()
                            if (incoming.has("menus")) {
                                merged.remove("items")
                                merged.remove("menu")
                            }
                            incoming.keys().forEach { key -> merged.put(key, incoming.get(key)) }
                            old.copy(menus = merged)
                        }
                    }
            }
            26 -> {
                val o = json()
                val owner = generation
                main.post {
                    if (
                        owner == generation &&
                            moves.pending == null &&
                            o.optLong("seen", 0) >= inputCount.get()
                    ) {
                        val x = o.optDouble("x")
                        val y = o.optDouble("y")
                        onPointer?.invoke(
                            if (o.optBoolean("inside") && x.isFinite() && y.isFinite())
                                x.coerceIn(0.0, 1.0) to y.coerceIn(0.0, 1.0)
                            else null
                        )
                    }
                }
            }
        // Unknown messages are consumed, never interpreted as another kind.
        }
    }

    private fun captureScale(): Double? =
        _state.value.settings?.optJSONObject("settings")?.optDouble("captureScale")?.takeIf {
            it.isFinite()
        }

    private fun cancelFallbackTimeout() {
        fallbackTimeout?.let(main::removeCallbacks)
        fallbackTimeout = null
    }

    private fun armFallbackTimeout() {
        if (fallbackTimeout != null) return
        val g = generation
        fallbackTimeout =
            Runnable {
                    fallbackTimeout = null
                    if (g == generation) handleResolution(resolutionFallback.timeout())
                }
                .also { main.postDelayed(it, 8000) }
    }

    /** Runs on main. The kind-17 setting is shared by the host's clients, so say so in the UI. */
    private fun handleResolution(action: ResolutionFallback.Action) {
        val size = resolutionFallback.rejected ?: return
        when (action) {
            ResolutionFallback.Action.NONE -> Unit
            ResolutionFallback.Action.WAIT_FOR_SETTINGS -> {
                _state.update {
                    it.copy(
                        error =
                            "The ${size.width} × ${size.height} picture is too large for this device. Checking the Mac's resolution…"
                    )
                }
                armFallbackTimeout()
            }
            ResolutionFallback.Action.REQUEST_STANDARD -> {
                cancelFallbackTimeout()
                _state.update {
                    it.copy(
                        error = null,
                        resolutionNotice =
                            "Switching the Mac's stream to Standard resolution so this device can display it…",
                    )
                }
                android.util.Log.i(
                    "SillVideo",
                    "Requesting Standard resolution after rejecting ${size.width}x${size.height}",
                )
                send(17, Wire.json("token" to nextToken(), "captureScale" to 1.0))
                armFallbackTimeout()
            }
            ResolutionFallback.Action.CANNOT_LOWER -> {
                cancelFallbackTimeout()
                val message =
                    if (captureScale()?.let { it <= 1.0 } == true)
                        "The Mac's Standard picture (${size.width} × ${size.height}) is still too large for this device. Pick a smaller window or reduce the Mac's display resolution."
                    else
                        "The Mac hasn't supplied a smaller picture. Choose Standard under Settings → Resolution, or pick a smaller window."
                _state.update { it.copy(error = message, resolutionNotice = null) }
            }
        }
    }

    private fun decodeBitmap(bytes: ByteArray, offset: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, offset, bytes.size - offset, options)
        if (options.outWidth !in 1..4096 || options.outHeight !in 1..4096) return null
        return BitmapFactory.decodeByteArray(bytes, offset, bytes.size - offset)
    }

    fun send(kind: Int, obj: JSONObject) {
        if (kind in listOf(17, 25, 27)) {
            if (_state.value.locked || _state.value.offConsole) return
            if (!obj.has("token")) obj.put("token", nextToken())
            if (kind == 17) {
                pendingSettings = obj.getInt("token")
                _state.update { it.copy(settingsNote = "Applying Mac settings…") }
                val token = pendingSettings
                val owner = generation
                main.postDelayed(
                    {
                        if (
                            owner == generation &&
                                pendingSettings == token &&
                                _state.value.settingsNote != null
                        )
                            _state.update {
                                it.copy(
                                    settingsNote =
                                        "No confirmation from the Mac. Check the settings and try again."
                                )
                            }
                    },
                    8000,
                )
            } else latestMenuToken = obj.getInt("token")
        }
        send(kind, obj.toString().toByteArray())
    }

    fun send(kind: Int, payload: ByteArray = byteArrayOf()) {
        val s = socket ?: return
        val g = generation
        writer.execute {
            if (g == generation && s === socket)
                runCatching { s.outputStream.write(Wire.bytes(kind, payload)) }
                    .onFailure { runCatching { s.close() } }
        }
    }

    private fun emitInputs(events: List<JSONObject>) {
        events.forEach {
            inputCount.incrementAndGet()
            send(8, it)
        }
    }

    fun input(obj: JSONObject) {
        if (android.os.Looper.myLooper() != main.looper) {
            val owner = generation
            main.post { if (owner == generation) input(obj) }
            return
        }
        if (!_state.value.connected || _state.value.offConsole || _state.value.inPip) return
        used()
        val now = android.os.SystemClock.elapsedRealtime()
        emitInputs(moves.offer(obj, obj.optJSONObject("pointer")?.optString("_0") == "move", now))
        moveTask?.let(main::removeCallbacks)
        moveTask = null
        moves.due()?.let { due ->
            val owner = generation
            moveTask =
                Runnable {
                        moveTask = null
                        if (owner == generation)
                            emitInputs(moves.flush(android.os.SystemClock.elapsedRealtime()))
                    }
                    .also { main.postDelayed(it, (due - now).coerceAtLeast(1)) }
        }
    }

    fun select(id: Long?) {
        if (_state.value.locked || _state.value.offConsole) return
        used()
        sourceRecovery.userChoice(SourceRecovery.Source(id))
        resumeSource = SourceRecovery.Source(id)
        send(6, Wire.source(id))
        video.awaitKeyframe()
        sendViewport()
    }

    fun launch(bundle: String) {
        if (_state.value.locked || _state.value.offConsole) return
        used()
        sourceRecovery.userChoice()
        resumeSource = null
        send(7, Wire.json("bundleID" to bundle))
    }

    fun windowCommand(id: Long, action: String) {
        if (!_state.value.locked && !_state.value.offConsole)
            send(15, Wire.json("id" to id, "action" to action))
    }

    fun desktop() {
        used()
        val s = _state.value
        if (ParityPolicy.nextDisplay(s.desktop, s.desktopDisplays, s.locked, s.offConsole)) {
            send(47)
            video.awaitKeyframe()
        } else select(null)
    }

    fun gesture(name: String, fingers: Int) {
        val s = _state.value
        if (s.gestures >= 1 && !s.locked && !s.offConsole) {
            used()
            send(28, Wire.json("gesture" to name, "fingers" to fingers))
        }
    }

    private fun syncClipboard() {
        val s = _state.value
        val wanted =
            shareClipboard &&
                inForeground &&
                !s.inPip &&
                s.connected &&
                s.clipboardGeneration >= 1 &&
                !s.locked &&
                !s.offConsole
        if (wanted != subscribedClipboard) {
            if (s.connected && s.clipboardGeneration >= 1)
                send(32, if (wanted) JSONObject() else Wire.json("stop" to true))
            subscribedClipboard = wanted
        }
        if (!wanted) pasteReceipt = null
    }

    fun paste() {
        used()
        val s = _state.value
        if (
            !inForeground ||
                s.inPip ||
                !shareClipboard ||
                s.clipboardGeneration < 1 ||
                s.locked ||
                s.offConsole ||
                pasteReceipt != null
        )
            return
        val clip =
            getApplication<Application>()
                .getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip
        val text =
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty()
            else ""
        val bytes = ParityPolicy.clipboard(text)
        if (bytes == null) {
            _state.update {
                it.copy(
                    clipboardNote = "Clipboard is empty or the text is too long (256 KB maximum)."
                )
            }
            return
        }
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        pasteReceipt = nonce
        send(32, bytes)
        send(10, nonce)
        _state.update { it.copy(clipboardNote = "Sending to ${it.macName}…") }
        val owner = generation
        main.postDelayed(
            {
                if (owner == generation && pasteReceipt === nonce) {
                    pasteReceipt = null
                    _state.update {
                        it.copy(clipboardNote = "The Mac did not confirm the copy. Try again.")
                    }
                }
            },
            8000,
        )
    }

    private fun rememberRemote(host: MacHost, info: JSONObject) {
        val current = _state.value.saved.firstOrNull { it.pin == host.pin } ?: return
        val issued = info.optDouble("issuedAt", 0.0)
        if (!issued.isFinite() || issued <= current.issuedAt) return
        val port = info.optInt("remotePort", 7455)
        val list = info.optJSONArray("addresses") ?: JSONArray()
        val fresh =
            (0 until minOf(list.length(), 32)).mapNotNull { i ->
                val a = list.optJSONObject(i) ?: return@mapNotNull null
                val h = a.optString("host")
                val p = a.optInt("port", port)
                if (h.isBlank() || h.length > 253 || '|' in h || p !in 1..65535) null else "$h|$p"
            }
        val updated =
            current.copy(routes = ParityPolicy.addresses(current.routes, fresh), issuedAt = issued)
        save(updated)
        currentHost = updated
    }

    private fun dial(host: MacHost, owner: Int): SSLSocket {
        val latest = _state.value.saved.firstOrNull { it.pin == host.pin } ?: host
        val routes = (listOf("${latest.host}|${latest.port}") + latest.routes).distinct()
        var failure: Exception = java.io.IOException("No address for this Mac")
        for (route in routes) {
            if (owner != generation) throw CancellationException("Dial replaced")
            val parts = route.split('|')
            if (parts.size != 2) continue
            try {
                return identity.socket(
                    parts[0],
                    parts[1].toInt(),
                    false,
                    Identity.unb64(requireNotNull(host.pin)),
                ) {
                    adopt(it, owner)
                }
            } catch (e: Exception) {
                if (
                    e is CancellationException ||
                        e is javax.net.ssl.SSLHandshakeException ||
                        e is javax.net.ssl.SSLPeerUnverifiedException
                )
                    throw e
                failure = e
            }
        }
        throw failure
    }

    fun viewport(w: Double, h: Double) {
        panelWidth = w
        panelHeight = h
        sendViewport()
    }

    fun sendViewport() =
        send(
            9,
            Wire.json(
                "width" to panelWidth.coerceIn(1.0, 16384.0),
                "height" to panelHeight.coerceIn(1.0, 16384.0),
                "scale" to textScale,
                "localCursor" to true,
                "fps" to panelFPS,
            ),
        )

    fun requestKeyframe() {
        val owner = generation
        main.post {
            if (owner == generation) {
                keyframeRecovery.interrupted(android.os.SystemClock.elapsedRealtime())
                considerKeyframe()
            }
        }
    }

    fun releaseModifiers() {
        val held = modifierKeys
        modifierKeys = 0
        for ((bit, usage) in listOf(17 to 225, 18 to 224, 19 to 226, 20 to 227)) if (
            held and (1L shl bit) != 0L
        )
            input(Wire.key(usage, false, 0))
    }

    fun key(hid: Int, modifiers: Long = 0) {
        input(Wire.key(hid, true, modifiers))
        input(Wire.key(hid, false, modifiers))
    }

    fun disconnect() {
        reconnectWanted = false
        resumeSource = null
        resetConnection()
        _state.update { ClientState(hosts = it.hosts, saved = it.saved, status = "Disconnected.") }
    }

    fun nextToken() = wireToken.incrementAndGet()

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun background() {
        if (_state.value.inPip) return
        releaseModifiers()
        inForeground = false
        syncClipboard()
        updateAudio()
        keyframeRecovery.leftForeground()
        considerKeyframe()
        lowLatencyWifi(false)
        video.suspendVideo()
    }

    fun foreground() {
        if (!inForeground) used()
        inForeground = true
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        resolving.clear()
        resolvingNow = false
        browse()
        syncClipboard()
        updateAudio()
        scheduleReconnect()
        if (_state.value.connected || _state.value.busy) lowLatencyWifi(true)
        video.resumeVideo()
        if (_state.value.connected) requestKeyframe()
    }

    private fun friendly(e: Exception) =
        when (e) {
            is java.net.SocketTimeoutException ->
                "The Mac did not respond. Check Sill on the Mac. For remote access, turn on your VPN on both this device and the Mac."
            else -> e.cause?.message ?: e.message ?: "Couldn't connect to the Mac."
        }

    override fun onCleared() {
        reconnectWanted = false
        resetConnection()
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        writer.shutdownNow()
        video.close()
        audio.close()
    }

    fun usbDiagnostic(port: Int) {
        if (!BuildConfig.DEBUG || port !in 1..65535) return
        _state.value.saved.firstOrNull()?.let { connect(it.copy(host = "127.0.0.1", port = port)) }
    }

    fun preview() {
        if (BuildConfig.DEBUG)
            _state.update {
                it.copy(
                    connected = true,
                    macName = "MacBook Pro",
                    windows =
                        listOf(
                            MacWindow(101, "Getting started", "Safari", "com.apple.Safari"),
                            MacWindow(102, "Welcome to Sill", "Notes", "com.apple.Notes"),
                            MacWindow(103, "Project", "Code", "com.microsoft.VSCode"),
                        ),
                    active = 102,
                    status = "Preview",
                )
            }
    }
}
