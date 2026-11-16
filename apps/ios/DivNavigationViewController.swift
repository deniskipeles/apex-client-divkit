import UIKit
import DivKit
import Engine

/// Host-side navigation container. Attaches the active card's `DivView` and *detaches*
/// (never destroys) the outgoing one, so scroll offsets, focus, and animations survive
/// push/pop. The view instances live in the navigator's LRU cache and are reused by both
/// the stack and any bottom sheet.
///
/// App-target file: depends on the DivKit framework and is compiled by the Xcode app target,
/// not the Linux SPM package (whose `Engine` target holds the portable navigation core).
final class DivNavigationViewController: UIViewController {

    let navigator: DivCardNavigator<DivView>
    private let components: DivKitComponents
    private var activeView: DivView?

    init(entries: [CardEntry], components: DivKitComponents) {
        self.navigator = DivCardNavigator(initialCards: entries)
        self.components = components
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        syncToNavigator()
    }

    func syncToNavigator() {
        guard let entry = navigator.current else { return }

        let view = navigator.view(for: entry) {
            let v = DivView(components: components)
            v.setSource(DivViewSource(kind: .data(entry.data.serialized.data(using: .utf8) ?? Data()), cardId: entry.id))
            return v
        }

        // Same view already attached? Nothing to do.
        if activeView === view { return }

        // Detach (do not destroy) the outgoing view; it stays in navigator's cache.
        activeView?.removeFromSuperview()

        view.frame = self.view.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        self.view.addSubview(view)
        activeView = view
    }
}
