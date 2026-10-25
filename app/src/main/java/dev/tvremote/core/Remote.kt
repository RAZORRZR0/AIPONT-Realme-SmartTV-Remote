package dev.tvremote.core

/** necCmd: NEC command byte (address 0x40). androidKeyCode: KeyEvent code for Wi-Fi; -1 = app-link launch. */
enum class RemoteKey(val necCmd: Int, val androidKeyCode: Int) {
    POWER(0x12, 26), MUTE(0x10, 164), OK(0x0A, 23), UP(0x19, 19), DOWN(0x1D, 20),
    LEFT(0x46, 21), RIGHT(0x47, 22), BACK(0x40, 4), HOME(0x51, 3), MENU(0x5B, 82),
    NETFLIX(0x58, -1), YOUTUBE(0x55, -1), PRIME(0x50, -1), SOURCE(0x14, 178),
    VOL_DOWN(0x1E, 25), VOL_UP(0x1A, 24)
}

interface RemoteTransport {
    val available: Boolean
    fun send(key: RemoteKey)
}
