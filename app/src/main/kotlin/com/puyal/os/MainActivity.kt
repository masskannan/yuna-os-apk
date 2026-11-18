package com.puyal.os

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RuntimeShader
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.puyal.os.service.PuyalCoreService
import java.io.File
import java.util.concurrent.TimeUnit

private const val AURORA_AGSL = """
uniform float2 uRes;
uniform float uTime;
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(uRes, float2(1.0, 1.0));
    float w1 = sin(uv.x * 3.1 + uTime * 0.4) * cos(uv.y * 2.4 - uTime * 0.3);
    float w2 = cos(uv.x * 2.2 - uv.y * 2.8 + uTime * 0.25);
    float tealMask = smoothstep(0.95, 0.08, distance(uv, float2(0.84, 0.16 + 0.03 * w1)));
    float indigoMask = smoothstep(1.05, 0.12, distance(uv, float2(0.16, 0.46 + 0.04 * w2)));
    float3 base = float3(0.020, 0.027, 0.047);
    float3 teal = float3(0.055, 0.647, 0.914) * tealMask * 0.42;
    float3 indigo = float3(0.388, 0.400, 0.945) * indigoMask * 0.38;
    return half4(base + teal + indigo, 1.0);
}
"""

private const val PLASMA_ORB_AGSL = """
uniform float2 uRes;
uniform float uTime;
uniform float3 uAccent;
half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * uRes) / max(min(uRes.x, uRes.y), 1.0);
    float d = length(uv);
    float angle = atan(uv.y, uv.x);
    float swirl = sin(angle * 3.0 + d * 10.0 - uTime * 2.2) * 0.5 + 0.5;
    float plasma = sin(uv.x * 7.0 + uTime * 1.4) * cos(uv.y * 7.0 - uTime * 1.1) * 0.5 + 0.5;
    float core = smoothstep(0.50, 0.05, d);
    float rim = smoothstep(0.50, 0.44, d) * smoothstep(0.35, 0.47, d);
    float3 violet = float3(0.388, 0.400, 0.945);
    float3 col = mix(violet, uAccent, swirl * 0.65 + plasma * 0.35) * core;
    col += float3(0.72, 0.90, 0.99) * rim * 0.75;
    float alpha = smoothstep(0.50, 0.46, d);
    return half4(col, alpha);
}
"""

data class ChatEntry(val isUser: Boolean, val title: String, val body: String)

class MainActivity : ComponentActivity() {

    private val hasRootState = mutableStateOf(false)
    private val oomScoreState = mutableStateOf("-1000")
    private val overlayGranted = mutableStateOf(false)
    private val micGranted = mutableStateOf(false)
    private val notifGranted = mutableStateOf(false)
    private val batteryExempt = mutableStateOf(false)
    private val serviceRunning = mutableStateOf(false)
    private val bubbleMode = mutableStateOf("IDLE")
    private val torchOn = mutableStateOf(false)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshSystemState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        refreshSystemState()
        probeRootAndOomAsync()

        setContent {
            YunaSovereignScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
        probeRootAndOomAsync()
    }

    @Composable
    private fun YunaSovereignScreen() {
        var inputText by remember { mutableStateOf("") }
        var navMode by remember { mutableStateOf("3BTN") }
        var showDrawer by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showPlusTools by remember { mutableStateOf(false) }
        val messages = remember { mutableStateListOf<ChatEntry>() }

        val activeMode = bubbleMode.value
        val isAnimating = activeMode != "IDLE"
        var animTime by remember { mutableFloatStateOf(0f) }

        // 0-FPS Idle Battery Lock: frame loop runs ONLY when activeMode != IDLE
        LaunchedEffect(isAnimating) {
            if (isAnimating) {
                var last = 0L
                while (true) {
                    withFrameNanos { now ->
                        if (last != 0L) animTime += (now - last) / 1_000_000_000f
                        last = now
                    }
                }
            }
        }

        val extraNavBottom = when (navMode) {
            "3BTN" -> 8.dp
            "2BTN" -> 6.dp
            else -> 4.dp
        }

        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF05070C))) {
            // 1. AGSL Multi-Layer Aurora Background
            AgslAuroraLayer(timeSec = animTime)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = extraNavBottom)
            ) {
                // Top Bar: Menu | YUNA [20MB • LOCAL] | Settings Gear
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GlassIconBtn("☰") { showDrawer = true }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "YUNA",
                            color = Color(0xFFF8FAFC),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(Color(0xB30D1829))
                                .border(1.dp, Color(0x4D38BDF8), RoundedCornerShape(99.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "20MB • LOCAL",
                                color = Color(0xFFBAE6FD),
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    GlassIconBtn("⚙️") {
                        refreshSystemState()
                        probeRootAndOomAsync()
                        showSettings = true
                    }
                }

                // Center Scrollable Stage
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (messages.isEmpty()) {
                        // Cybernetic HUD Rings + AGSL Breathing Plasma Orb
                        Box(
                            modifier = Modifier
                                .size(190.dp)
                                .clickable {
                                    val next = if (bubbleMode.value == "LISTENING") "IDLE" else "LISTENING"
                                    triggerServiceState(next)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            HudOrbitalRings(activeMode = activeMode)
                            AgslPlasmaOrb(
                                modifier = Modifier.size(116.dp),
                                timeSec = animTime,
                                activeMode = activeMode
                            )
                        }

                        Text(
                            text = "Vanakkam Boss",
                            color = Color(0xFFF8FAFC),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "OOM ${oomScoreState.value} • ${if (activeMode == "IDLE") "0-FPS IDLE (TAP ORB)" else activeMode}",
                            color = Color(0xFF38BDF8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )

                        Spacer(Modifier.height(16.dp))

                        // Frosted Glass "Screen Vision & Scan" Hero Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xCC112238), Color(0xCC161A33))
                                    )
                                )
                                .border(1.dp, Color(0x557DD3FC), RoundedCornerShape(20.dp))
                                .clickable {
                                    submitUserQuery("Screen Vision & Scan", messages)
                                }
                                .padding(horizontal = 20.dp, vertical = 18.dp)
                        ) {
                            Column {
                                Text(
                                    text = "⛶  Screen Vision & Scan",
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Read active app DOM, OCR & local triggers",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Quick Action Pills (WA Intel, Root Shield, Torch)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuickActionPill("WA Intel", Color(0xFF38BDF8), Modifier.weight(1f)) {
                                submitUserQuery("WhatsApp VIP Intel check", messages)
                            }
                            QuickActionPill("Root Shield", Color(0xFF00FF88), Modifier.weight(1f)) {
                                refreshSystemState()
                                probeRootAndOomAsync()
                                showSettings = true
                            }
                            QuickActionPill(
                                if (torchOn.value) "Torch ON" else "Torch",
                                Color(0xFF818CF8),
                                Modifier.weight(1f)
                            ) { toggleTorch() }
                        }
                    } else {
                        messages.forEach { entry ->
                            ChatBubbleItem(entry)
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }

                // Plus Attachment Sheet
                AnimatedVisibility(visible = showPlusTools, enter = fadeIn(), exit = fadeOut()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniChip("📷 Camera") { toast("Camera Vision ready in Level 5"); showPlusTools = false }
                        MiniChip("🖼️ Photo") { toast("Photo picker ready in Level 5"); showPlusTools = false }
                        MiniChip("📁 File") { toast("File attach ready in Level 2"); showPlusTools = false }
                        MiniChip("👁️ Scan") {
                            showPlusTools = false
                            submitUserQuery("Screen Vision & Scan", messages)
                        }
                    }
                }

                // Bottom Floating Input Pill ("+", "Yuna kitta sollunga...", Voice Wave, AI Orb Button)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(29.dp))
                        .background(Color(0xD90E1525))
                        .border(1.dp, Color(0x4D38BDF8), RoundedCornerShape(29.dp))
                        .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x18FFFFFF))
                            .clickable { showPlusTools = !showPlusTools },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("＋", color = Color(0xFFCBD5E1), fontSize = 19.sp)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (inputText.isEmpty()) {
                            Text("Yuna kitta sollunga...", color = Color(0xFF64748B), fontSize = 14.5.sp)
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color(0xFFF8FAFC), fontSize = 14.5.sp),
                            cursorBrush = SolidColor(Color(0xFF38BDF8)),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 5-Bar Voice Wave Trigger
                    VoiceWaveButton(active = activeMode == "LISTENING") {
                        val next = if (bubbleMode.value == "LISTENING") "IDLE" else "LISTENING"
                        triggerServiceState(next)
                        if (next == "LISTENING") {
                            messages.add(ChatEntry(false, "YUNA Mic ON", "Level 3 Tamil STT hook active (0-FPS resumes on stop)."))
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // AI Orb Send Button
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF6366F1))))
                            .border(1.5.dp, Color(0xFF7DD3FC), CircleShape)
                            .clickable {
                                val q = inputText.trim()
                                if (q.isNotEmpty()) {
                                    inputText = ""
                                    submitUserQuery(q, messages)
                                } else {
                                    triggerServiceState("IDLE")
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🌪️", fontSize = 18.sp)
                    }
                }
            }

            // Left Slide-Out Drawer (New Chat, Files, Skills, Schedules, ALL CHATS)
            AnimatedVisibility(visible = showDrawer, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xB8000000))
                        .clickable { showDrawer = false }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(275.dp)
                            .background(Color(0xFF080C16))
                            .statusBarsPadding()
                            .padding(horizontal = 18.dp, vertical = 20.dp)
                            .clickable(enabled = false) {}
                    ) {
                        Text(
                            text = "YUNA • LOCAL OS",
                            color = Color(0xFF38BDF8),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(18.dp))
                        DrawerRow("＋  New Chat") { messages.clear(); showDrawer = false }
                        DrawerRow("📁  Files & App Store") { openUrl("http://127.0.0.1:7778"); showDrawer = false }
                        DrawerRow("⚡  Wasm Skills") { toast("Unlocks in Level 2 & 4"); showDrawer = false }
                        DrawerRow("🕒  Schedules") { toast("Unlocks in Level 6"); showDrawer = false }
                        DrawerRow("⚙️  Settings") { showDrawer = false; showSettings = true }

                        Spacer(Modifier.height(18.dp))
                        Text("ALL CHATS", color = Color(0xFF64748B), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(8.dp))
                        DrawerStatusRow("● READY", Color(0xFF00FF88), "OOM -1000 Immortal Core")
                        DrawerStatusRow("▲ ACTION", Color(0xFFF59E0B), "Level 1 Phone Verify")
                        DrawerStatusRow("◌ PROC", Color(0xFF38BDF8), "0-FPS Idle Guard")
                    }
                }
            }

            // Settings Modal (3-Button / 2-Button / Gesture + Immortal Bubble + Perms + Local Ports)
            AnimatedVisibility(visible = showSettings, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                        .clickable { showSettings = false },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF0B101D))
                            .border(1.dp, Color(0xFF2E4360), RoundedCornerShape(20.dp))
                            .clickable(enabled = false) {}
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚙️ YUNA SETTINGS", color = Color(0xFF38BDF8), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("✕", color = Color(0xFF94A3B8), fontSize = 18.sp, modifier = Modifier.clickable { showSettings = false })
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("Navigation Bar Clearance", color = Color(0xFF64748B), fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            NavChip("3-Button", navMode == "3BTN", Modifier.weight(1f)) { navMode = "3BTN" }
                            NavChip("2-Button", navMode == "2BTN", Modifier.weight(1f)) { navMode = "2BTN" }
                            NavChip("Gesture", navMode == "GESTURE", Modifier.weight(1f)) { navMode = "GESTURE" }
                        }

                        Spacer(Modifier.height(12.dp))
                        val svcOk = serviceRunning.value
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (svcOk) Color(0xFF071E14) else Color(0xFF00FF88))
                                .border(1.dp, Color(0xFF00FF88), RoundedCornerShape(12.dp))
                                .clickable {
                                    if (svcOk) {
                                        startService(Intent(this@MainActivity, PuyalCoreService::class.java).apply {
                                            action = PuyalCoreService.ACTION_STOP_SERVICE
                                        })
                                    } else {
                                        ContextCompat.startForegroundService(
                                            this@MainActivity,
                                            Intent(this@MainActivity, PuyalCoreService::class.java)
                                        )
                                    }
                                    window.decorView.postDelayed({ refreshSystemState(); probeRootAndOomAsync() }, 350)
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (svcOk) "🟢 YUNA FLOATING 🌪️ ACTIVE (STOP)" else "🚀 START YUNA FLOATING 🌪️ BUBBLE",
                                color = if (svcOk) Color(0xFF00FF88) else Color(0xFF04110A),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                        Text(
                            text = "PID ${Process.myPid()} ($abi) • SU: ${if (hasRootState.value) "OK" else "NO"} • OOM ${oomScoreState.value}",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(Modifier.height(12.dp))
                        PermRow("Overlay Bubble", overlayGranted.value) {
                            if (!Settings.canDrawOverlays(this@MainActivity)) {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            }
                        }
                        PermRow("Mic & Camera", micGranted.value) {
                            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                        }
                        PermRow("Notifications", notifGranted.value) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            }
                        }
                        PermRow("Battery Unrestricted", batteryExempt.value) {
                            val pm = getSystemService(POWER_SERVICE) as PowerManager
                            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                })
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            NavChip(":7777 Web UI", false, Modifier.weight(1f)) { openUrl("http://127.0.0.1:7777") }
                            NavChip(":7778 Store", false, Modifier.weight(1f)) { openUrl("http://127.0.0.1:7778") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AgslAuroraLayer(timeSec: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shader = remember { RuntimeShader(AURORA_AGSL) }
            Canvas(modifier = Modifier.fillMaxSize()) {
                shader.setFloatUniform("uRes", size.width, size.height)
                shader.setFloatUniform("uTime", timeSec)
                drawRect(brush = ShaderBrush(shader))
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x400EA5E9), Color(0x336366F1), Color(0xFF05070C)),
                            center = Offset(720f, 260f),
                            radius = 1100f
                        )
                    )
            )
        }
    }

    @Composable
    private fun AgslPlasmaOrb(modifier: Modifier, timeSec: Float, activeMode: String) {
        val accentRgb = when (activeMode) {
            "LISTENING" -> floatArrayOf(0.0f, 0.90f, 1.0f)
            "ACTION_BURST" -> floatArrayOf(0.0f, 1.0f, 0.53f)
            "PROCESSING" -> floatArrayOf(0.96f, 0.62f, 0.04f)
            else -> floatArrayOf(0.22f, 0.74f, 0.97f)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shader = remember { RuntimeShader(PLASMA_ORB_AGSL) }
            Canvas(modifier = modifier) {
                shader.setFloatUniform("uRes", size.width, size.height)
                shader.setFloatUniform("uTime", timeSec)
                shader.setFloatUniform("uAccent", accentRgb[0], accentRgb[1], accentRgb[2])
                drawCircle(brush = ShaderBrush(shader))
            }
        } else {
            val c = Color(accentRgb[0], accentRgb[1], accentRgb[2])
            Canvas(modifier = modifier) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFFBAE6FD), c, Color(0xFF6366F1), Color(0xFF080D1C))
                    )
                )
            }
        }
    }

    @Composable
    private fun HudOrbitalRings(activeMode: String) {
        val ringColor = when (activeMode) {
            "LISTENING" -> Color(0xFF00E5FF)
            "ACTION_BURST" -> Color(0xFF00FF88)
            "PROCESSING" -> Color(0xFFF59E0B)
            else -> Color(0x6638BDF8)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r1 = size.minDimension * 0.36f
            val r2 = size.minDimension * 0.45f
            drawCircle(color = ringColor, radius = r1, style = Stroke(width = 1.2.dp.toPx()))
            drawCircle(color = ringColor.copy(alpha = 0.45f), radius = r2, style = Stroke(width = 1.dp.toPx()))
        }
    }

    @Composable
    private fun VoiceWaveButton(active: Boolean, onClick: () -> Unit) {
        val barColor = if (active) Color(0xFF00FF88) else Color(0xFF38BDF8)
        Canvas(
            modifier = Modifier
                .size(34.dp)
                .clickable { onClick() }
        ) {
            val heights = listOf(0.26f, 0.50f, 0.76f, 0.46f, 0.28f)
            val startX = size.width * 0.18f
            val step = (size.width * 0.64f) / 4f
            heights.forEachIndexed { idx, frac ->
                val x = startX + idx * step
                val halfH = size.height * frac / 2f
                drawLine(
                    color = barColor,
                    start = Offset(x, center.y - halfH),
                    end = Offset(x, center.y + halfH),
                    strokeWidth = 2.3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }

    @Composable
    private fun GlassIconBtn(icon: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xB3132036))
                .border(1.dp, Color(0x4D38BDF8), RoundedCornerShape(12.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(icon, color = Color(0xFFE2E8F0), fontSize = 16.sp)
        }
    }

    @Composable
    private fun QuickActionPill(label: String, dotColor: Color, modifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0xCC0E1525))
                .border(1.dp, Color(0xFF2E4360), RoundedCornerShape(99.dp))
                .clickable { onClick() }
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(dotColor)
            )
        }
    }

    @Composable
    private fun MiniChip(label: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0xFF0E1525))
                .border(1.dp, Color(0xFF283B54), RoundedCornerShape(99.dp))
                .clickable { onClick() }
                .padding(horizontal = 11.dp, vertical = 7.dp)
        ) {
            Text(label, color = Color(0xFFCBD5E1), fontSize = 11.sp)
        }
    }

    @Composable
    private fun ChatBubbleItem(entry: ChatEntry) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (entry.isUser) Arrangement.End else Arrangement.Start
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (entry.isUser) Color(0xFF162336) else Color(0xCC0D1320))
                    .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (!entry.isUser) {
                    Text("🌪️ ${entry.title}", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                }
                Text(entry.body, color = Color(0xFFF1F5F9), fontSize = 13.5.sp)
            }
        }
    }

    @Composable
    private fun DrawerRow(label: String, onClick: () -> Unit) {
        Text(
            text = label,
            color = Color(0xFFE2E8F0),
            fontSize = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 11.dp)
        )
    }

    @Composable
    private fun DrawerStatusRow(badge: String, color: Color, title: String) {
        Row(
            modifier = Modifier.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(badge, color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color(0xFF94A3B8), fontSize = 12.sp)
        }
    }

    @Composable
    private fun NavChip(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (active) Color(0xFF0D2436) else Color(0xFF111726))
                .border(1.dp, if (active) Color(0xFF38BDF8) else Color(0xFF26354D), RoundedCornerShape(10.dp))
                .clickable { onClick() }
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = if (active) Color(0xFF38BDF8) else Color(0xFF94A3B8), fontSize = 11.sp)
        }
    }

    @Composable
    private fun PermRow(label: String, ok: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color(0xFFE2E8F0), fontSize = 12.sp)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (ok) Color(0xFF071E14) else Color(0xFF1F1607))
                    .border(1.dp, if (ok) Color(0xFF00FF88) else Color(0xFFF59E0B), RoundedCornerShape(99.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (ok) "READY" else "GRANT",
                    color = if (ok) Color(0xFF00FF88) else Color(0xFFF59E0B),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    private fun submitUserQuery(q: String, messages: MutableList<ChatEntry>) {
        messages.add(ChatEntry(true, "You", q))
        triggerServiceState("ACTION_BURST")
        messages.add(ChatEntry(false, "YUNA Local Core", "Queued: \"$q\" — Rust/Wasm Brain attaches in Level 2 & 4."))
    }

    private fun triggerServiceState(stateName: String) {
        bubbleMode.value = stateName
        if (!PuyalCoreService.isRunning) {
            ContextCompat.startForegroundService(this, Intent(this, PuyalCoreService::class.java))
        }
        startService(Intent(this, PuyalCoreService::class.java).apply {
            action = PuyalCoreService.ACTION_SET_STATE
            putExtra(PuyalCoreService.EXTRA_STATE, stateName)
        })
        serviceRunning.value = true
    }

    private fun toggleTorch() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return
            torchOn.value = !torchOn.value
            cm.setTorchMode(id, torchOn.value)
            toast(if (torchOn.value) "Torch ON" else "Torch OFF")
        } catch (e: Exception) {
            toast("Torch error: ${e.message}")
        }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { toast("Browser error: ${e.message}") }
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun refreshSystemState() {
        overlayGranted.value = Settings.canDrawOverlays(this)
        micGranted.value = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        notifGranted.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        batteryExempt.value = pm.isIgnoringBatteryOptimizations(packageName)
        serviceRunning.value = PuyalCoreService.isRunning
        bubbleMode.value = PuyalCoreService.currentStateName
    }

    private fun probeRootAndOomAsync() {
        Thread {
            val pid = Process.myPid()
            val rootOk = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val done = p.waitFor(3, TimeUnit.SECONDS)
                if (!done) { p.destroyForcibly(); false } else p.exitValue() == 0
            } catch (_: Exception) { false }
            val oom = try {
                File("/proc/$pid/oom_score_adj").readText().trim()
            } catch (_: Exception) { if (rootOk) "-1000" else "0" }
            hasRootState.value = rootOk
            oomScoreState.value = oom
        }.start()
    }
}
