package me.rkt.semantic

import me.rkt.ast.*

/** Resolves calls, overloads, and built-in memory operations. */
internal class SemanticCallBinder(
    private val context: SemanticContext,
    private val typeResolver: SemanticTypeResolver,
    private val rules: SemanticRules,
    private val bindExpression: (Expression, Scope, ClassSymbol?) -> BoundExpression
) {
    private val overloads get() = context.overloads

    private val diagnostics get() = context.diagnostics
    private val classes get() = context.classes
    private val nativeTypes get() = context.nativeTypes
    private fun unwrapNullable(type: RType) = typeResolver.unwrapNullable(type)

    fun bindCall(
        expression: CallExpression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression {
        val arguments = expression.arguments.map { bindExpression(it, scope, owner) }
        if (expression.target is NameExpression && expression.target.name == "arrayOf") {
            if (arguments.isEmpty()) {
                diagnostics.fail(expression.span, "arrayOf requires at least one argument")
            }
            val elementType = arguments.first().type
            if (elementType == UnitType || elementType == NullType || elementType is NullableType) {
                diagnostics.fail(expression.span, "arrayOf requires non-null value elements")
            }
            arguments.drop(1).forEach { argument ->
                if (!rules.isAssignable(elementType, argument.type) || !rules.isAssignable(argument.type, elementType)) {
                    diagnostics.fail(expression.span, "arrayOf arguments must have the same type")
                }
            }
            return BoundArrayLiteral(
                arguments,
                elementType,
                ClassType("Array", typeArguments = listOf(elementType)),
                expression.span
            )
        }
        var receiver: BoundExpression? = null
        var symbol: FunctionSymbol
        when (val target = expression.target) {
            is NameExpression -> {
                run {
                    val resolved = scope.resolve(target.name)
                    if (resolved is FunctionSymbol) {
                        val matches = overloads[target.name].orEmpty().filter { candidate ->
                            (candidate.visibility != Visibility.PRIVATE || candidate.declarationSpan?.source == target.span.source) &&
                                candidate.parameters.size == arguments.size &&
                                candidate.parameters.zip(arguments).all { (p, a) -> rules.isAssignable(p.type, a.type) }
                        }
                        val best = matches.filter { candidate -> matches.none { other ->
                            other !== candidate && other.parameters.zip(candidate.parameters).all { (a, b) -> rules.isAssignable(b.type, a.type) } &&
                                other.parameters.zip(candidate.parameters).any { (a, b) -> a.type != b.type }
                        } }
                        if (best.size != 1) diagnostics.fail(target.span, "no unique accessible overload for '${target.name}'")
                        symbol = best.single()
                    } else if (resolved == null && owner != null) {
                        symbol = owner.methods[target.name]
                            ?: diagnostics.fail(target.span, "unknown function '${target.name}'")
                        receiver = BoundThisExpression(
                            ParameterSymbol("this", nativeTypes[owner.name] ?: owner.type),
                            target.span
                        )
                    } else {
                        diagnostics.fail(target.span, "unknown function '${target.name}'")
                    }
                }
            }
            is MemberAccessExpression -> {
                receiver = bindExpression(target.receiver, scope, owner)
                if (receiver.type is NullableType && unwrapNullable(receiver.type) is ManagedPointerType) {
                    diagnostics.fail(target.span, "nullable receiver must be resolved before calling a memory method")
                }
                val receiverClassType = unwrapNullable(receiver.type) as? ClassType
                val receiverIsFreedOverride = receiverClassType
                    ?.let { classes[it.name]?.methods?.get("isFreed") }
                    ?.overriding == true
                if (target.name == "isFreed" &&
                    receiver.type !is NullableType &&
                    !receiverIsFreedOverride
                ) {
                    if (arguments.isNotEmpty()) {
                        diagnostics.fail(expression.span, "isFreed expects no arguments")
                    }
                    return BoundTypeIsFreedExpression(receiver, expression.span)
                }
                if (receiver.type is ManagedPointerType && target.name == "address") {
                    if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "address expects no arguments")
                    val value = receiver as? BoundNameExpression
                        ?: diagnostics.fail(target.span, "address requires a local variable")
                    if (value.symbol !is VariableSymbol && value.symbol !is ParameterSymbol) {
                        diagnostics.fail(target.span, "address requires a local variable")
                    }
                    return BoundAddressExpression(value, expression.span)
                }
                val pointerType = unwrapNullable(receiver.type) as? ManagedPointerType
                if (pointerType != null) {
                    val pointerClass = when (pointerType.kind) {
                        ManagedPointerKind.POINTER -> "Pointer"
                        ManagedPointerKind.BUFFER -> "BufferPointer"
                    }
                    if (classes[pointerClass]?.methods?.containsKey(target.name) != true) {
                        diagnostics.fail(target.span, "unknown Pointer method '${target.name}'")
                    }
                    return when (target.name) {
                        "add", "plus" -> {
                            if (pointerType.kind != ManagedPointerKind.BUFFER) {
                                diagnostics.fail(expression.span, "pointer arithmetic requires BufferPointer")
                            }
                            if (arguments.size != 1 || arguments[0].type != Int32Type) {
                                diagnostics.fail(expression.span, "operator 'plus' has incompatible operands")
                            }
                            BoundPointerAddExpression(
                                receiver,
                                arguments.single(),
                                ManagedPointerType(
                                    pointerType.pointee,
                                    pointerType.writable,
                                    pointerType.kind
                                ),
                                expression.span
                            )
                        }
                        "get" -> {
                            if (pointerType.kind != ManagedPointerKind.BUFFER) {
                                diagnostics.fail(expression.span, "get requires BufferPointer")
                            }
                            if (pointerType.pointee == WildcardType) {
                                diagnostics.fail(expression.span, "cannot get from BufferPointer<*>")
                            }
                            if (arguments.size != 1 || arguments[0].type != Int32Type) {
                                diagnostics.fail(expression.span, "get expects one Int index")
                            }
                            BoundBufferGetExpression(receiver, arguments.single(), pointerType.pointee, expression.span)
                        }
                        "set" -> {
                            if (pointerType.kind != ManagedPointerKind.BUFFER) {
                                diagnostics.fail(expression.span, "set requires BufferPointer")
                            }
                            if (!pointerType.writable) {
                                diagnostics.fail(expression.span, "cannot write through the address of a val")
                            }
                            if (pointerType.pointee == WildcardType) {
                                diagnostics.fail(expression.span, "cannot set BufferPointer<*>")
                            }
                            if (arguments.size != 2 ||
                                arguments[0].type != Int32Type ||
                                !rules.isAssignable(pointerType.pointee, arguments[1].type)) {
                                diagnostics.fail(expression.span, "set expects an Int index and a ${pointerType.pointee.displayName} value")
                            }
                            BoundBufferSetExpression(receiver, arguments[0], arguments[1], expression.span)
                        }
                        "length" -> {
                            if (pointerType.kind != ManagedPointerKind.BUFFER) {
                                diagnostics.fail(expression.span, "length requires BufferPointer")
                            }
                            if (arguments.isNotEmpty()) {
                                diagnostics.fail(expression.span, "length expects no arguments")
                            }
                            BoundBufferLengthExpression(receiver, expression.span)
                        }
                        "read" -> {
                            if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "read expects no arguments")
                            if (pointerType.pointee == WildcardType) {
                                diagnostics.fail(expression.span, "cannot read from Pointer<*>")
                            }
                            BoundDereferenceExpression(receiver, expression.span, pointerType.pointee)
                        }
                        "write" -> {
                            if (!pointerType.writable) diagnostics.fail(expression.span, "cannot write through the address of a val")
                            if (pointerType.pointee == WildcardType) {
                                diagnostics.fail(expression.span, "cannot write to Pointer<*>")
                            }
                            if (arguments.size != 1 || !rules.isAssignable(pointerType.pointee, arguments[0].type)) {
                                diagnostics.fail(expression.span, "write expects one ${pointerType.pointee.displayName} argument")
                            }
                            BoundPointerWriteExpression(receiver, arguments[0], expression.span)
                        }
                        "free" -> {
                            if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "free expects no arguments")
                            BoundFreeExpression(receiver, expression.span)
                        }
                        "isFreed" -> {
                            if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "isFreed expects no arguments")
                            BoundCallExpression(classes.getValue(pointerClass).methods.getValue("isFreed"),
                                emptyList(), expression.span, receiver = receiver)
                        }
                        "toString" -> {
                            if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "toString expects no arguments")
                            BoundCallExpression(classes.getValue(pointerClass).methods.getValue("toString"),
                                emptyList(), expression.span, receiver = receiver)
                        }
                        else -> diagnostics.fail(target.span, "unknown Pointer method '${target.name}'")
                    }
                }
                if ((receiver.type in setOf(Int32Type, BoolType, StringType) ||
                        (receiver.type is ClassType && (receiver.type as ClassType).name != "Array")) &&
                    target.name == "address") {
                    if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "address expects no arguments")
                    val value = receiver as? BoundNameExpression
                        ?: diagnostics.fail(target.span, "address requires a local variable")
                    if (value.symbol !is VariableSymbol && value.symbol !is ParameterSymbol) {
                        diagnostics.fail(target.span, "address requires a local variable")
                    }
                    return BoundAddressExpression(value, expression.span)
                }
                if (receiver.type is NullableType && !target.safe) {
                    diagnostics.fail(target.span, "nullable receiver requires '?.'")
                }
                val classType = (unwrapNullable(receiver.type) as? ClassType)
                    ?: classes[unwrapNullable(receiver.type).displayName]?.type
                    ?: diagnostics.fail(target.span, "method receiver must be an object value")
                symbol = classes[classType.name]?.methods?.get(target.name)
                    ?: diagnostics.fail(
                        target.span,
                        "unknown method '${target.name}' on '${classType.name}'"
                    )
                rules.checkAccess(symbol, owner, target.span)
            }
            else -> diagnostics.fail(expression.span, "call target must be a function or method")
        }

        symbol = rules.specializeGenericFunction(symbol, receiver)
        rules.checkCallable(symbol, expression.span)
        if (symbol.owner == null && symbol.visibility == Visibility.PRIVATE &&
            symbol.declarationSpan?.source != expression.span.source) {
            diagnostics.fail(expression.span, "function '${symbol.name}' is private")
        }
        rules.checkAccess(symbol, owner, expression.span)
        if (arguments.size != symbol.parameters.size) {
            diagnostics.fail(
                expression.span,
                "function '${symbol.name}' expects ${symbol.parameters.size} arguments, " +
                    "found ${arguments.size}"
            )
        }
        arguments.zip(symbol.parameters).forEachIndexed { index, (argument, parameter) ->
            if (!rules.isAssignable(parameter.type, argument.type)) {
                diagnostics.error(
                    expression.arguments[index].span,
                    "argument ${index + 1}: expected ${parameter.type.displayName}, " +
                        "found ${argument.type.displayName}"
                )
            }
        }
        val safe = (expression.target as? MemberAccessExpression)?.safe == true && receiver?.type is NullableType
        if (safe && symbol.returnType != UnitType && !typeResolver.isNullableCapable(symbol.returnType)) {
            diagnostics.fail(expression.span, "safe call currently requires a reference return type")
        }
        val resultType = if (safe && symbol.returnType != UnitType) {
            rules.nullable(symbol.returnType)
        } else {
            symbol.returnType
        }
        return BoundCallExpression(
            symbol,
            arguments,
            expression.span,
            resultType,
            receiver,
            safe,
            ((expression.target as? MemberAccessExpression)?.receiver as? NameExpression)?.name == "super"
        )
    }

}
