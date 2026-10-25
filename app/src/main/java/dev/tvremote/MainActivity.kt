package dev.tvremote

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.tvremote.core.RemoteKey
import dev.tvremote.ir.IrRemote
import dev.tvremote.ui.RemoteScreen
import dev.tvremote.ui.routeRemote
import dev.tvremote.wifi.WifiRemote

class MainActivity : ComponentActivity() {
    // Created in onCreate: Context is not attached before that.
    private lateinit var ir: IrRemote
    private lateinit var wifi: WifiRemote

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ir = Remotes.ir(this)
        wifi = Remotes.wifi(this)
        setContent { RemoteScreen(ir, wifi) }
    }

    override fun onStart() {
        super.onStart()
        wifi.onStart()
    }

    override fun onStop() {
        wifi.onStop(isChangingConfigurations)
        super.onStop()
    }

    // Phone volume keys drive the TV while this activity is foregrounded.
    // Foreground only; controlling the TV from the background would need an AccessibilityService.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = volumeKey(keyCode) ?: return super.onKeyDown(keyCode, event)
        // Drop IR auto-repeats while the previous burst is still transmitting instead of queueing them.
        if (event.repeatCount > 0 && !wifi.available && ir.busy) return true
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        routeRemote(ir, wifi, key)
        return true // consumed, so the system volume panel stays hidden
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        volumeKey(keyCode) != null || super.onKeyUp(keyCode, event)

    private fun volumeKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VOL_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VOL_DOWN
        KeyEvent.KEYCODE_VOLUME_MUTE -> RemoteKey.MUTE
        else -> null
    }
}
