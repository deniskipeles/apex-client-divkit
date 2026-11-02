import UIKit
import shared

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // KMP exposes the sync engine and the registration helper.
        // Task ID must be present in Info.plist (see above) or this crashes.
        let syncEngine = SharedSyncEngine(
            workspaceManager: SharedWorkspaceManager(
                driverFactory: WorkspaceDriverFactory()
            )
        )
        SharedSyncSchedulerKt.registerIosSyncHandler(engine: syncEngine)
        return true
    }
}
