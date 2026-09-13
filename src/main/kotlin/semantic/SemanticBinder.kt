package me.rkt.semantic

import me.rkt.ast.*
import me.rkt.source.SourceSpan

class SemanticBinder(
    private val context: SemanticContext,
    private val typeResolver: SemanticTypeResolver
) {
    private val diagnostics get() = context.diagnostics
    private val globalScope get() = context.globalScope
    private val classes get() = context.classes
    private val functions get() = context.functions
    private val constructors get() = context.constructors
    private val nativeTypes get() = context.nativeTypes
    private val overloads get() = context.overloads
    private fun resolveClassType(type: TypeReference) = typeResolver.resolveClassType(type)
    private fun resolveType(type: TypeReference) = typeResolver.resolveType(type)
    private fun unwrapNullable(type: RType) = typeResolver.unwrapNullable(type)
    private fun isNullableCapable(type: RType) = typeResolver.isNullableCapable(type)

    private var constructing: ClassSymbol? = null
    private val initializedFields = mutableSetOf<String>()
    private var blockDepth = 0

    fun bind(module: AstModule): List<BoundFunction> = module.declarations
        .flatMap { declaration ->
            when (declaration) {
                is FunctionDeclaration -> declaration.body?.let { listOf(bindFunction(declaration)) } ?: emptyList()
                is ClassDeclaration -> declaration.members.mapNotNull { member ->
                    when (member) {
                        is FunctionDeclaration -> member.body?.let { bindFunction(member) }
                        is ConstructorDeclaration -> bindConstructor(member)
                        is FieldDeclaration -> null
                    }
                }
                is ObjectDeclaration -> declaration.members
                    .filterIsInstance<FunctionDeclaration>()
                    .filter { it.body != null }
                    .map(::bindFunction)
                is ImportDeclaration -> emptyList()
                is NativeTypeDeclaration -> emptyList()
            }
        }

    private fun isSubclass(actual: ClassSymbol?, expected: ClassSymbol): Boolean {
        var current = actual
        while (current != null) {
            if (current === expected) return true
            current = current.baseClass
        }
        return false
    }

    private fun checkAccess(symbol: FunctionSymbol, owner: ClassSymbol?, span: SourceSpan) {
        val declaring = symbol.owner ?: return
        val allowed = when (symbol.visibility) {
            Visibility.PUBLIC -> true
            Visibility.PRIVATE -> owner === declaring
            Visibility.PROTECTED -> isSubclass(owner, declaring)
        }
        if (!allowed) diagnostics.fail(span, "'${symbol.name}' is ${symbol.visibility.name.lowercase()} in '${declaring.name}'")
    }

    private fun member(receiver: BoundExpression, field: FieldSymbol, span: SourceSpan, owner: ClassSymbol?, safe: Boolean = false): BoundMemberExpression {
        val receiverClass = classes.getValue((unwrapNullable(receiver.type) as ClassType).name)
        val suffix = field.name.replaceFirstChar { it.uppercaseChar() }
        val getter = receiverClass.methods.getValue("get$suffix")
        checkAccess(getter, owner, span)
        return BoundMemberExpression(receiver, field, span, if (safe) nullable(field.type) else field.type,
            safe, getter, receiverClass.methods["set$suffix"])
    }

    private fun nullable(type: RType): RType = if (type is NullableType) type else NullableType(type)
    private fun bindFunction(declaration: FunctionDeclaration): BoundFunction {
        val function = functions.getValue(declaration)
        val scope = Scope(globalScope)
        val owner = function.owner
        context.typeParameters = genericTypeParameters(owner)

        if (owner != null) {
            scope.define(ParameterSymbol("this", nativeTypes[owner.name] ?: owner.type))
        }
        for (parameter in function.parameters) {
            if (!scope.define(parameter)) {
                diagnostics.error(
                    declaration.span,
                    "duplicate parameter '${parameter.name}'"
                )
            }
        }

        val body = bindBlock(declaration.body ?: error("native function has no body"), scope, function.returnType, owner)
        if (function.returnType != UnitType && !alwaysReturns(body)) {
            diagnostics.fail(declaration.span, "function '${function.name}' must return a value on every path")
        }
        context.typeParameters = emptySet()
        return BoundFunction(function, body, function.parameters, owner?.type)
    }

    private fun alwaysReturns(statement: BoundStatement): Boolean = when (statement) {
        is BoundReturnStatement -> true
        is BoundBlockStatement -> statement.statements.any(::alwaysReturns)
        is BoundIfStatement -> alwaysReturns(statement.thenBranch) && statement.elseBranch?.let(::alwaysReturns) == true
        else -> false
    }

    private fun bindConstructor(declaration: ConstructorDeclaration): BoundFunction {
        val function = constructors.getValue(declaration)
        val owner = function.owner ?: error("constructor without class")
        context.typeParameters = genericTypeParameters(owner)
        val scope = Scope(globalScope)
        scope.define(ParameterSymbol("this", owner.type))
        function.parameters.forEach { parameter ->
            if (!scope.define(parameter)) {
                diagnostics.error(declaration.span, "duplicate parameter '${parameter.name}'")
            }
        }
        constructing = owner
        initializedFields.clear()
        val first = (declaration.body.statements.firstOrNull() as? ExpressionStatement)?.expression as? CallExpression
        val explicitSuper = (first?.target as? NameExpression)?.name == "super"
        val prefix = mutableListOf<BoundStatement>()
        owner.baseClass?.let { base ->
            val constructor = base.constructor ?: error("missing superclass constructor")
            checkAccess(constructor, owner, declaration.span)
            val arguments = if (explicitSuper) first.arguments.map { bindExpression(it, scope, owner) } else emptyList()
            checkArguments(base.name, arguments, constructor.parameters, declaration.span)
            prefix += BoundExpressionStatement(BoundCallExpression(constructor, arguments, declaration.span,
                receiver = BoundThisExpression(ParameterSymbol("this", base.type), declaration.span), direct = true), declaration.span)
        }
        if (explicitSuper && owner.baseClass == null) diagnostics.fail(first.span, "super requires a superclass")
        val statements = if (explicitSuper) declaration.body.statements.drop(1) else declaration.body.statements
        val body = bindBlock(declaration.body.copy(statements = statements), scope, UnitType, owner)
        for (field in owner.fields.values.filter { it.ownerName == owner.name }) {
            if ((field.type is ClassType || field.type is ManagedPointerType) && field.name !in initializedFields) {
                diagnostics.fail(declaration.span, "constructor must initialize non-null field '${field.name}'")
            }
        }
        constructing = null
        initializedFields.clear()
        context.typeParameters = emptySet()
        return BoundFunction(function, body.copy(statements = prefix + body.statements), function.parameters, owner.type)
    }

    private fun genericTypeParameters(owner: ClassSymbol?): Set<String> =
        if (owner?.name in setOf("Pointer", "BufferPointer", "Array")) setOf("T") else emptySet()

    private fun bindBlock(
        block: BlockStatement,
        scope: Scope,
        expectedReturnType: RType,
        owner: ClassSymbol? = null
    ): BoundBlockStatement {
        blockDepth++
        val statements = block.statements.map {
            bindStatement(it, scope, expectedReturnType, owner)
        }
        blockDepth--
        return BoundBlockStatement(statements, block.span)
    }

    private fun bindStatement(
        statement: Statement,
        scope: Scope,
        expectedReturnType: RType,
        owner: ClassSymbol?
    ): BoundStatement = when (statement) {
        is ReturnStatement -> {
            val expression = bindExpression(statement.expression, scope, owner)
            if (!isAssignable(expectedReturnType, expression.type)) {
                diagnostics.error(
                    statement.span,
                    "return type mismatch: expected ${expectedReturnType.displayName}, " +
                        "found ${expression.type.displayName}"
                )
            }
            BoundReturnStatement(expression, statement.span)
        }

        is VariableDeclarationStatement -> {
            val initializer = bindExpression(statement.initializer, scope, owner)
            val type = statement.type?.let(::resolveType) ?: initializer.type
            if (type == NullType || type == UnitType) {
                diagnostics.fail(statement.span, "variable requires a concrete value type")
            }
            if (!isAssignable(type, initializer.type)) {
                diagnostics.error(
                    statement.initializer.span,
                    "initializer type mismatch: expected ${type.displayName}, " +
                        "found ${initializer.type.displayName}"
                )
            }
            val symbol = VariableSymbol(statement.name, type, statement.mutable)
            if (!scope.define(symbol)) {
                diagnostics.error(statement.span, "duplicate variable '${statement.name}'")
            }
            BoundVariableDeclarationStatement(symbol, initializer, statement.span)
        }

        is AssignmentStatement -> {
            if (statement.target is IndexExpression) {
                val target = statement.target
                val receiver = bindExpression(target.receiver, scope, owner)
                val arrayType = unwrapNullable(receiver.type) as? ClassType
                if (arrayType?.name == "Array") {
                    val index = bindExpression(target.index, scope, owner)
                    if (index.type != Int32Type) {
                        diagnostics.fail(target.index.span, "array index must be Int")
                    }
                    val value = bindExpression(statement.expression, scope, owner)
                    val method = classes.getValue("Array").methods.getValue("set")
                    val specialized = specializeGenericFunction(method, receiver)
                    if (!isAssignable(specialized.parameters[0].type, index.type) ||
                        !isAssignable(specialized.parameters[1].type, value.type)
                    ) {
                        diagnostics.fail(statement.span, "array assignment has incompatible types")
                    }
                    return BoundExpressionStatement(
                        BoundCallExpression(
                            specialized,
                            listOf(index, value),
                            statement.span,
                            receiver = receiver
                        ),
                        statement.span
                    )
                }
            }
            val target = bindExpression(statement.target, scope, owner)
            if (target is BoundMemberExpression) {
                if (target.safe) diagnostics.fail(statement.span, "safe access is not an assignment target")
                target.setter?.let { checkAccess(it, owner, statement.span) }
            }
            val mutable = when (target) {
                is BoundNameExpression -> target.symbol.mutable
                is BoundMemberExpression -> target.field.mutable || (
                    constructing === owner && owner != null && blockDepth == 1 &&
                        target.field.ownerName == owner.name &&
                        (target.receiver is BoundThisExpression ||
                            (target.receiver as? BoundNameExpression)?.symbol?.name == "this") &&
                        initializedFields.add(target.field.name))
                is BoundDereferenceExpression -> true
                is BoundBufferGetExpression -> {
                    val pointerType = target.pointer.type as? ManagedPointerType
                        ?: diagnostics.fail(statement.target.span, "invalid buffer index target")
                    pointerType.writable
                }
                else -> diagnostics.fail(statement.target.span, "assignment target is not writable")
            }
            if (!mutable) {
                diagnostics.fail(statement.target.span, "cannot assign to immutable value")
            }
            val expression = bindExpression(statement.expression, scope, owner)
            if (!isAssignable(target.type, expression.type)) {
                diagnostics.error(
                    statement.expression.span,
                    "assignment type mismatch: expected ${target.type.displayName}, " +
                        "found ${expression.type.displayName}"
                )
            }
            if (target is BoundMemberExpression && constructing === owner && owner != null && blockDepth == 1 &&
                target.field.ownerName == owner.name && (target.receiver is BoundThisExpression ||
                    (target.receiver as? BoundNameExpression)?.symbol?.name == "this")) {
                initializedFields.add(target.field.name)
            }
            BoundAssignmentStatement(target, expression, statement.span)
        }

        is ExpressionStatement -> BoundExpressionStatement(
            bindExpression(statement.expression, scope, owner),
            statement.span
        )

        is IfStatement -> {
            val condition = bindCondition(statement.condition, scope, owner)
            val thenBranch = bindBlock(
                statement.thenBranch,
                Scope(scope),
                expectedReturnType,
                owner
            )
            val elseBranch = statement.elseBranch?.let {
                bindBlock(it, Scope(scope), expectedReturnType, owner)
            }
            BoundIfStatement(condition, thenBranch, elseBranch, statement.span)
        }

        is WhileStatement -> {
            val condition = bindCondition(statement.condition, scope, owner)
            val body = bindBlock(statement.body, Scope(scope), expectedReturnType, owner)
            BoundWhileStatement(condition, body, statement.span)
        }

        is ForStatement -> {
            if (statement.iterable != null) {
                val iterable = bindExpression(statement.iterable, scope, owner)
                val iterableType = unwrapNullable(iterable.type)
                val elementType: RType
                val limit: BoundExpression
                val elementAccess: BoundExpression
                when (iterableType) {
                    is ClassType -> {
                        if (iterableType.name != "Array" || iterableType.typeArguments.size != 1) {
                            diagnostics.fail(statement.span, "for-in requires Array or BufferPointer")
                        }
                        elementType = iterableType.typeArguments.single()
                        val sizeMethod = specializeGenericFunction(
                            classes.getValue("Array").methods.getValue("size"),
                            iterable
                        )
                        limit = BoundCallExpression(sizeMethod, emptyList(), statement.span, receiver = iterable)
                        val index = ParameterSymbol("__index", Int32Type)
                        elementAccess = BoundCallExpression(
                            specializeGenericFunction(
                                classes.getValue("Array").methods.getValue("get"),
                                iterable
                            ),
                            listOf(BoundNameExpression(index, statement.span)),
                            statement.span,
                            receiver = iterable
                        )
                    }
                    is ManagedPointerType -> {
                        if (iterableType.kind != ManagedPointerKind.BUFFER ||
                            iterableType.pointee == PointerWildcardType
                        ) {
                            diagnostics.fail(statement.span, "for-in requires Array or BufferPointer")
                        }
                        elementType = iterableType.pointee
                        limit = BoundBufferLengthExpression(iterable, statement.span)
                        val index = ParameterSymbol("__index", Int32Type)
                        elementAccess = BoundBufferGetExpression(
                            iterable,
                            BoundNameExpression(index, statement.span),
                            elementType,
                            statement.span
                        )
                    }
                    else -> diagnostics.fail(statement.span, "for-in requires Array or BufferPointer")
                }
                val indexSymbol = VariableSymbol("__index", Int32Type, mutable = true)
                val loopScope = Scope(scope)
                loopScope.define(indexSymbol)
                val symbol = VariableSymbol(statement.name, elementType, mutable = false)
                if (!loopScope.define(symbol)) {
                    diagnostics.error(statement.span, "duplicate variable '${statement.name}'")
                }
                val indexedAccess = when (elementAccess) {
                    is BoundCallExpression -> elementAccess.copy(
                        arguments = listOf(BoundNameExpression(indexSymbol, statement.span))
                    )
                    is BoundBufferGetExpression -> elementAccess.copy(
                        index = BoundNameExpression(indexSymbol, statement.span)
                    )
                    else -> elementAccess
                }
                val body = bindBlock(statement.body, loopScope, expectedReturnType, owner)
                return BoundForStatement(
                    symbol,
                    BoundIntLiteral(0, statement.span),
                    limit,
                    body,
                    statement.span,
                    iterable,
                    indexSymbol,
                    indexedAccess
                )
            }
            val start = bindExpression(statement.start, scope, owner)
            val end = bindExpression(statement.end, scope, owner)
            requireInt32(start, statement.start.span)
            requireInt32(end, statement.end.span)
            if (start.type != Int32Type || end.type != Int32Type) diagnostics.fail(statement.span, "range bounds require Int")
            val loopScope = Scope(scope)
            val symbol = VariableSymbol(statement.name, Int32Type, mutable = false)
            if (!loopScope.define(symbol)) {
                diagnostics.error(statement.span, "duplicate variable '${statement.name}'")
            }
            val body = bindBlock(statement.body, loopScope, expectedReturnType, owner)
            BoundForStatement(symbol, start, end, body, statement.span)
        }

        is BlockStatement -> bindBlock(statement, Scope(scope), expectedReturnType, owner)
    }

    private fun bindCondition(
        expression: Expression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression {
        val bound = bindExpression(expression, scope, owner)
        requireInt32(bound, expression.span)
        return bound
    }

    private fun requireInt32(expression: BoundExpression, span: SourceSpan) {
        if (expression.type != Int32Type && expression.type != BoolType) {
            diagnostics.fail(span, "condition requires Bool or Int32")
        }
    }

    private fun bindExpression(
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
                    return member(receiver, field, expression.span, owner)
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
            if (expression.type.name !in setOf("Pointer", "BufferPointer", "Array") &&
                expression.type.arguments.isNotEmpty()) {
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
                if (managedType.pointee == PointerWildcardType) {
                    diagnostics.fail(expression.span, "${managedType.kind.displayName}<*> cannot be constructed")
                }
                val initializer = bindExpression(expression.arguments.single(), scope, owner)
                if (!isAssignable(managedType.pointee, initializer.type)) {
                    diagnostics.fail(expression.span, "Pointer initializer must be ${managedType.pointee.displayName}")
                }
                return BoundManagedPointerExpression(initializer, managedType, expression.span)
            }
            val classType = resolveClassType(expression.type)
            if (classType.objectLike) diagnostics.fail(expression.span, "object declarations cannot be constructed")
            val arguments = expression.arguments.map { bindExpression(it, scope, owner) }
            val classSymbol = classes.getValue(classType.name)
            val constructor = classSymbol.constructor
            if (constructor == null && arguments.isNotEmpty()) {
                diagnostics.fail(expression.span, "class '${classSymbol.name}' has no constructor")
            }
            if (constructor != null) {
                checkArguments(classSymbol.name, arguments, constructor.parameters, expression.span)
                checkAccess(constructor, owner, expression.span)
            }
            BoundNewExpression(classType, arguments, constructor, expression.span)
        }

        is AllocationExpression -> {
            val pointee = resolveType(expression.elementType)
            if (pointee == UnitType || pointee == PointerWildcardType) {
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
                val method = specializeGenericFunction(
                    classes.getValue("Array").methods.getValue("get"),
                    receiver
                )
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
            if (pointerType.pointee == PointerWildcardType) {
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
            if (expression.safe && !isNullableCapable(field.type)) {
                diagnostics.fail(expression.span, "safe access currently requires a reference-valued field")
            }
            member(receiver, field, expression.span, owner, expression.safe && receiver.type is NullableType).copy(
                direct = (expression.receiver as? NameExpression)?.name == "super")
        }

        is BinaryExpression -> bindBinary(expression, scope, owner)
        is CallExpression -> bindCall(expression, scope, owner)
        is ElvisExpression -> {
            val nullable = bindExpression(expression.nullable, scope, owner)
            val nullableType = nullable.type as? NullableType
                ?: diagnostics.fail(expression.nullable.span, "left side of '?:' must be nullable")
            val fallback = bindExpression(expression.fallback, scope, owner)
            if (!isAssignable(nullableType.underlying, fallback.type)) {
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
        checkAccess(method, owner, expression.span)
        if (method.parameters.size != 1 || !isAssignable(method.parameters.single().type, right.type)) {
            diagnostics.fail(expression.span, "operator '$name' has incompatible operands")
        }
        return BoundCallExpression(method, listOf(right), expression.span, receiver = left)
    }

    private fun bindCall(
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
                if (!isAssignable(elementType, argument.type) || !isAssignable(argument.type, elementType)) {
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
                                candidate.parameters.zip(arguments).all { (p, a) -> isAssignable(p.type, a.type) }
                        }
                        val best = matches.filter { candidate -> matches.none { other ->
                            other !== candidate && other.parameters.zip(candidate.parameters).all { (a, b) -> isAssignable(b.type, a.type) } &&
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
                            if (pointerType.pointee == PointerWildcardType) {
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
                            if (pointerType.pointee == PointerWildcardType) {
                                diagnostics.fail(expression.span, "cannot set BufferPointer<*>")
                            }
                            if (arguments.size != 2 ||
                                arguments[0].type != Int32Type ||
                                !isAssignable(pointerType.pointee, arguments[1].type)) {
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
                            if (pointerType.pointee == PointerWildcardType) {
                                diagnostics.fail(expression.span, "cannot read from Pointer<*>")
                            }
                            BoundDereferenceExpression(receiver, expression.span, pointerType.pointee)
                        }
                        "write" -> {
                            if (!pointerType.writable) diagnostics.fail(expression.span, "cannot write through the address of a val")
                            if (pointerType.pointee == PointerWildcardType) {
                                diagnostics.fail(expression.span, "cannot write to Pointer<*>")
                            }
                            if (arguments.size != 1 || !isAssignable(pointerType.pointee, arguments[0].type)) {
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
                checkAccess(symbol, owner, target.span)
            }
            else -> diagnostics.fail(expression.span, "call target must be a function or method")
        }

        symbol = specializeGenericFunction(symbol, receiver)
        if (symbol.owner == null && symbol.visibility == Visibility.PRIVATE &&
            symbol.declarationSpan?.source != expression.span.source) {
            diagnostics.fail(expression.span, "function '${symbol.name}' is private")
        }
        checkAccess(symbol, owner, expression.span)
        if (arguments.size != symbol.parameters.size) {
            diagnostics.fail(
                expression.span,
                "function '${symbol.name}' expects ${symbol.parameters.size} arguments, " +
                    "found ${arguments.size}"
            )
        }
        arguments.zip(symbol.parameters).forEachIndexed { index, (argument, parameter) ->
            if (!isAssignable(parameter.type, argument.type)) {
                diagnostics.error(
                    expression.arguments[index].span,
                    "argument ${index + 1}: expected ${parameter.type.displayName}, " +
                        "found ${argument.type.displayName}"
                )
            }
        }
        val safe = (expression.target as? MemberAccessExpression)?.safe == true && receiver?.type is NullableType
        if (safe && symbol.returnType != UnitType && !isNullableCapable(symbol.returnType)) {
            diagnostics.fail(expression.span, "safe call currently requires a reference return type")
        }
        val resultType = if (safe && symbol.returnType != UnitType) {
            nullable(symbol.returnType)
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

    private fun specializeGenericFunction(
        function: FunctionSymbol,
        receiver: BoundExpression?
    ): FunctionSymbol {
        val receiverType = (receiver?.type as? ClassType) ?: return function
        if (receiverType.typeArguments.isEmpty()) return function
        val arguments = receiverType.typeArguments
        fun substitute(type: RType): RType = when (type) {
            is ClassType -> if (type.name == "T" && arguments.size == 1) {
                arguments.single()
            } else {
                type.copy(typeArguments = type.typeArguments.map(::substitute))
            }
            is ManagedPointerType -> type.copy(pointee = substitute(type.pointee))
            is NullableType -> NullableType(substitute(type.underlying))
            else -> type
        }
        return function.copy(
            parameters = function.parameters.map { it.copy(type = substitute(it.type)) },
            returnType = substitute(function.returnType)
        )
    }

    private fun isAssignable(expected: RType, actual: RType): Boolean {
        if (expected == actual) return true
        if (expected is NullableType) return actual == NullType ||
            isAssignable(expected.underlying, unwrapNullable(actual))
        if (expected is ManagedPointerType && actual is ManagedPointerType) {
            if (expected.kind != actual.kind) return false
            if (expected.pointee == PointerWildcardType) return true
            if (actual.pointee == PointerWildcardType) return false
            return isAssignable(expected.pointee, actual.pointee)
        }
        if (expected is ClassType && actual is ClassType) return isSubclass(classes[actual.name], classes.getValue(expected.name))
        return false
    }

    private fun checkArguments(
        name: String,
        arguments: List<BoundExpression>,
        parameters: List<ParameterSymbol>,
        span: SourceSpan
    ) {
        if (arguments.size != parameters.size) {
            diagnostics.fail(span, "constructor '$name' expects ${parameters.size} arguments, found ${arguments.size}")
        }
        arguments.zip(parameters).forEachIndexed { index, (argument, parameter) ->
            if (!isAssignable(parameter.type, argument.type)) {
                diagnostics.error(span, "constructor argument ${index + 1}: expected ${parameter.type.displayName}, found ${argument.type.displayName}")
            }
        }
    }
}
