package me.rkt.semantic

import me.rkt.ast.*

/** Binds value expressions and operators without constructor or block state. */
internal class SemanticExpressionBinder(
    private val context: SemanticContext,
    private val typeResolver: SemanticTypeResolver,
    private val rules: SemanticRules
) {
    private val calls = SemanticCallBinder(context, typeResolver, rules, ::bindExpression)
    private fun resolveClassType(type: TypeReference) = typeResolver.resolveClassType(type)
    private fun resolveType(type: TypeReference) = typeResolver.resolveType(type)

    private val diagnostics get() = context.diagnostics
    private val classes get() = context.classes
    private val nativeTypes get() = context.nativeTypes
    private fun unwrapNullable(type: RType) = typeResolver.unwrapNullable(type)

    fun bindExpression(
        expression: Expression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression {
        return when (expression) {
        is IntegerLiteral -> BoundIntLiteral(expression.value, expression.span)
        is BooleanLiteral -> BoundIntLiteral(if (expression.value) 1 else 0, expression.span, BoolType)
        is VoidLiteral -> BoundVoidLiteral(expression.span)
        is StringLiteral -> BoundStringLiteral(expression.value, expression.span)
        is NullLiteral -> BoundNullLiteral(expression.span)

        is NameExpression -> {
            if (expression.name == "super") {
                val base = owner?.baseClass ?: diagnostics.fail(expression.span, "super requires a superclass")
                return BoundThisExpression(ParameterSymbol("this", base.type), expression.span)
            }
            val symbol = scope.resolve(expression.name)
            if (symbol == null && owner != null) {
                val field = owner.fields[expression.name]
                if (field != null) {
                    val receiver = BoundThisExpression(
                        ParameterSymbol("this", owner.type),
                        expression.span
                    )
                    return rules.member(receiver, field, expression.span, owner)
                }
            }
            val resolved = symbol
                ?: diagnostics.fail(expression.span, "unknown name '${expression.name}'")
            if (resolved is ClassSymbol && resolved.type.objectLike) {
                return BoundObjectReference(resolved.type, expression.span)
            }
            if (resolved !is ValueSymbol) {
                diagnostics.fail(expression.span, "'${expression.name}' is not a value")
            }
            BoundNameExpression(resolved, expression.span)
        }

        is NewExpression -> {
            if (expression.type.nullable) diagnostics.fail(expression.span, "construct a non-null type")
            if (expression.type.arguments.isNotEmpty() &&
                (expression.type.name in nativeTypes ||
                    classes[expression.type.name]?.typeParameters.isNullOrEmpty())) {
                diagnostics.fail(expression.span, "type '${expression.type.name}' does not accept type arguments")
            }
            val standardType = nativeTypes[expression.type.name]
            if (standardType != null) {
                val arguments = expression.arguments.map { bindExpression(it, scope, owner) }
                if (arguments.isEmpty()) {
                    return when (standardType) {
                        Int32Type -> BoundIntLiteral(0, expression.span)
                        BoolType -> BoundIntLiteral(0, expression.span, BoolType)
                        StringType -> BoundStringLiteral("", expression.span)
                        UnitType -> BoundVoidLiteral(expression.span)
                        else -> error("unsupported standard constructor")
                    }
                }
                if (arguments.size != 1 || arguments.single().type != standardType || standardType == UnitType) {
                    diagnostics.fail(expression.span, "invalid ${expression.type.name} constructor arguments")
                }
                return arguments.single()
            }
            if (expression.type.name in setOf("Pointer", "BufferPointer")) {
                val managedType = resolveType(expression.type) as? ManagedPointerType
                    ?: diagnostics.fail(expression.type.span, "Pointer requires one type argument")
                if (managedType.pointee == WildcardType) {
                    diagnostics.fail(expression.span, "${managedType.kind.displayName}<*> cannot be constructed")
                }
                if (managedType.kind == ManagedPointerKind.BUFFER) {
                    if (expression.arguments.size != 1) {
                        diagnostics.fail(expression.span, "BufferPointer constructor expects one Int length")
                    }
                    val length = bindExpression(expression.arguments.single(), scope, owner)
                    if (length.type != Int32Type) {
                        diagnostics.fail(expression.span, "BufferPointer constructor expects one Int length")
                    }
                    return BoundBufferAllocationExpression(
                        length,
                        managedType,
                        expression.span
                    )
                }
                if (expression.arguments.size != 1) {
                    diagnostics.fail(expression.span, "Pointer constructor expects one initializer")
                }
                val initializer = bindExpression(expression.arguments.single(), scope, owner)
                if (!rules.isAssignable(managedType.pointee, initializer.type)) {
                    diagnostics.fail(expression.span, "Pointer initializer must be ${managedType.pointee.displayName}")
                }
                return BoundManagedPointerExpression(initializer, managedType, expression.span)
            }
            val classType = resolveClassType(expression.type)
            if (WildcardProjection.isView(classType)) {
                diagnostics.fail(expression.span, "wildcard types cannot be constructed; specify concrete type arguments")
            }
            if (classType.objectLike) diagnostics.fail(expression.span, "object declarations cannot be constructed")
            val arguments = expression.arguments.map { bindExpression(it, scope, owner) }
            val classSymbol = classes.getValue(classType.name)
            if (classSymbol.isInterface) diagnostics.fail(expression.span, "interfaces cannot be constructed")
            val constructor = classSymbol.constructor
            if (constructor == null && arguments.isNotEmpty()) {
                diagnostics.fail(expression.span, "class '${classSymbol.name}' has no constructor")
            }
            if (constructor != null) {
                val specialized = rules.specializeGenericFunction(constructor, classType)
                rules.checkArguments(classSymbol.name, arguments, specialized.parameters, expression.span)
                rules.checkAccess(constructor, owner, expression.span)
            }
            BoundNewExpression(classType, arguments, constructor, expression.span)
        }

        is AllocationExpression -> {
            val pointee = resolveType(expression.elementType)
            if (pointee == UnitType || pointee == WildcardType) {
                diagnostics.fail(expression.span, "alloc requires a concrete value type")
            }
            val length = bindExpression(expression.length, scope, owner)
            if (length.type != Int32Type) {
                diagnostics.fail(expression.length.span, "alloc length must be Int")
            }
            BoundBufferAllocationExpression(
                length,
                ManagedPointerType(pointee, kind = ManagedPointerKind.BUFFER),
                expression.span
            )
        }

        is IndexExpression -> {
            val receiver = bindExpression(expression.receiver, scope, owner)
            if (receiver.type is NullableType) {
                diagnostics.fail(expression.span, "nullable value must be resolved before indexing")
            }
            val arrayType = receiver.type as? ClassType
            if (arrayType?.name == "Array") {
                val index = bindExpression(expression.index, scope, owner)
                if (index.type != Int32Type) {
                    diagnostics.fail(expression.index.span, "array index must be Int")
                }
                val method = rules.specializeGenericFunction(
                    classes.getValue("Array").methods.getValue("get"),
                    receiver
                )
                rules.checkCallable(method, expression.span)
                return BoundCallExpression(
                    method,
                    listOf(index),
                    expression.span,
                    receiver = receiver
                )
            }
            val pointerType = unwrapNullable(receiver.type) as? ManagedPointerType
                ?: diagnostics.fail(expression.span, "indexing requires BufferPointer")
            if (pointerType.kind != ManagedPointerKind.BUFFER) {
                diagnostics.fail(expression.span, "indexing requires BufferPointer")
            }
            if (pointerType.pointee == WildcardType) {
                diagnostics.fail(expression.span, "cannot index BufferPointer<*>")
            }
            val index = bindExpression(expression.index, scope, owner)
            if (index.type != Int32Type) {
                diagnostics.fail(expression.index.span, "buffer index must be Int")
            }
            BoundBufferGetExpression(receiver, index, pointerType.pointee, expression.span)
        }

        is UnaryExpression -> {
            val operand = bindExpression(expression.operand, scope, owner)
            if (expression.operator != UnaryOperator.NOT || operand.type != BoolType) {
                diagnostics.fail(expression.span, "operator 'NOT' requires Bool")
            }
            BoundCallExpression(
                classes.getValue("Bool").methods.getValue("not"),
                emptyList(),
                expression.span,
                BoolType,
                receiver = operand
            )
        }

        is MemberAccessExpression -> {
            val receiver = bindExpression(expression.receiver, scope, owner)
            val receiverType = unwrapNullable(receiver.type)
            if (receiver.type is NullableType && !expression.safe) {
                diagnostics.fail(expression.span, "nullable receiver requires '?.'")
            }
            val classSymbol = receiverType as? ClassType
                ?: diagnostics.fail(expression.span, "member access requires an object value")
            val field = classes[classSymbol.name]?.fields?.get(expression.name)
                ?: diagnostics.fail(
                    expression.span,
                    "unknown field '${expression.name}' on '${classSymbol.name}'"
                )
            if (expression.safe && !typeResolver.isNullableCapable(field.type)) {
                diagnostics.fail(expression.span, "safe access currently requires a reference-valued field")
            }
            rules.member(receiver, field, expression.span, owner, expression.safe && receiver.type is NullableType).copy(
                direct = (expression.receiver as? NameExpression)?.name == "super")
        }

        is BinaryExpression -> bindBinary(expression, scope, owner)
        is CallExpression -> calls.bindCall(expression, scope, owner)
        is ElvisExpression -> {
            val nullable = bindExpression(expression.nullable, scope, owner)
            val nullableType = nullable.type as? NullableType
                ?: diagnostics.fail(expression.nullable.span, "left side of '?:' must be nullable")
            val fallback = bindExpression(expression.fallback, scope, owner)
            if (!rules.isAssignable(nullableType.underlying, fallback.type)) {
                diagnostics.fail(expression.fallback.span, "Elvis fallback must be ${nullableType.underlying.displayName}")
            }
            BoundElvisExpression(nullable, fallback, nullableType.underlying, expression.span)
        }
    }

    }

    private fun bindBinary(
        expression: BinaryExpression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression {
        val left = bindExpression(expression.left, scope, owner)
        val right = bindExpression(expression.right, scope, owner)
        if ((left.type == NullType || right.type == NullType) &&
            expression.operator in setOf(BinaryOperator.EQUALS, BinaryOperator.NOT_EQUALS)) {
            val other = if (left.type == NullType) right.type else left.type
            if (other !is NullableType && other != NullType) diagnostics.fail(expression.span, "null comparison requires a nullable value")
            return BoundBinaryExpression(left,
                if (expression.operator == BinaryOperator.EQUALS) BoundBinaryOperator.EQ_I32 else BoundBinaryOperator.NE_I32,
                right, expression.span, BoolType)
        }
        val leftType = left.type
        if (expression.operator in setOf(BinaryOperator.ADD, BinaryOperator.SUB) &&
            leftType is ManagedPointerType &&
            leftType.kind == ManagedPointerKind.BUFFER) {
            if (right.type != Int32Type) {
                diagnostics.fail(expression.span, "operator 'ADD' has incompatible operands")
            }
            return BoundPointerAddExpression(
                left,
                right,
                ManagedPointerType(leftType.pointee, leftType.writable, leftType.kind),
                expression.span,
                if (expression.operator == BinaryOperator.ADD) 1 else -1
            )
        }
        val name = when (expression.operator) {
            BinaryOperator.ADD -> "plus"
            BinaryOperator.SUB -> "minus"
            BinaryOperator.MUL -> "times"
            BinaryOperator.DIV -> "div"
            BinaryOperator.EQUALS -> "equals"
            BinaryOperator.NOT_EQUALS -> "notEquals"
            BinaryOperator.LESS -> "lessThan"
            BinaryOperator.LESS_EQUALS -> "lessOrEqual"
            BinaryOperator.GREATER -> "greaterThan"
            BinaryOperator.GREATER_EQUALS -> "greaterOrEqual"
            BinaryOperator.AND -> "and"
            BinaryOperator.OR -> "or"
            BinaryOperator.XOR -> "xor"
        }
        val method = (left.type as? ClassType)?.let { classes[it.name]?.methods?.get(name) }
            ?: classes[left.type.displayName]?.methods?.get(name)
            ?: diagnostics.fail(expression.span, "operator '${expression.operator}' is not defined on ${left.type.displayName}")
        rules.checkAccess(method, owner, expression.span)
        if (method.parameters.size != 1 || !rules.isAssignable(method.parameters.single().type, right.type)) {
            diagnostics.fail(expression.span, "operator '$name' has incompatible operands")
        }
        return BoundCallExpression(method, listOf(right), expression.span, receiver = left)
    }

}
