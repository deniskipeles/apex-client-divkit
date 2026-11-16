import Foundation

/// Routes parsed navigation actions to the stack and the sheet callbacks.
///
/// Resolution goes through `CardRegistry`: a `push`/`replace`/`reset` whose id does not
/// resolve is a silent no-op that still reports "handled" (no exception), matching the
/// contract. Returns `false` only for URLs the navigation layer does not own, so the caller
/// can fall through to other handlers.
public final class NavigationRouter<View: AnyObject> {
    public let navigator: DivCardNavigator<View>
    private let registry: CardRegistry

    /// Invoked when a sheet should present, with the resolved card id and its payload.
    public var onSheetPresent: ((String, JsonValue) -> Void)?
    public var onSheetDismiss: (() -> Void)?
    /// Invoked after any stack-mutating op (push/pop/replace/reset) so the host can re-sync.
    public var onChange: (() -> Void)?

    public init(navigator: DivCardNavigator<View>, registry: CardRegistry = .shared) {
        self.navigator = navigator
        self.registry = registry
    }

    @discardableResult
    public func handle(_ url: String) -> Bool {
        guard let op = NavigationUrl.parse(url) else { return false }

        switch op {
        case let .push(cardId):
            if let data = registry.card(id: cardId) {
                navigator.push(CardEntry(id: cardId, data: data))
                onChange?()
            }
        case .pop:
            navigator.pop()
            onChange?()
        case let .replace(cardId):
            if let data = registry.card(id: cardId) {
                navigator.replace(CardEntry(id: cardId, data: data))
                onChange?()
            }
        case let .reset(cardId):
            if let data = registry.card(id: cardId) {
                navigator.reset(CardEntry(id: cardId, data: data))
                onChange?()
            }
        case let .sheetPresent(cardId):
            if let data = registry.card(id: cardId) {
                onSheetPresent?(cardId, data)
            }
        case .sheetDismiss:
            onSheetDismiss?()
        }
        return true
    }
}
