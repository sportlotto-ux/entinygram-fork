package desu.inugram.helpers.calls

// entiny: jni bridge to the webrtc tap in inu_audio_tap.cc
internal object InuCallTap {
    const val IN = 0
    const val OUT = 1

    external fun nativeStart()

    external fun nativeStop()

    external fun nativeRead(dir: Int, out: ByteArray): Int

    external fun nativeDropped(dir: Int): Int
}
