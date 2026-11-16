import UIKit
import DivKit
import Engine

/// Bridges DivKit `nav://` and `sheet://` actions to the portable `NavigationRouter`.
///
/// Registered once when building `DivKitComponents`. `handle` returns `true` when the URL
/// belongs to the navigation layer (including the silent no-op case where a card id does not
/// resolve) and `false` otherwise, so DivKit's built-in handling continues for other schemes.
///
/// App-target file (DivKit + UIKit); not part of the Linux SPM package.
final class NavigationUrlHandler: DivUrlHandler {

    private let router: NavigationRouter<DivView>
    weak var navigationController: DivNavigationViewController?

    init(router: NavigationRouter<DivView>, navigationController: DivNavigationViewController?) {
        self.router = router
        self.navigationController = navigationController
        // Re-attach the active view after a stack change.
        router.navigator.onChange = { [weak navigationController] in
            navigationController?.syncToNavigator()
        }
    }

    func handle(url: URL, context: DivActionContext) -> Bool {
        let handled = router.handle(url.absoluteString)
        if handled {
            navigationController?.syncToNavigator()
        }
        return handled
    }
}
