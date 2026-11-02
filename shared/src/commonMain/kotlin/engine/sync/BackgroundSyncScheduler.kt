package engine.sync

import engine.PlatformContext

/**
 * Platform-agnostic background scheduling.
 *
 * Android → WorkManager PeriodicWorkRequest with network constraint.
 * iOS     → BGTaskScheduler BGAppRefreshTask (opportunistic, ~30s budget).
 *
 * iOS note: task identifiers must be declared in Info.plist under
 * `BGTaskSchedulerPermittedIdentifiers`. Registering without that entry
 * causes a runtime crash on iOS.
 */
expect class BackgroundSyncScheduler(context: PlatformContext) {
    fun schedule(workspaceId: String, endpoint: String, intervalMs: Long)
    fun syncNow(workspaceId: String, endpoint: String)
    fun cancelAll()
}
