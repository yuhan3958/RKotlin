package me.rkt.semantic

sealed interface RType {
    val displayName: String
}

data object BoolType : RType {
    override val displayName: String = "Bool"
}

data object Int32Type : RType {
    override val displayName: String = "Int32"
}

data object UnitType : RType {
    override val displayName: String = "Unit"
}

data object StringType : RType {
    override val displayName: String = "String"
}

data object NullType : RType { override val displayName: String = "null" }

/** An unknown generic argument, including an unknown pointer pointee. */
data object WildcardType : RType { override val displayName: String = "*" }

data class NullableType(val underlying: RType) : RType {
    override val displayName: String = "${underlying.displayName}?"
}

enum class ManagedPointerKind(val displayName: String) {
    POINTER("Pointer"),
    BUFFER("BufferPointer")
}

data class ManagedPointerType(
    val pointee: RType,
    val writable: Boolean = true,
    val kind: ManagedPointerKind = ManagedPointerKind.POINTER
) : RType {
    override val displayName: String = "${kind.displayName}<${pointee.displayName}>"
}

data class ClassType(
    val name: String,
    val objectLike: Boolean = false,
    val typeArguments: List<RType> = emptyList(),
    val typeParameter: Boolean = false
) : RType {
    override val displayName: String = if (typeArguments.isEmpty()) {
        name
    } else {
        "$name<${typeArguments.joinToString(",") { it.displayName }}>"
    }
}
