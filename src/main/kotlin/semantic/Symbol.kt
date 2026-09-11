package me.rkt.semantic

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

data class VariableSymbol(
    override val name: String,
    override val type: RType,
    override val mutable: Boolean
) : ValueSymbol

data class FieldSymbol(
    override val name: String,
    override val type: RType,
    override val mutable: Boolean
) : ValueSymbol

data class ClassSymbol(
    override val name: String,
    val type: ClassType,
    val fields: LinkedHashMap<String, FieldSymbol> = linkedMapOf(),
    val methods: LinkedHashMap<String, FunctionSymbol> = linkedMapOf()
) : Symbol

data class FunctionSymbol(
    override val name: String,
    val parameters: List<ParameterSymbol>,
    val returnType: RType,
    val builtinTarget: String? = null,
    val owner: ClassSymbol? = null
) : Symbol

val FunctionSymbol.generatedName: String
    get() = owner?.let { "${it.name}_$name" } ?: name
