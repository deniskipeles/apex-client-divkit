import Foundation

/// A parsed `nav://` / `sheet://` action. Mirrors the shape pinned by
/// packages/contract/fixtures/navigation/v1 and parsed identically on every platform.
public enum NavigationOp: Equatable {
    case push(cardId: String)
    case pop
    case replace(cardId: String)
    case reset(cardId: String)
    case sheetPresent(cardId: String)
    case sheetDismiss

    /// The fixture-facing encoding: `{"op": "...", "cardId": "..."}`, omitting `cardId`
    /// for the parameterless ops. Used by NavigationContractTests to compare against fixtures.
    public var fixtureObject: [String: String] {
        switch self {
        case let .push(cardId): return ["op": "push", "cardId": cardId]
        case .pop: return ["op": "pop"]
        case let .replace(cardId): return ["op": "replace", "cardId": cardId]
        case let .reset(cardId): return ["op": "reset", "cardId": cardId]
        case let .sheetPresent(cardId): return ["op": "sheet-present", "cardId": cardId]
        case .sheetDismiss: return ["op": "sheet-dismiss"]
        }
    }
}

public enum NavigationUrl {
    /// Parses a navigation action URL. Returns `nil` for any URL the navigation layer does not
    /// own (unknown scheme/host, or a missing/blank card id), so callers fall through without
    /// throwing. Matches `parseNavigationUrl` on web and the Kotlin parser on Android.
    public static func parse(_ url: String) -> NavigationOp? {
        guard let comps = URLComponents(string: url) else { return nil }
        guard let scheme = comps.scheme?.lowercased() else { return nil }
        let host = (comps.host ?? "").lowercased()

        func param(_ key: String) -> String? {
            guard let value = comps.queryItems?.first(where: { $0.name == key })?.value,
                  !value.isEmpty else { return nil }
            return value
        }

        switch scheme {
        case "nav":
            switch host {
            case "push": return param("id").map { .push(cardId: $0) }
            case "pop": return .pop
            case "replace": return param("id").map { .replace(cardId: $0) }
            case "reset": return param("id").map { .reset(cardId: $0) }
            default: return nil
            }
        case "sheet":
            switch host {
            case "present": return param("card_id").map { .sheetPresent(cardId: $0) }
            case "dismiss": return .sheetDismiss
            default: return nil
            }
        default:
            return nil
        }
    }
}
