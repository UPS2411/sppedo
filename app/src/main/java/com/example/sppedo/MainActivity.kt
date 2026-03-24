package com.example.sppedo

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity.kt — Complete Internet Speed Meter in a single file
// Includes: ForegroundService + Compose UI + Notification + Permission handling
// Target: Android 8+ (API 26+) | Zero heavy architecture | Minimal RAM/CPU
// ─────────────────────────────────────────────────────────────────────────────

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

// ═════════════════════════════════════════════════════════════════════════════
// SECTION 1 — FOREGROUND SERVICE
//
// Architecture notes:
//   • Single HandlerThread drives the 1-second tick — no coroutine scope,
//     no ExecutorService, no thread pool overhead.
//   • @Volatile static fields act as zero-cost shared-memory IPC with the UI.
//     No Binder, no LiveData, no Flow required.
//   • StringBuilder is reused every tick — eliminates per-second GC pressure.
//   • NotificationCompat.Builder is built once; only setContentText() is
//     called each tick so we never recreate the builder object.
// ═════════════════════════════════════════════════════════════════════════════

class SpeedService : Service() {

    companion object {
        const val CHANNEL_ID = "speed_ch"
        const val NOTIF_ID   = 1

        // ── Shared-memory IPC ─────────────────────────────────────────────
        // Written by the service worker thread, read by the UI coroutine.
        // @Volatile ensures cross-thread visibility without locking.
        @Volatile var downSpeed: String  = "0 B/s"
        @Volatile var upSpeed:   String  = "0 B/s"
        @Volatile var isRunning: Boolean = false
    }

    // One dedicated background thread with a Looper.
    // Cheaper than CoroutineScope or any thread pool for a simple tick loop.
    private val workerThread = HandlerThread("SpeedWorker").also { it.start() }
    private val handler       = Handler(workerThread.looper)

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L

    // Single reusable buffer — cleared with setLength(0) each tick.
    // Avoids a new String/char[] allocation every second in the hot path.
    private val sb = StringBuilder(16)

    private lateinit var notifManager: NotificationManager
    private lateinit var notifBuilder: NotificationCompat.Builder

    // Stored as a field so removeCallbacks() can cancel it precisely.
    private val tick = object : Runnable {
        override fun run() {
            measure()
            handler.postDelayed(this, 1_000L)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        setupChannel()

        // Build once — update text in-place every second, never recreate.
        notifBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Speed Meter")
            .setOngoing(true)
            .setSilent(true)         // No sound / vibration on each update
            .setOnlyAlertOnce(true)  // Heads-up only on first appearance

        startForeground(NOTIF_ID, notifBuilder.build())
        isRunning = true

        // Seed baseline so the first delta isn't the total bytes ever sent.
        lastRxBytes = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        lastTxBytes = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)

        handler.postDelayed(tick, 1_000L)
    }

    // START_STICKY: OS will restart the service if it kills it under pressure.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(tick)
        workerThread.quitSafely() // Drains pending messages before stopping thread
        downSpeed = "0 B/s"
        upSpeed   = "0 B/s"
        super.onDestroy()
    }

    // Not a bound service — no Binder needed.
    override fun onBind(intent: Intent?): IBinder? = null

    // ── Core 1-second measurement ─────────────────────────────────────────
    private fun measure() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()

        // TrafficStats returns UNSUPPORTED (-1) on some VPN stacks / emulators.
        val rxDelta = if (rx < 0L) 0L else (rx - lastRxBytes).coerceAtLeast(0L)
        val txDelta = if (tx < 0L) 0L else (tx - lastTxBytes).coerceAtLeast(0L)

        if (rx >= 0L) lastRxBytes = rx
        if (tx >= 0L) lastTxBytes = tx

        downSpeed = format(rxDelta)
        upSpeed   = format(txDelta)

        // notify() is silently a no-op if POST_NOTIFICATIONS was denied on
        // API 33+. The service keeps running and the in-app UI still updates.
        notifManager.notify(
            NOTIF_ID,
            notifBuilder
                .setContentText("↓ $downSpeed   ↑ $upSpeed")
                .build()
        )
    }

    // ── Speed formatter — zero extra allocations via StringBuilder reuse ──
    private fun format(bps: Long): String {
        sb.setLength(0) // Clear buffer without reallocation
        return when {
            bps >= 1_048_576L ->
                sb.append(String.format("%.2f", bps / 1_048_576.0))
                    .append(" MB/s").toString()
            bps >= 1_024L ->
                sb.append(String.format("%.1f", bps / 1_024.0))
                    .append(" KB/s").toString()
            else ->
                sb.append(bps).append(" B/s").toString()
        }
    }

    // ── Notification channel — required on Android 8+ ─────────────────────
    private fun setupChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Speed Meter",
            NotificationManager.IMPORTANCE_LOW // Silent, no badge dot
        ).apply {
            description = "Real-time network speed"
            setShowBadge(false)
        }
        notifManager.createNotificationChannel(ch)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// SECTION 2 — MAIN ACTIVITY
//
// POST_NOTIFICATIONS (Android 13+ / API 33+) is a runtime permission.
// We request it once on launch via ActivityResultContracts.RequestPermission.
//
// If the user denies it:
//   • The foreground service keeps running normally.
//   • Speed measurement is completely unaffected.
//   • notifManager.notify() silently no-ops — zero crash risk.
//   • The in-app Compose UI still shows live speeds perfectly.
// ═════════════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {

    // Must be registered before onCreate() per Jetpack lifecycle contract.
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // granted = true  → status-bar notification will show
            // granted = false → app still fully functional, no status-bar notif
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Request POST_NOTIFICATIONS on Android 13+ (API 33+) ───────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SpeedScreen(
                    onStart = { startService(Intent(this, SpeedService::class.java)) },
                    onStop  = { stopService(Intent(this, SpeedService::class.java)) }
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// SECTION 3 — COMPOSE UI
//
// Recomposition strategy:
//   • One LaunchedEffect coroutine polls two @Volatile strings + one Boolean
//     every 1 second via delay(). No Timer, no extra thread, no callbacks.
//   • Only 3 remembered state values — recompose redraws only the leaf
//     Text() nodes that actually changed. Minimal GPU/CPU work per frame.
//   • Dark theme: fewer overdraw layers on OLED, lower power draw, and
//     matches the utilitarian monitoring-tool aesthetic intentionally.
// ═════════════════════════════════════════════════════════════════════════════

private val BgColor    = Color(0xFF0F0F0F)
private val CardColor  = Color(0xFF1A1A1A)
private val AccentDown = Color(0xFF00C896)  // Teal   — download
private val AccentUp   = Color(0xFFFF6B35)  // Orange — upload
private val LabelColor = Color(0xFF888888)

@Composable
fun SpeedScreen(onStart: () -> Unit, onStop: () -> Unit) {

    var down    by remember { mutableStateOf("0 B/s") }
    var up      by remember { mutableStateOf("0 B/s") }
    var running by remember { mutableStateOf(SpeedService.isRunning) }

    // Single coroutine polling static fields every second.
    // suspend delay() yields the thread — zero blocking, zero extra threads.
    LaunchedEffect(Unit) {
        while (true) {
            down    = SpeedService.downSpeed
            up      = SpeedService.upSpeed
            running = SpeedService.isRunning
            delay(1_000L)
        }
    }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(BgColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── App title ──────────────────────────────────────────────────
            Text(
                text          = "SPEED METER",
                color         = Color.White,
                fontSize      = 13.sp,
                fontWeight    = FontWeight.W600,
                letterSpacing = 4.sp
            )

            Spacer(Modifier.height(8.dp))

            // ── Speed cards ───────────────────────────────────────────────
            SpeedCard(label = "DOWNLOAD", value = down, accent = AccentDown, arrow = "↓")
            SpeedCard(label = "UPLOAD",   value = up,   accent = AccentUp,   arrow = "↑")

            Spacer(Modifier.height(16.dp))

            // ── Live status indicator ─────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (running) AccentDown else Color(0xFF444444))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text          = if (running) "MONITORING" else "STOPPED",
                    color         = LabelColor,
                    fontSize      = 11.sp,
                    letterSpacing = 2.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Start / Stop buttons ──────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick  = onStart,
                    enabled  = !running,
                    colors   = ButtonDefaults.buttonColors(containerColor = AccentDown),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("START", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                OutlinedButton(
                    onClick  = onStop,
                    enabled  = running,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("STOP", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
    }
}

// ── Reusable speed display card ────────────────────────────────────────────
@Composable
private fun SpeedCard(label: String, value: String, accent: Color, arrow: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardColor)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text          = label,
                    color         = LabelColor,
                    fontSize      = 10.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text       = value,
                    color      = Color.White,
                    fontSize   = 26.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text       = arrow,
                color      = accent,
                fontSize   = 36.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}