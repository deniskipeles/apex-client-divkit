package engine.sync

import android.content.Context
import androidx.work.*
import engine.PlatformContext
import engine.db.WorkspaceDriverFactory
import engine.db.WorkspaceManager
import java.util.concurrent.TimeUnit

class BackgroundSyncScheduler(private val context: PlatformContext) {

    private val appContext: Context = (context as PlatformContext.Android).context

    fun schedule(workspaceId: String, endpoint: String, intervalMs: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<AndroidSyncWorker>(
            intervalMs.coerceAtLeast(TimeUnit.MINUTES.toMillis(15)),
            TimeUnit.MILLISECONDS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf("workspace_id" to workspaceId, "endpoint" to endpoint))
            .build()

        try {
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                "$WORK_NAME.$workspaceId",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } catch (_: Exception) {
            // WorkManager may not be initialized in unit test environments
        }
    }

    fun syncNow(workspaceId: String, endpoint: String) {
        val request = OneTimeWorkRequestBuilder<AndroidSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setInputData(workDataOf("workspace_id" to workspaceId, "endpoint" to endpoint))
            .build()
        try {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                "$WORK_NAME.$workspaceId.now",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        } catch (_: Exception) {
            // Ignore in non-WorkManager test contexts
        }
    }

    fun cancelAll() {
        try {
            WorkManager.getInstance(appContext).cancelAllWork()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val WORK_NAME = "engine.sync"
    }
}

class AndroidSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ws = inputData.getString("workspace_id") ?: return Result.failure()
        val endpoint = inputData.getString("endpoint") ?: return Result.failure()
        val engine = SyncEngine(
            workspaceManager = WorkspaceManager(WorkspaceDriverFactory(applicationContext)),
            http = createHttpClient()
        )
        return when (engine.sync(ws, endpoint)) {
            SyncOutcome.Success -> Result.success()
            SyncOutcome.Retry -> Result.retry()
        }
    }
}
