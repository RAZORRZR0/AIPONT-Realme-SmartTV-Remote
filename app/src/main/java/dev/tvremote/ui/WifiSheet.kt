package dev.tvremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tvremote.wifi.TvDevice
import dev.tvremote.wifi.WifiRemote
import dev.tvremote.wifi.WifiState

private val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val SheetCapsule = RoundedCornerShape(50)

/** Bottom sheet for Wi-Fi discovery, PIN pairing and connection state. Solid #1C1C1E, no glass. */
@Composable
fun BoxScope.WifiSheet(open: Boolean, onDismiss: () -> Unit, wifi: WifiRemote) {
    val state by wifi.state.collectAsState()
    val reduced = reducedMotion()

    val idle = state is WifiState.Idle
    LaunchedEffect(open, idle) { if (!open) wifi.stopSearch() else if (idle) wifi.discover() }

    AnimatedVisibility(
        visible = open,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(tween(if (reduced) 0 else 200)),
        exit = fadeOut(tween(if (reduced) 0 else 200)),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = open,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (reduced) fadeIn(tween(0))
        else slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = if (reduced) fadeOut(tween(0)) else slideOutVertically { it } + fadeOut(),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(SheetShape)
                .background(Gray1)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 5.dp).clip(SheetCapsule).background(Gray3))
            Text("Connect to TV", style = TitleStyle, color = Color.White)
            Text("Same Wi-Fi network", style = CaptionStyle, color = GrayText)

            when (val s = state) {
                is WifiState.Idle, is WifiState.Discovering -> {
                    val devices = (s as? WifiState.Discovering)?.devices.orEmpty()
                    Group {
                        devices.forEach { d ->
                            DeviceRow(d) { wifi.connect(d) }
                            Separator()
                        }
                        SpinnerRow("Searching…")
                    }
                }
                is WifiState.Pairing -> Group { SpinnerRow("Pairing…") }
                is WifiState.AwaitingPin -> PinEntry(onSubmit = { wifi.submitPin(it) }, onCancel = { wifi.disconnect() })
                is WifiState.Connected -> Group {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Green))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(s.name, style = BodyStyle, color = Color.White, maxLines = 1)
                            Text("Connected", style = CaptionStyle, color = GrayText)
                        }
                    }
                    Separator()
                    Press("Disconnect", { wifi.disconnect() }, Modifier.fillMaxWidth().heightIn(min = 52.dp), RectangleShape, Gray2, Gray3, scaled = false) {
                        Text("Disconnect", style = BodyStyle, color = Red)
                    }
                }
                is WifiState.Error -> Group {
                    Text(s.message, style = BodyStyle, color = GrayText, modifier = Modifier.fillMaxWidth().padding(16.dp))
                    Separator()
                    Press("Retry", { wifi.discover() }, Modifier.fillMaxWidth().heightIn(min = 52.dp), RectangleShape, Gray2, Gray3, scaled = false) {
                        Text("Retry", style = BodyStyle, color = Blue)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinEntry(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Text("Enter the 6-character code on your TV", style = CaptionStyle, color = GrayText, textAlign = TextAlign.Center)
    BasicTextField(
        value = pin,
        onValueChange = { v -> pin = v.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6) },
        modifier = Modifier.focusRequester(focus),
        textStyle = TextStyle(color = Color.Transparent),
        singleLine = true,
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (pin.length == 6) onSubmit(pin) }),
        decorationBox = { inner ->
            // Six cells; the real field sits on top with transparent text so typing and focus still work.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { i ->
                        Box(
                            Modifier.size(width = 44.dp, height = 56.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (i == pin.length) Gray3 else Gray2),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                pin.getOrNull(i)?.toString().orEmpty(),
                                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                            )
                        }
                    }
                }
                Box(Modifier.matchParentSize()) { inner() }
            }
        },
    )

    PrimaryButton("Pair", enabled = pin.length == 6) { onSubmit(pin) }
    Press("Cancel", onCancel, Modifier.fillMaxWidth().height(44.dp), SheetCapsule, Color.Transparent, Gray3) {
        Text("Cancel", style = BodyStyle, color = Blue)
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) =
    Press(
        label,
        { if (enabled) onClick() },
        Modifier.fillMaxWidth().height(50.dp).alpha(if (enabled) 1f else 0.35f),
        RoundedCornerShape(14.dp),
        Blue,
        pressedColor = Blue.copy(alpha = 0.8f),
    ) {
        Text(label, style = BodyStyle.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
    }

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Gray2), content = content)

/** Inset hairline between rows inside a group. */
@Composable
private fun Separator() = Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Gray3))

@Composable
private fun SpinnerRow(label: String) =
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = GrayText, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, style = BodyStyle, color = GrayText)
    }

@Composable
private fun DeviceRow(d: TvDevice, onClick: () -> Unit) =
    Press("Connect to ${d.name}", onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), RectangleShape, Gray2, Gray3, scaled = false) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = BodyStyle, color = Color.White, maxLines = 1)
                Text(d.host, style = CaptionStyle, color = GrayText, maxLines = 1)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(24.dp), GrayText)
        }
    }
