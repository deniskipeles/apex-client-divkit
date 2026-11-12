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


// Register iOS native Signal tool handler into the universal ToolRegistry
// TerminalApp.shared.registry.register(capability: "crypto.signal@1", handler: IosSignalTool())
// TerminalApp.shared.registry.register(capability: "libsignal", handler: IosSignalTool())
// TerminalApp.shared.registry.register(capability: "hardware.camera@1", handler: IosCameraTool())
// TerminalApp.shared.registry.register(capability: "camera", handler: IosCameraTool())
// TerminalApp.shared.registry.register(capability: "hardware.haptics@1", handler: IosHapticsTool())
// TerminalApp.shared.registry.register(capability: "haptics", handler: IosHapticsTool())
// TerminalApp.shared.registry.register(capability: "hardware.beeper@1", handler: IosBeeperTool())
// TerminalApp.shared.registry.register(capability: "beeper", handler: IosBeeperTool())
// TerminalApp.shared.registry.register(capability: "audio", handler: IosBeeperTool())
// TerminalApp.shared.registry.register(capability: "vision.image_manipulation@1", handler: IosImageManipulationTool())
// TerminalApp.shared.registry.register(capability: "image-manipulation", handler: IosImageManipulationTool())