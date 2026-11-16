package engine.navigation

/**
 * Routes parsed navigation actions to the stack and the sheet callbacks.
 *
 * Resolution goes through [CardRegistry]: a `push`/`replace`/`reset` whose id does not
 * resolve is a silent no-op that still reports `true` (no exception), matching the contract.
 * Returns `false` only for URLs the navigation layer does not own, so the caller can fall
 * through to [engine.router.EngineRouter].
 */
class NavigationRouter(
    val navigator: DivCardNavigator,
    private val registry: CardRegistry = CardRegistry
) {
    var onSheetPresent: ((String, kotlinx.serialization.json.JsonObject) -> Unit)? = null
    var onSheetDismiss: (() -> Unit)? = null

    /** Invoked after any stack-mutating op so the host can recompose. */
    var onChange: (() -> Unit)? = null

    fun handle(url: String): Boolean {
        val op = NavigationUrlParser.parse(url) ?: return false
        when (op) {
            is NavOp.Push -> registry.card(op.cardId)?.let {
                navigator.push(CardEntry(op.cardId, it))
                onChange?.invoke()
            }
            is NavOp.Pop -> {
                navigator.pop()
                onChange?.invoke()
            }
            is NavOp.Replace -> registry.card(op.cardId)?.let {
                navigator.replace(CardEntry(op.cardId, it))
                onChange?.invoke()
            }
            is NavOp.Reset -> registry.card(op.cardId)?.let {
                navigator.reset(CardEntry(op.cardId, it))
                onChange?.invoke()
            }
            is NavOp.SheetPresent -> registry.card(op.cardId)?.let {
                onSheetPresent?.invoke(op.cardId, it)
            }
            is NavOp.SheetDismiss -> onSheetDismiss?.invoke()
        }
        return true
    }
}
