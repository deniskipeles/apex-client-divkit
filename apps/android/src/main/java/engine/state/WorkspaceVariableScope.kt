package engine.state

/**
 * Structural rule for every DivKit variable the engine may write.
 *
 * A workspace owns its own vocabulary (`cart.total`, `shop.name`, `product.sku`, …); the
 * app must not ship a list of allowed prefixes, or a new workspace would need a rebuild.
 * The rule is therefore shape-based:
 *
 *  - the name is `<scope>.<name>` (exactly one dot-separated scope),
 *  - the scope is not [RESERVED], which keeps engine/system state out of reach of a card.
 *
 * Which scopes actually exist is decided by the workspace manifest, i.e. by the cards
 * themselves: the renderer only accepts writes to names the pushed card declares.
 */
object WorkspaceVariableScope {

    /** Scopes the runtime owns; a card may read them but never write them. */
    val RESERVED: Set<String> = setOf("engine", "system", "internal", "runtime")

    private val VALID_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*\\.[A-Za-z0-9_.]+$")

    fun scopeOf(name: String): String? =
        name.substringBefore('.', "").takeIf { it.isNotEmpty() && name.contains('.') }

    fun isReserved(name: String): Boolean = scopeOf(name) in RESERVED

    fun isValid(name: String): Boolean =
        name.length <= 128 && VALID_NAME.matches(name) && !isReserved(name)

    fun requireValid(name: String) {
        require(isValid(name)) {
            "Variable '$name' must be '<scope>.<name>' in a non-reserved scope; " +
                "${RESERVED.joinToString()} are runtime-owned"
        }
    }
}
