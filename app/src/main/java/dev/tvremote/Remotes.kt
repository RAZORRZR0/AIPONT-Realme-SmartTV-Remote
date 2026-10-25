package dev.tvremote

import android.content.Context
import dev.tvremote.ir.IrRemote
import dev.tvremote.wifi.WifiRemote

/** Process-wide transports so a configuration change (rotation) keeps the live TV socket and state. */
object Remotes {
    private var ir: IrRemote? = null
    private var wifi: WifiRemote? = null

    @Synchronized fun ir(context: Context): IrRemote = ir ?: IrRemote(context.applicationContext).also { ir = it }

    @Synchronized fun wifi(context: Context): WifiRemote = wifi ?: WifiRemote(context.applicationContext).also { wifi = it }
}
