package me.rkt.semantic

/** A star hides one invariant type argument; it never chooses a storage type. */
internal object WildcardProjection {
    fun isView(type: ClassType): Boolean = WildcardType in type.typeArguments

    fun accepts(expected: ClassType, actual: ClassType): Boolean =
        expected.name == actual.name && expected.typeArguments.size == actual.typeArguments.size &&
            expected.typeArguments.zip(actual.typeArguments).all { (wanted, given) ->
                wanted == WildcardType || wanted == given
            }

    fun unreadable(type: RType): Boolean = type == WildcardType ||
        (type is NullableType && unreadable(type.underlying))

    private fun depends(type: RType, hidden: Set<String>): Boolean = when (type) {
        is ClassType -> (type.typeParameter && type.name in hidden) || type.typeArguments.any { depends(it, hidden) }
        is ManagedPointerType -> depends(type.pointee, hidden)
        is NullableType -> depends(type.underlying, hidden)
        else -> false
    }

    fun output(type: RType, arguments: Map<String, RType>): RType {
        val hidden = arguments.filterValues { it == WildcardType }.keys
        fun project(value: RType): RType = when (value) {
            is ClassType -> if (value.typeParameter) TypeSubstitution.apply(value, arguments)
                else value.copy(typeArguments = value.typeArguments.map {
                    if (depends(it, hidden)) WildcardType else TypeSubstitution.apply(it, arguments)
                })
            is ManagedPointerType -> value.copy(pointee =
                if (depends(value.pointee, hidden)) WildcardType else TypeSubstitution.apply(value.pointee, arguments))
            is NullableType -> NullableType(project(value.underlying))
            else -> value
        }
        return project(type)
    }

    fun function(function: FunctionSymbol, arguments: Map<String, RType>): FunctionSymbol {
        val hidden = arguments.filterValues { it == WildcardType }.keys
        return function.copy(
            parameters = function.parameters.map { it.copy(type = TypeSubstitution.apply(it.type, arguments)) },
            returnType = output(function.returnType, arguments),
            unavailableParameters = function.parameters.indices.filter {
                depends(function.parameters[it].type, hidden)
            }.toSet())
    }
}
