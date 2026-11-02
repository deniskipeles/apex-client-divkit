package engine

import android.content.Context

sealed class PlatformContext {
    data class Android(val context: Context) : PlatformContext()
}
