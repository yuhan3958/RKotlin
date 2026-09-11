package me.rkt.semantic

sealed interface RType {
    val displayName: String
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
