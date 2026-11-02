package engine

import android.content.Context

actual sealed class PlatformContext {
    data class Android(val context: Context) : PlatformContext()
}
