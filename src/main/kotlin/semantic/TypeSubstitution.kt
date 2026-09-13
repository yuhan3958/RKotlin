package me.rkt.semantic

/** Substitutes types without changing the identity of a generic declaration. */
internal object TypeSubstitution {
    fun apply(type: RType, arguments: Map<String, RType>): RType = when (type) {
        is ClassType -> if (type.typeParameter) arguments[type.name] ?: type
            else type.copy(typeArguments = type.typeArguments.map { apply(it, arguments) })
        is ManagedPointerType -> type.copy(pointee = apply(type.pointee, arguments))
        is NullableType -> NullableType(apply(type.underlying, arguments))
        else -> type
    }

    fun viewAs(type: ClassType, owner: ClassSymbol, classes: Map<String, ClassSymbol>): ClassType? {
        val visited = mutableSetOf<ClassType>()
        fun visit(current: ClassType): ClassType? {
            if (!visited.add(current)) return null
            if (current.name == owner.name) return current
            val symbol = classes[current.name] ?: return null
            val arguments = symbol.typeParameters.zip(current.typeArguments).toMap()
            val parents = symbol.interfaceTypes + listOfNotNull(symbol.baseClass?.type)
            return parents.firstNotNullOfOrNull { visit(apply(it, arguments) as ClassType) }
        }
        return visit(type)
    }
}
