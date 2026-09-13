package me.rkt.semantic

import me.rkt.ast.*

/** Owns body scopes, control flow, and constructor field-initialization checks. */
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
    private fun resolveType(type: TypeReference) = typeResolver.resolveType(type)
    private fun unwrapNullable(type: RType) = typeResolver.unwrapNullable(type)

    private val rules = SemanticRules(context, typeResolver)
    private val expressions = SemanticExpressionBinder(context, typeResolver, rules)

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
            rules.checkAccess(constructor, owner, declaration.span)
            val arguments = if (explicitSuper) first.arguments.map { expressions.bindExpression(it, scope, owner) } else emptyList()
            rules.checkArguments(base.name, arguments, constructor.parameters, declaration.span)
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
        owner?.typeParameters?.toSet().orEmpty()

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
            val expression = expressions.bindExpression(statement.expression, scope, owner)
            if (!rules.isAssignable(expectedReturnType, expression.type)) {
                diagnostics.error(
                    statement.span,
                    "return type mismatch: expected ${expectedReturnType.displayName}, " +
                        "found ${expression.type.displayName}"
                )
            }
            BoundReturnStatement(expression, statement.span)
        }

        is VariableDeclarationStatement -> {
            val initializer = expressions.bindExpression(statement.initializer, scope, owner)
            val type = statement.type?.let(::resolveType) ?: initializer.type
            if (type == NullType || type == UnitType) {
                diagnostics.fail(statement.span, "variable requires a concrete value type")
            }
            if (!rules.isAssignable(type, initializer.type)) {
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
                val receiver = expressions.bindExpression(target.receiver, scope, owner)
                val arrayType = unwrapNullable(receiver.type) as? ClassType
                if (arrayType?.name == "Array") {
                    val index = expressions.bindExpression(target.index, scope, owner)
                    if (index.type != Int32Type) {
                        diagnostics.fail(target.index.span, "array index must be Int")
                    }
                    val value = expressions.bindExpression(statement.expression, scope, owner)
                    val method = classes.getValue("Array").methods.getValue("set")
                    val specialized = rules.specializeGenericFunction(method, receiver)
                    rules.checkCallable(specialized, statement.span)
                    if (!rules.isAssignable(specialized.parameters[0].type, index.type) ||
                        !rules.isAssignable(specialized.parameters[1].type, value.type)
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
            val target = expressions.bindExpression(statement.target, scope, owner)
            if (target is BoundMemberExpression) {
                if (target.safe) diagnostics.fail(statement.span, "safe access is not an assignment target")
                target.setter?.let {
                    rules.checkAccess(it, owner, statement.span)
                    rules.checkCallable(it, statement.span)
                }
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
            val expression = expressions.bindExpression(statement.expression, scope, owner)
            if (!rules.isAssignable(target.type, expression.type)) {
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
            expressions.bindExpression(statement.expression, scope, owner),
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
                val iterable = expressions.bindExpression(statement.iterable, scope, owner)
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
                        if (WildcardProjection.unreadable(elementType)) {
                            diagnostics.fail(statement.span, "cannot iterate values whose element type is hidden by '*'")
                        }
                        val iterator = BoundCallExpression(
                            rules.specializeGenericFunction(
                                classes.getValue("Array").methods.getValue("iterator"),
                                iterable
                            ),
                            emptyList(),
                            statement.span,
                            receiver = iterable
                        )
                        val condition = BoundCallExpression(
                            rules.specializeGenericFunction(
                                classes.getValue("Iterator").methods.getValue("hasNext"), iterator),
                            emptyList(),
                            statement.span,
                            receiver = iterator
                        )
                        elementAccess = BoundCallExpression(
                            rules.specializeGenericFunction(
                                classes.getValue("Iterator").methods.getValue("next"),
                                iterator
                            ),
                            emptyList(),
                            statement.span,
                            receiver = iterator
                        )
                        limit = BoundIntLiteral(0, statement.span)
                        val loopScope = Scope(scope)
                        val iteratorSymbol = VariableSymbol("__iterator", iterator.type, mutable = false)
                        loopScope.define(iteratorSymbol)
                        val iteratorReference = BoundNameExpression(iteratorSymbol, statement.span)
                        val boundCondition = condition.copy(receiver = iteratorReference)
                        val boundElementAccess = elementAccess.copy(receiver = iteratorReference)
                        val symbol = VariableSymbol(statement.name, elementType, mutable = false)
                        if (!loopScope.define(symbol)) {
                            diagnostics.error(statement.span, "duplicate variable '${statement.name}'")
                        }
                        val body = bindBlock(statement.body, loopScope, expectedReturnType, owner)
                        return BoundForStatement(
                            symbol,
                            BoundIntLiteral(0, statement.span),
                            limit,
                            body,
                            statement.span,
                            iterable,
                            null,
                            boundElementAccess,
                            boundCondition,
                            iteratorSymbol,
                            iterator
                        )
                    }
                    is ManagedPointerType -> {
                        if (iterableType.kind != ManagedPointerKind.BUFFER ||
                            iterableType.pointee == WildcardType
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
            val start = expressions.bindExpression(statement.start, scope, owner)
            val end = expressions.bindExpression(statement.end, scope, owner)
            rules.requireInt32(start, statement.start.span)
            rules.requireInt32(end, statement.end.span)
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
        val bound = expressions.bindExpression(expression, scope, owner)
        rules.requireInt32(bound, expression.span)
        return bound
    }

}
