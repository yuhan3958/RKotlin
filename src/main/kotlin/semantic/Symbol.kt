package me.rkt.semantic

import me.rkt.ast.Visibility
import me.rkt.source.SourceSpan

sealed interface Symbol {
    val name: String
}

sealed interface ValueSymbol : Symbol {
    val type: RType
    val mutable: Boolean
}

data class ParameterSymbol(
    override val name: String,
    override val type: RType,
    override val mutable: Boolean = false
) : ValueSymbol

class VariableSymbol(
    override val name: String,
    override val type: RType,
    override val mutable: Boolean
) : ValueSymbol

data class FieldSymbol(
    override val name: String,
    override val type: RType,
    override val mutable: Boolean,
    val visibility: Visibility = Visibility.PUBLIC,
    val ownerName: String = ""
) : ValueSymbol

class ClassSymbol(
    override val name: String,
    val type: ClassType,
    val fields: LinkedHashMap<String, FieldSymbol> = linkedMapOf(),
    val methods: LinkedHashMap<String, FunctionSymbol> = linkedMapOf(),
    var constructor: FunctionSymbol? = null,
    val visibility: Visibility = Visibility.PUBLIC,
    val declarationSpan: SourceSpan? = null,
    var baseClass: ClassSymbol? = null
) : Symbol

data class FunctionSymbol(
    override val name: String,
    val parameters: List<ParameterSymbol>,
    val returnType: RType,
    val builtinTarget: String? = null,
    val owner: ClassSymbol? = null,
    val visibility: Visibility = Visibility.PUBLIC,
    val declarationSpan: SourceSpan? = null,
    val synthetic: Boolean = false,
    val overriding: Boolean = false
) : Symbol

val FunctionSymbol.generatedName: String
    get() = owner?.let { "${it.name}_$name" }
        ?: if (parameters.isEmpty()) name else name + "_" + parameters.joinToString("_") { it.type.displayName }
