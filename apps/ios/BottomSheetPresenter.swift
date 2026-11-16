import UIKit
import DivKit
import Engine

/// Presents a card in a bottom sheet using the navigator's view cache, so a card presented
/// as a sheet and the same card on the stack share one `DivView` instance and one state.
///
/// App-target file (DivKit + UIKit); not part of the Linux SPM package.
final class BottomSheetPresenter {

    private weak var hostVC: UIViewController?
    private let navigator: DivCardNavigator<DivView>
    private let components: DivKitComponents
    private var current: UIViewController?

    init(hostVC: UIViewController, navigator: DivCardNavigator<DivView>, components: DivKitComponents) {
        self.hostVC = hostVC
        self.navigator = navigator
        self.components = components
    }

    func present(cardId: String, card: JsonValue) {
        let entry = CardEntry(id: cardId, data: card)

        let vc = UIViewController()
        vc.view.backgroundColor = .systemBackground
        vc.modalPresentationStyle = .pageSheet
        if let sheet = vc.sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
        }

        // Reuse the navigator's cache: sheet content is state-preserved.
        let view = navigator.view(for: entry) {
            let v = DivView(components: components)
            v.setSource(DivViewSource(kind: .data(card.serialized.data(using: .utf8) ?? Data()), cardId: cardId))
            return v
        }
        view.frame = vc.view.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        vc.view.addSubview(view)

        current = vc
        hostVC?.present(vc, animated: true)
    }

    func dismiss() {
        current?.dismiss(animated: true)
        current = nil
    }
}
