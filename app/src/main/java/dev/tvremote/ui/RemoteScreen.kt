package dev.tvremote.ui

import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tvremote.core.RemoteKey
import dev.tvremote.core.RemoteTransport
import dev.tvremote.ir.IrRemote
import dev.tvremote.wifi.WifiRemote
import dev.tvremote.wifi.WifiState
import kotlin.math.atan2
import kotlin.math.sqrt

// iOS dark system colours.
val Gray1 = Color(0xFF1C1C1E)
val Gray2 = Color(0xFF2C2C2E)
val Gray3 = Color(0xFF3A3A3C)
val GrayText = Color(0xFF8E8E93)
val Blue = Color(0xFF0A84FF)
val Red = Color(0xFFFF453A)
val Green = Color(0xFF30D158)

// Type scale: titles 17sp SemiBold, labels 13sp Medium, captions 12sp.
val TitleStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
val LabelStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
val BodyStyle = TextStyle(fontSize = 17.sp)
val CaptionStyle = TextStyle(fontSize = 12.sp)

private val Capsule = RoundedCornerShape(50)

/** D-pad wedge start angles (Compose degrees: 0 = 3 o'clock, clockwise), each wedge spans 90 degrees. */
private val Wedges = mapOf(
    RemoteKey.UP to -135f, RemoteKey.RIGHT to -45f, RemoteKey.DOWN to 45f, RemoteKey.LEFT to 135f,
)

/** Routing shared by the UI and the phone volume keys: Wi-Fi when connected, otherwise IR. */
fun routeRemote(ir: IrRemote, wifi: WifiRemote, key: RemoteKey) {
    val t: RemoteTransport = if (wifi.available) wifi else ir
    t.send(key)
}

@Composable
fun RemoteScreen(ir: IrRemote, wifi: WifiRemote) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color.Black, surface = Gray1, onBackground = Color.White,
            onSurface = Color.White, primary = Blue,
        ),
    ) {
        val state by wifi.state.collectAsState()
        var sheet by rememberSaveable { mutableStateOf(false) }
        var about by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) { if (!ir.available && !wifi.available) sheet = true }

        fun send(key: RemoteKey) = routeRemote(ir, wifi, key)
        val chip = when (val s = state) {
            is WifiState.Connected -> s.name
            else -> if (ir.available) "IR" else "Not connected"
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Whole group is centred; scrolls only when the screen is too short for it.
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Header(
                        chip, state is WifiState.Connected,
                        onAbout = { about = true }, onPill = { sheet = true }, onPower = { send(RemoteKey.POWER) },
                    )
                    Spacer(Modifier.height(24.dp))
                    DPad(::send)
                    Spacer(Modifier.height(24.dp))
                    ControlGrid(::send)
                    Spacer(Modifier.height(24.dp))
                    AppsRow(::send)
                }
            }
            WifiSheet(open = sheet, onDismiss = { sheet = false }, wifi = wifi)
            AboutSheet(open = about, onDismiss = { about = false })
        }
    }
}

@Composable
private fun Header(name: String, connected: Boolean, onAbout: () -> Unit, onPill: () -> Unit, onPower: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Press("About", onAbout, Modifier.size(44.dp), CircleShape, Gray1) {
            Icon(Icons.Rounded.Info, null, Modifier.size(24.dp), Color.White)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Press("Device $name. Open TV connection", onPill, Modifier.height(44.dp), Capsule, Gray1, Gray2) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (connected) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Green))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(name, style = TitleStyle, color = Color.White, maxLines = 1)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(24.dp), GrayText)
                }
            }
        }
        Press("Power", onPower, Modifier.size(44.dp), CircleShape, Gray2) {
            Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(24.dp), Red)
        }
    }
}

/**
 * Circular D-pad: ring split into four 90-degree tap wedges, chevrons at the compass points,
 * OK disc in the centre. Ring is 78% of screen width, capped at 320dp.
 */
@Composable
private fun DPad(send: (RemoteKey) -> Unit) {
    val view = LocalView.current
    val reduced = reducedMotion()
    val ring = minOf(LocalConfiguration.current.screenWidthDp * 0.78f, 320f).dp
    var wedge by remember { mutableStateOf(RemoteKey.UP) }
    var held by remember { mutableStateOf(false) }
    var pxSize by remember { mutableStateOf(IntSize.Zero) }
    val glow by animateFloatAsState(if (held) 1f else 0f, tween(if (reduced) 0 else 120))
    val chevronTint = Color.White.copy(alpha = 0.7f)
    val inset = ring * 0.10f

    Box(
        Modifier.size(ring).clip(CircleShape).background(Gray1)
            .semantics {
                contentDescription = "Directional pad"
                customActions = listOf(
                    CustomAccessibilityAction("Up") { send(RemoteKey.UP); true },
                    CustomAccessibilityAction("Down") { send(RemoteKey.DOWN); true },
                    CustomAccessibilityAction("Left") { send(RemoteKey.LEFT); true },
                    CustomAccessibilityAction("Right") { send(RemoteKey.RIGHT); true },
                )
            }
            .onSizeChanged { pxSize = it }
            .pointerInput(pxSize) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val key = wedgeAt(down.position, pxSize) ?: return@awaitEachGesture
                    wedge = key
                    held = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    send(key)
                    waitForUpOrCancellation()
                    held = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (glow > 0f) drawArc(Gray2, Wedges.getValue(wedge), 90f, useCenter = true, alpha = glow)
        }
        Icon(Icons.Rounded.KeyboardArrowUp, "Up", Modifier.align(Alignment.TopCenter).padding(top = inset).size(28.dp), chevronTint)
        Icon(Icons.Rounded.KeyboardArrowDown, "Down", Modifier.align(Alignment.BottomCenter).padding(bottom = inset).size(28.dp), chevronTint)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Left", Modifier.align(Alignment.CenterStart).padding(start = inset).size(28.dp), chevronTint)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Right", Modifier.align(Alignment.CenterEnd).padding(end = inset).size(28.dp), chevronTint)
        Press(
            "OK", { send(RemoteKey.OK) }, Modifier.size(ring * 0.4f), CircleShape, Blue,
            pressedColor = Blue.copy(alpha = 0.8f),
            brush = Brush.verticalGradient(listOf(Color(0xFF409CFF), Blue)),
        ) {
            Text("OK", style = TitleStyle, color = Color.White)
        }
    }
}

/**
 * Tap wedge for a point in the ring, or null inside the OK disc (radius 0.4 x ring radius)
 * and outside the ring. Wedges are split by angle from the centre.
 */
private fun wedgeAt(p: Offset, size: IntSize): RemoteKey? {
    val dx = p.x - size.width / 2f
    val dy = p.y - size.height / 2f
    val r = sqrt(dx * dx + dy * dy)
    val ringR = minOf(size.width, size.height) / 2f
    if (r < ringR * 0.4f || r > ringR) return null
    val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    return when {
        deg >= -45f && deg < 45f -> RemoteKey.RIGHT
        deg >= 45f && deg < 135f -> RemoteKey.DOWN
        deg >= -135f && deg < -45f -> RemoteKey.UP
        else -> RemoteKey.LEFT
    }
}

/** Row A: four equal circles spread across the width. Row B: Mute circle, then the volume pill. */
@Composable
private fun ControlGrid(send: (RemoteKey) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundKey("Back", Icons.AutoMirrored.Rounded.ArrowBack) { send(RemoteKey.BACK) }
            RoundKey("Home", Icons.Rounded.Home) { send(RemoteKey.HOME) }
            RoundKey("Menu", Icons.Rounded.Menu) { send(RemoteKey.MENU) }
            RoundKey("Source", Icons.AutoMirrored.Rounded.Input) { send(RemoteKey.SOURCE) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundKey("Mute", Icons.AutoMirrored.Rounded.VolumeOff) { send(RemoteKey.MUTE) }
            VolumePill(send)
        }
    }
}

@Composable
private fun RoundKey(desc: String, icon: ImageVector, onClick: () -> Unit) =
    Press(desc, onClick, Modifier.size(60.dp), CircleShape, Gray2) {
        Icon(icon, null, Modifier.size(24.dp), Color.White)
    }

/** 60dp pill: "−" | divider | "+", each half its own tap target. */
@Composable
private fun RowScope.VolumePill(send: (RemoteKey) -> Unit) {
    Row(
        Modifier.weight(1f).height(60.dp).clip(Capsule).background(Gray2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Press("Volume down", { send(RemoteKey.VOL_DOWN) }, Modifier.weight(1f).fillMaxHeight(), RectangleShape, Gray2) {
            Icon(Icons.Rounded.Remove, null, Modifier.size(24.dp), Color.White)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Gray3))
        Press("Volume up", { send(RemoteKey.VOL_UP) }, Modifier.weight(1f).fillMaxHeight(), RectangleShape, Gray2) {
            Icon(Icons.Rounded.Add, null, Modifier.size(24.dp), Color.White)
        }
    }
}

@Composable
private fun AppsRow(send: (RemoteKey) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppKey("Netflix", RemoteKey.NETFLIX, send)
        AppKey("YouTube", RemoteKey.YOUTUBE, send)
        AppKey("Prime Video", RemoteKey.PRIME, send)
    }
}

@Composable
private fun RowScope.AppKey(label: String, key: RemoteKey, send: (RemoteKey) -> Unit) =
    Press("Open $label", { send(key) }, Modifier.weight(1f).height(44.dp), Capsule, Gray1) {
        Text(label, style = LabelStyle, color = Color.White, maxLines = 1)
    }

/**
 * Pressable surface: 0.96 spring scale, pressed fill, KEYBOARD_TAP haptic, a11y label.
 * [brush] (if set) replaces [color] while not pressed. Reduced motion = no scale animation.
 */
@Composable
fun Press(
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier,
    shape: Shape,
    color: Color,
    pressedColor: Color = Gray3,
    scaled: Boolean = true,
    brush: Brush? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val reduced = reducedMotion()
    val scale by animateFloatAsState(
        if (pressed && scaled) 0.96f else 1f,
        if (reduced) snap<Float>() else spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
    )
    val view = LocalView.current
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (brush != null && !pressed) brush else SolidColor(if (pressed) pressedColor else color))
            .clickable(src, null, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun reducedMotion(): Boolean {
    val cr = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
