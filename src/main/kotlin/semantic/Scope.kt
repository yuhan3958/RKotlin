package me.rkt.semantic

class Scope(
    private val parent: Scope? = null
) {
    private val symbols = linkedMapOf<String, Symbol>()

    fun define(symbol: Symbol): Boolean {
        if (symbol.name in symbols) return false
        symbols[symbol.name] = symbol
        return true
    }

    fun resolve(name: String): Symbol? =
        symbols[name] ?: parent?.resolve(name)
}
