import UIKit
import DivKit
import Engine

/// Renders the `bottom_sheet` custom block. The sheet itself is presented modally by the
/// presenter; the block returns a zero-size placeholder view. When `present_on_appear` is
/// true the sheet is presented as soon as the block is created.
///
/// App-target file (DivKit + UIKit); not part of the Linux SPM package.
final class BottomSheetBlockFactory: DivCustomBlockFactory {

    let presenter: BottomSheetPresenter

    init(presenter: BottomSheetPresenter) {
        self.presenter = presenter
    }

    func makeBlock(data: DivCustomBlockData) -> UIView {
        guard let cardId = data.customProps["card_id"] as? String,
              let card = CardRegistry.shared.card(id: cardId) else {
            return UIView(frame: .zero)
        }
        let presentOnAppear = (data.customProps["present_on_appear"] as? Bool) ?? false
        if presentOnAppear {
            presenter.present(cardId: cardId, card: card)
        }
        return UIView(frame: .zero)
    }
}
