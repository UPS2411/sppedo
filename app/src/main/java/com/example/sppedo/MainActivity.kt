package com.example.sppedo

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity.kt — Internet Speed Meter
//
// Notification fixes in this version:
//   • Icon bitmap enlarged to 128×128 with bigger text sizes (was 96×96, too small)
//   • Number line: 52px bold monospace  (was 42px — unreadable in status bar)
//   • Unit line:   32px normal monospace (was 28px)
//   • Vertical centering recalculated with correct Paint.FontMetrics baseline math
//     (the old topOffset + height formula drifts on some fonts — fixed)
//   • Content text format changed to "1.2 MB/s ↓   32 KB/s ↑"
//
// ⚠️  AndroidManifest.xml — required for Android 14+ (API 34+):
//      <service
//          android:name=".SpeedService"
//          android:foregroundServiceType="dataSync"
//          android:exported="false" />
// ─────────────────────────────────────────────────────────────────────────────

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

// ═════════════════════════════════════════════════════════════════════════════
// SECTION 1 — FOREGROUND SERVICE
// ═════════════════════════════════════════════════════════════════════════════

class SpeedService : Service() {

    companion object {
        const val CHANNEL_ID = "speed_ch"
        const val NOTIF_ID   = 1

        @Volatile var downSpeed: String  = "0 B/s"
        @Volatile var upSpeed:   String  = "0 B/s"
        @Volatile var isRunning: Boolean = false
    }

    private lateinit var workerThread: HandlerThread
    private lateinit var handler: Handler

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L

    private lateinit var notifManager: NotificationManager
    private lateinit var notifBuilder: NotificationCompat.Builder

    // ── Icon bitmap ───────────────────────────────────────────────────────────
    //
    // 128×128 px — larger canvas means more pixels per glyph before Android
    // scales the icon down to ~24dp in the status bar. On xxhdpi (3×) that
    // 24dp slot is 72px, so 128px source gives us ~1.78× supersampling which
    // makes strokes noticeably crisper than the old 96px source.
    //
    // One Bitmap allocated for the app's lifetime; erased + redrawn each tick.
    private val BMP_SIZE = 128
    private val iconBitmap: Bitmap by lazy {
        Bitmap.createBitmap(BMP_SIZE, BMP_SIZE, Bitmap.Config.ARGB_8888)
    }
    private val iconCanvas: Canvas by lazy { Canvas(iconBitmap) }

    // ── Paint: speed number (e.g. "1.24") ────────────────────────────────────
    // Larger textSize (52px on 128px canvas ≈ 40% of height) so the number
    // fills the icon slot and is readable at 24dp in the status bar.
    // ISML proportions: number glyph cell ≈ 60% of 128px canvas → textSize 72f
    // unit label ≈ 35% of canvas → textSize 44f
    private val paintNum: Paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color     = AColor.WHITE
            textAlign = Paint.Align.CENTER
            typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize  = 90f   // was 52f
        }
    }

    // ── Paint: unit label (e.g. "MB/s") ──────────────────────────────────────
    private val paintUnit: Paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color     = AColor.WHITE
            textAlign = Paint.Align.CENTER
            typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textSize  = 40f   // was 32f
        }
    }

    // Reusable bounds — written by getTextBounds(), never heap-allocated again.
    private val numBounds  = Rect()
    private val unitBounds = Rect()

    private val tick = object : Runnable {
        override fun run() {
            measure()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()

        notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        setupChannel()

        workerThread = HandlerThread("SpeedWorker").also { it.start() }
        handler      = Handler(workerThread.looper)

        notifBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Meter")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(
                        NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                    )
                }
            }

        val initialNotif = buildNotification("0", "B/s", "0 B/s ↓   0 B/s ↑")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, initialNotif)
        }

        isRunning = true
        seedBaseline()
        handler.postDelayed(tick, 1_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) seedBaseline()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(tick)
        workerThread.quitSafely()
        downSpeed = "0 B/s"
        upSpeed   = "0 B/s"
        if (!iconBitmap.isRecycled) iconBitmap.recycle()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun seedBaseline() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        lastRxBytes = if (rx >= 0L) rx else 0L
        lastTxBytes = if (tx >= 0L) tx else 0L
    }

    // ── Core 1-second measurement ─────────────────────────────────────────
    private fun measure() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()

        val rxDelta = if (rx >= 0L) abs(rx - lastRxBytes) else 0L
        val txDelta = if (tx >= 0L) abs(tx - lastTxBytes) else 0L

        if (rx >= 0L) lastRxBytes = rx
        if (tx >= 0L) lastTxBytes = tx

        val down = format(rxDelta)
        val up   = format(txDelta)

        downSpeed = down
        upSpeed   = up

        // "1.24 MB/s" → number = "1.24", unit = "MB/s"
        val parts  = down.split(" ")
        val number = parts.getOrElse(0) { "0" }
        val unit   = parts.getOrElse(1) { "B/s" }

        // ── Content text: "1.2 MB/s ↓   32 KB/s ↑" ──────────────────────
        val contentText = "$down ↓   $up ↑"

        notifManager.notify(NOTIF_ID, buildNotification(number, unit, contentText))
    }

    // ── Build notification with fresh bitmap icon each tick ───────────────
    private fun buildNotification(number: String, unit: String, contentText: String) =
        notifBuilder
            .setSmallIcon(IconCompat.createWithBitmap(drawSpeedBitmap(number, unit)))
            .setContentText(contentText)
            .build()

    // ── Status-bar icon renderer ──────────────────────────────────────────
    //
    // Layout on the 128×128 canvas:
    //
    //   leftPad = 6px
    //
    //   ┌─────────────────────────┐
    //   │                         │
    //   │  1.24     ← 52px bold   │
    //   │  MB/s     ← 32px normal │
    //   │                         │
    //   └─────────────────────────┘
    //
    // Vertical centering uses FontMetrics (ascent/descent) rather than
    // getTextBounds() height, which is more accurate across all fonts and
    // avoids the upward drift seen with the old formula on some devices.
    //
    private fun drawSpeedBitmap(number: String, unit: String): Bitmap {
        iconBitmap.eraseColor(AColor.TRANSPARENT)

        val leftPad = 6f

        // ── Measure real rendered heights via FontMetrics ─────────────────
        // FontMetrics.ascent is negative (above baseline), descent is positive.
        // lineHeight = ascent.abs + descent gives the full glyph cell height.
        val fmNum  = paintNum.fontMetrics
        val fmUnit = paintUnit.fontMetrics

        val numH  = (-fmNum.ascent  + fmNum.descent)   // height of number line
        val unitH = (-fmUnit.ascent + fmUnit.descent)  // height of unit line

        val gap    = 0f                                 // px between the two lines
        val blockH = numH + gap + unitH

        // Top of the two-line block, vertically centred in BMP_SIZE.
        val blockTop = (BMP_SIZE - blockH) / 2f

        // drawText() positions text at the BASELINE.
        // Baseline = blockTop + ascent-height (distance from top to baseline).
        val numBaseline  = blockTop + (-fmNum.ascent)
        val unitBaseline = blockTop + numH + gap + (-fmUnit.ascent)

        val cx = BMP_SIZE / 2f   // horizontal centre of canvas

        iconCanvas.drawText(number, cx, numBaseline,  paintNum)
        iconCanvas.drawText(unit,   cx, unitBaseline, paintUnit)

        return iconBitmap
    }

    // ── Speed formatter ───────────────────────────────────────────────────
    private fun format(bps: Long): String {
        return when {
            bps >= 1_048_576L -> {
                val mb = bps / 1_048_576.0
                String.format("%.1f MB/s", mb)   // keep 1 decimal for MB
            }
            bps >= 1_024L -> {
                val kb = bps / 1_024.0

                if (kb < 100) {
                    String.format("%.0f KB/s", kb)  // round, no decimal (34.7 → 35)
                } else {
                    String.format("%.0f KB/s", kb)  // already clean (234 → 234)
                }
            }
            else -> {
                "$bps B/s"
            }
        }
    }

    // ── Notification channel ──────────────────────────────────────────────
    private fun setupChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Speed Meter",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Real-time network speed"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notifManager.createNotificationChannel(ch)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// SECTION 2 — MAIN ACTIVITY
// ═════════════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
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
// ═════════════════════════════════════════════════════════════════════════════

private val BgColor    = Color(0xFF0F0F0F)
private val CardColor  = Color(0xFF1A1A1A)
private val AccentDown = Color(0xFF00C896)
private val AccentUp   = Color(0xFFFF6B35)
private val LabelColor = Color(0xFF888888)

@Composable
fun SpeedScreen(onStart: () -> Unit, onStop: () -> Unit) {

    var down    by remember { mutableStateOf("0 B/s") }
    var up      by remember { mutableStateOf("0 B/s") }
    var running by remember { mutableStateOf(SpeedService.isRunning) }

    LaunchedEffect(Unit) {
        while (isActive) {
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
            Text(
                text          = "SPEED METER",
                color         = Color.White,
                fontSize      = 13.sp,
                fontWeight    = FontWeight.W600,
                letterSpacing = 4.sp
            )

            Spacer(Modifier.height(8.dp))

            SpeedCard(label = "DOWNLOAD", value = down, accent = AccentDown, arrow = "↓")
            SpeedCard(label = "UPLOAD",   value = up,   accent = AccentUp,   arrow = "↑")

            Spacer(Modifier.height(16.dp))

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