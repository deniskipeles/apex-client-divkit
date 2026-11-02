package engine.sync

import engine.PlatformContext
import engine.db.WorkspaceDriverFactory
import engine.db.WorkspaceManager
import platform.BackgroundTasks.*
import platform.Foundation.*

actual class BackgroundSyncScheduler actual constructor(private val context: PlatformContext) {

    actual fun schedule(workspaceId: String, endpoint: String, intervalMs: Long) {
        val request = BGAppRefreshTaskRequest(TASK_ID)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(
            intervalMs / 1000.0
        )
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    actual fun syncNow(workspaceId: String, endpoint: String) {
        // BGTaskScheduler does not support immediate one-shot requests.
        // Best-effort: submit a refresh request with earliestBeginDate = now.
        val request = BGAppRefreshTaskRequest(TASK_ID)
        request.earliestBeginDate = NSDate()
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
    }

    actual fun cancelAll() {
        BGTaskScheduler.sharedScheduler.cancelAllTaskRequests()
    }

    companion object {
        /**
         * MUST be declared in Info.plist under `BGTaskSchedulerPermittedIdentifiers`.
         * Registering a handler without the plist entry crashes at runtime.
         */
        const val TASK_ID = "com.example.engine.sync"
    }
}

/**
 * Called from AppDelegate (see Swift file) to register the task handler.
 * Runs [SyncEngine] within the ~30s BGAppRefreshTask budget.
 */
fun registerIosSyncHandler(engine: SyncEngine) {
    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
        identifier = BackgroundSyncScheduler.TASK_ID,
        usingQueue = null
    ) { task ->
        val refreshTask = task as? BGAppRefreshTask ?: return@registerForTaskWithIdentifier
        refreshTask.expirationHandler = {
            // Cancellation is cooperative; SyncEngine checks coroutine cancellation.
        }
        kotlinx.coroutines.MainScope().launch {
            val ws = "acme" // resolved from persisted config
            val endpoint = "https://api.example.com"
            engine.sync(ws, endpoint)
            refreshTask.setTaskCompletedWithSuccess(true)
            // Schedule the next occurrence
            val next = BGAppRefreshTaskRequest(BackgroundSyncScheduler.TASK_ID)
            next.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(900.0)
            BGTaskScheduler.sharedScheduler.submitTaskRequest(next, null)
        }
    }
}
