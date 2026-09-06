// Android JNI entry point from iroh-ffi v1.1.0 (MIT OR Apache-2.0).
// https://github.com/n0-computer/iroh-ffi/blob/v1.1.0/kotlin/android/src/main/kotlin/computer/iroh/IrohAndroid.kt
package computer.iroh

import android.content.Context

object IrohAndroid {
    init { System.loadLibrary("iroh_ffi") }
    @JvmStatic external fun installAndroidContext(context: Context)
}
