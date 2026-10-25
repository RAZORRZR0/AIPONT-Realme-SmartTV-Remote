package dev.tvremote.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tvremote.R

private val AboutShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private const val GITHUB_URL = "https://github.com/RAZORRZR0"

/** Bottom sheet with app name, version, description, developer and GitHub link. Same chrome as WifiSheet. */
// Version literal mirrors versionName in app/build.gradle.kts.
@Composable
fun BoxScope.AboutSheet(open: Boolean, onDismiss: () -> Unit) {
    val reduced = reducedMotion()
    val context = LocalContext.current
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f

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
                .clip(AboutShape)
                .background(Gray1)
                .navigationBarsPadding()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Gray3))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.app_full_name), style = TitleStyle, color = Color.White)
            Text(stringResource(R.string.about_version), style = CaptionStyle, color = GrayText)
            Text(stringResource(R.string.about_description), style = CaptionStyle, color = GrayText)
            Text(
                stringResource(R.string.about_made_by, stringResource(R.string.author_name)),
                style = CaptionStyle, color = GrayText,
            )
            Group {
                InfoRow(stringResource(R.string.about_developer), stringResource(R.string.author_name))
                Separator()
                Press(
                    stringResource(R.string.about_github_desc),
                    { openGitHub(context) },
                    Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    RectangleShape, Gray2, Gray3, scaled = false,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.about_github), style = BodyStyle, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.about_github_handle), style = BodyStyle, color = GrayText)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(24.dp), GrayText)
                    }
                }
            }
        }
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Gray2), content = content)

/** Inset hairline between rows inside a group. */
@Composable
private fun Separator() = Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Gray3))

@Composable
private fun InfoRow(label: String, value: String) =
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = BodyStyle, color = Color.White)
        Text(value, style = BodyStyle, color = GrayText)
    }

/** Opens the GitHub profile; silently does nothing when no app can handle the link. */
private fun openGitHub(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
    } catch (e: ActivityNotFoundException) {
        // No browser installed: show nothing.
    }
}
