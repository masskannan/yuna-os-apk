package com.puyal.os

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint as NativePaint
import android.graphics.Path as NativePath
import android.graphics.RectF as NativeRectF
import android.graphics.RuntimeShader
import android.graphics.Typeface as NativeTypeface
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
    float w1 = sin(uv.x * 3.2 + uTime * 0.45) * cos(uv.y * 2.5 - uTime * 0.3);
    float w2 = cos(uv.x * 2.4 - uv.y * 3.0 + uTime * 0.25);
    float cyanBand = smoothstep(0.85, 0.06, distance(uv, float2(0.86, 0.16 + 0.03 * w1)));
    float tealFlow = smoothstep(0.92, 0.10, distance(uv, float2(0.14, 0.48 + 0.04 * w2)));
    float violetHue = smoothstep(0.95, 0.12, distance(uv, float2(0.68, 0.56 - 0.03 * w1)));
    float3 base = float3(0.018, 0.024, 0.045);
    float3 cCyan = float3(0.000, 0.780, 0.940) * cyanBand * 0.46;
    float3 cBlue = float3(0.055, 0.520, 0.920) * tealFlow * 0.40;
    float3 cViolet = float3(0.486, 0.227, 0.929) * violetHue * 0.36;
    return half4(base + cCyan + cBlue + cViolet, 1.0);
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
    float f1 = sin(angle * 3.0 + d * 12.0 - uTime * 2.1) * cos(uv.y * 9.0 + uTime * 1.3);
    float f2 = cos(angle * 5.0 - d * 9.0 + uTime * 1.6) * sin(uv.x * 11.0 - uTime * 1.1);
    float filaments = smoothstep(-0.2, 0.85, f1 * 0.55 + f2 * 0.45);
    float sparks = pow(max(0.0, sin(uv.x * 28.0 + uTime) * cos(uv.y * 28.0 - uTime)), 8.0);
    float core = smoothstep(0.49, 0.04, d);
    float rim = smoothstep(0.49, 0.43, d) * smoothstep(0.34, 0.46, d);
    float3 violet = float3(0.486, 0.227, 0.929);
    float3 deep = float3(0.035, 0.060, 0.160);
    float3 col = mix(deep, mix(violet, uAccent, filaments), core);
    col += float3(0.65, 0.92, 1.0) * sparks * core * 0.75;
    col += float3(0.74, 0.92, 1.0) * rim * 0.85;
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
        setContent { YunaCloneScreen() }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
        probeRootAndOomAsync()
    }

    @Composable
    private fun YunaCloneScreen() {
        var inputText by remember { mutableStateOf("") }
        var navMode by remember { mutableStateOf("3BTN") }
        var showDrawer by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showPlusTools by remember { mutableStateOf(false) }
        val messages = remember { mutableStateListOf<ChatEntry>() }

        val activeMode = bubbleMode.value
        val isAnimating = activeMode != "IDLE"
        var animTime by remember { mutableFloatStateOf(0f) }

        // 0-FPS Idle Lock: animation frames advance ONLY when activeMode != IDLE
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
            // 1. Multi-layer AGSL Aurora Background (Cyan, Deep Blue, Vivid Purple/Violet)
            AgslAuroraLayer(timeSec = animTime)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = extraNavBottom)
            ) {
                // 2. Top Bar (Header): Menu | YUNA [20MB • LOCAL] | Settings Gear
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
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.2.sp
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(Color(0x990D1829))
                                .border(1.dp, Color(0x5538BDF8), RoundedCornerShape(99.dp))
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

                // 3. Center Scroll Stage
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (messages.isEmpty()) {
                        // Plasma Orb + Concentric Cybernetic HUD Telemetry Rings
                        Box(
                            modifier = Modifier
                                .size(214.dp)
                                .clickable {
                                    val next = if (bubbleMode.value == "LISTENING") "IDLE" else "LISTENING"
                                    triggerServiceState(next)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            CyberneticHudRings(
                                activeMode = activeMode,
                                oomText = "OOM ${oomScoreState.value}"
                            )
                            AgslPlasmaOrb(
                                modifier = Modifier.size(122.dp),
                                timeSec = animTime,
                                activeMode = activeMode
                            )
                        }

                        // 4. Greeting & Status Section
                        Text(
                            text = "Vanakkam Boss",
                            color = Color(0xFFFFFFFF),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (activeMode == "IDLE") "0-FPS IDLE • TAP ORB FOR MIC" else "$activeMode • ACTIVE",
                            color = Color(0xB3BAE6FD),
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )

                        Spacer(Modifier.height(16.dp))

                        // 5. Main Action Card ("Screen Vision & Scan") with embedded storm badge
                        ScreenVisionFrostedCard(
                            onClick = { submitUserQuery("Screen Vision & Scan", messages) },
                            onBadgeClick = { triggerServiceState("ACTION_BURST") }
                        )

                        Spacer(Modifier.height(12.dp))

                        // 6. Quick Action Pills Row (WA Intel, Root Shield, Torch)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuickActionPill("WA Intel", Color(0xFF00FF88), Modifier.weight(1f)) {
                                submitUserQuery("WhatsApp VIP Intel check", messages)
                            }
                            QuickActionPill("Root Shield", Color(0xFF00FF88), Modifier.weight(1f)) {
                                refreshSystemState()
                                probeRootAndOomAsync()
                                showSettings = true
                            }
                            QuickActionPill(
                                if (torchOn.value) "Torch ON" else "Torch",
                                Color(0xFFA855F7),
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

                // 7. Bottom Input Bar (Gemini Style Floating Pill)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(29.dp))
                        .background(Color(0xD90E1525))
                        .border(1.dp, Color(0x5538BDF8), RoundedCornerShape(29.dp))
                        .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF141E30))
                            .border(1.dp, Color(0x4438BDF8), CircleShape)
                            .clickable { showPlusTools = !showPlusTools },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("＋", color = Color(0xFFE2E8F0), fontSize = 19.sp)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (inputText.isEmpty()) {
                            Text("Yuna kitta sollunga...", color = Color(0xFF94A3B8), fontSize = 14.5.sp)
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

                    AudioWaveformBars(active = activeMode == "LISTENING") {
                        val next = if (bubbleMode.value == "LISTENING") "IDLE" else "LISTENING"
                        triggerServiceState(next)
                        if (next == "LISTENING") {
                            messages.add(ChatEntry(false, "YUNA Mic ON", "Level 3 Tamil STT hook active (0-FPS resumes on stop)."))
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C3AED))))
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

            // Left Drawer (New Chat, Files, Skills, Schedules, ALL CHATS)
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

            // Settings Modal (3-Btn / 2-Btn / Gesture + Immortal Bubble + Perms)
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
                            colors = listOf(Color(0x4500E5FF), Color(0x387C3AED), Color(0xFF05070C)),
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
            else -> floatArrayOf(0.0f, 0.86f, 0.98f)
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
                        listOf(Color(0xFFBAE6FD), c, Color(0xFF7C3AED), Color(0xFF080D1C))
                    )
                )
            }
        }
    }

    @Composable
    private fun CyberneticHudRings(activeMode: String, oomText: String) {
        val ringColor = when (activeMode) {
            "LISTENING" -> Color(0xFF00E5FF)
            "ACTION_BURST" -> Color(0xFF00FF88)
            "PROCESSING" -> Color(0xFFF59E0B)
            else -> Color(0x667DD3FC)
        }
        val textPaint = remember {
            NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(190, 203, 213, 225)
                typeface = NativeTypeface.MONOSPACE
            }
        }
        val path = remember { NativePath() }
        val rect = remember { NativeRectF() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val r1 = size.minDimension * 0.34f
            val r2 = size.minDimension * 0.42f
            val r3 = size.minDimension * 0.48f
            drawCircle(color = ringColor, radius = r1, style = Stroke(width = 1.2.dp.toPx()))
            drawCircle(color = ringColor.copy(alpha = 0.55f), radius = r2, style = Stroke(width = 0.9.dp.toPx()))
            drawCircle(color = ringColor.copy(alpha = 0.28f), radius = r3, style = Stroke(width = 0.8.dp.toPx()))

            textPaint.textSize = 8.5.sp.toPx()
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                rect.set(center.x - r2, center.y - r2, center.x + r2, center.y + r2)
                path.reset(); path.addArc(rect, -125f, 70f)
                nc.drawTextOnPath(oomText, path, 0f, -3.dp.toPx(), textPaint)

                path.reset(); path.addArc(rect, 118f, -56f)
                nc.drawTextOnPath("0-FPS", path, 0f, 10.dp.toPx(), textPaint)

                rect.set(center.x - r3, center.y - r3, center.x + r3, center.y + r3)
                path.reset(); path.addArc(rect, 152f, 55f)
                nc.drawTextOnPath("AIDL-IPC", path, 0f, -3.dp.toPx(), textPaint)

                path.reset(); path.addArc(rect, -32f, 65f)
                nc.drawTextOnPath("OFFLINE SECURE", path, 0f, -3.dp.toPx(), textPaint)
            }
        }
    }

    @Composable
    private fun ScreenVisionFrostedCard(onClick: () -> Unit, onBadgeClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(94.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x557DD3FC), RoundedCornerShape(20.dp))
                .clickable { onClick() }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(Color(0xD9102238), Color(0xD9141830))
                    )
                )
                val wave1 = Path().apply {
                    moveTo(size.width * 0.56f, 0f)
                    cubicTo(size.width * 0.72f, size.height * 0.36f, size.width * 0.86f, size.height * 0.14f, size.width, size.height * 0.48f)
                    lineTo(size.width, 0f)
                    close()
                }
                drawPath(wave1, color = Color(0x3800E5FF))

                val wave2 = Path().apply {
                    moveTo(0f, size.height * 0.54f)
                    cubicTo(size.width * 0.16f, size.height * 0.86f, size.width * 0.34f, size.height * 0.70f, size.width * 0.48f, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(wave2, color = Color(0x2E7C3AED))
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Screen Vision & Scan",
                        color = Color(0xFFF8FAFC),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "DOM + OCR local screen reader",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.5.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⛶", color = Color(0xFF7DD3FC), fontSize = 13.sp)
                        Text("☷", color = Color(0xFF94A3B8), fontSize = 13.sp)
                        Text("🎙️", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0x5500E5FF), Color(0xFF07111E))))
                        .border(1.8.dp, Color(0xFF00E5FF), CircleShape)
                        .clickable { onBadgeClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("🌪️", fontSize = 23.sp)
                }
            }
        }
    }

    @Composable
    private fun AudioWaveformBars(active: Boolean, onClick: () -> Unit) {
        val barColor = if (active) Color(0xFF00FF88) else Color(0xFF38BDF8)
        Canvas(
            modifier = Modifier
                .size(36.dp)
                .clickable { onClick() }
        ) {
            val heights = listOf(0.24f, 0.44f, 0.68f, 0.88f, 0.58f, 0.38f, 0.22f)
            val startX = size.width * 0.14f
            val step = (size.width * 0.72f) / 6f
            heights.forEachIndexed { idx, frac ->
                val x = startX + idx * step
                val halfH = size.height * frac / 2f
                drawLine(
                    color = barColor,
                    start = Offset(x, center.y - halfH),
                    end = Offset(x, center.y + halfH),
                    strokeWidth = 2.dp.toPx(),
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
                .border(1.dp, Color(0x5538BDF8), RoundedCornerShape(12.dp))
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
