import UIKit
import Engine

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // Platform bridge for the tools://keep-awake capability: toggle the idle timer so a
        // card can hold the terminal awake during long-running tasks.
        KeepAwakeTool.sink = { enabled in
            UIApplication.shared.isIdleTimerDisabled = enabled
            return true
        }
        TerminalRegistry.shared.register(IosHapticsTool())
        TerminalRegistry.shared.register(IosBeeperTool())
        TerminalRegistry.shared.register(IosCameraTool())
        TerminalRegistry.shared.register(IosScannerTool())
        TerminalRegistry.shared.register(IosImageManipulationTool())
        TerminalRegistry.shared.register(IosSignalTool())
        TerminalRegistry.shared.register(IosVectorizeTool())
        return true
    }
}
