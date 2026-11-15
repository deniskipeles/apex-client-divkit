import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
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
