package me.rkt.semantic

import me.rkt.ast.*
import me.rkt.diagnostic.DiagnosticReporter
import me.rkt.source.SourceSpan

class SemanticAnalyzer(
    private val diagnostics: DiagnosticReporter
) {
    private val globalScope = Scope()
    private val classes = linkedMapOf<String, ClassSymbol>()
    private val functions = linkedMapOf<FunctionDeclaration, FunctionSymbol>()

    fun analyze(module: AstModule): SemanticModule {
        registerBuiltins()
        registerClasses(module)
        registerFunctions(module)

        val boundFunctions = module.declarations
            .flatMap { declaration ->
                when (declaration) {
                    is FunctionDeclaration -> listOf(bindFunction(declaration))
                    is ClassDeclaration -> declaration.members
                        .filterIsInstance<FunctionDeclaration>()
                        .map(::bindFunction)
                    is ObjectDeclaration -> declaration.members
                        .filterIsInstance<FunctionDeclaration>()
                        .map(::bindFunction)
                    is ImportDeclaration -> emptyList()
                }
            }

        diagnostics.throwIfErrors()
        return SemanticModule(boundFunctions, classes.values.toList(), module.imports)
    }

    private fun registerBuiltins() {
        globalScope.define(
            FunctionSymbol(
                "println",
                listOf(ParameterSymbol("value", Int32Type)),
                UnitType,
                builtinTarget = "printInt32ln"
            )
        )
        globalScope.define(
            FunctionSymbol(
                "readInt32",
                emptyList(),
                Int32Type,
                builtinTarget = "readInt32"
            )
        )
        globalScope.define(
            FunctionSymbol(
                "input",
                emptyList(),
                Int32Type,
                builtinTarget = "readInt32"
            )
        )
    }

    private fun registerClasses(module: AstModule) {
        val declarations = module.declarations.filter {
            it is ClassDeclaration || it is ObjectDeclaration
        }
        for (declaration in declarations) {
            val name = when (declaration) {
                is ClassDeclaration -> declaration.name
                is ObjectDeclaration -> declaration.name
                else -> error("not a type declaration")
            }
            val type = ClassType(name, declaration is ObjectDeclaration)
            val symbol = ClassSymbol(name, type)
            if (!globalScope.define(symbol)) {
                diagnostics.error(declaration.span, "duplicate declaration '$name'")
            } else {
                classes[name] = symbol
            }
        }

        for (declaration in declarations) {
            val name = when (declaration) {
                is ClassDeclaration -> declaration.name
                is ObjectDeclaration -> declaration.name
                else -> error("not a type declaration")
            }
            val classSymbol = classes[name] ?: continue
            val members = when (declaration) {
                is ClassDeclaration -> declaration.members
                is ObjectDeclaration -> declaration.members
            }
            for (member in members) {
                if (member is FieldDeclaration) {
                    val field = FieldSymbol(
                        member.name,
                        resolveType(member.type),
                        member.mutable
                    )
                    if (classSymbol.fields.putIfAbsent(member.name, field) != null) {
                        diagnostics.error(member.span, "duplicate field '${member.name}'")
                    }
                }
            }
        }
    }

    private fun registerFunctions(module: AstModule) {
        for (declaration in module.declarations) {
            when (declaration) {
                is FunctionDeclaration -> registerFunction(declaration, null)
                is ClassDeclaration -> declaration.members
                    .filterIsInstance<FunctionDeclaration>()
                    .forEach { registerFunction(it, classes[declaration.name]) }
                is ObjectDeclaration -> declaration.members
                    .filterIsInstance<FunctionDeclaration>()
                    .forEach { registerFunction(it, classes[declaration.name]) }
                is ImportDeclaration -> Unit
            }
        }
    }

    private fun registerFunction(
        declaration: FunctionDeclaration,
        owner: ClassSymbol?
    ) {
        val parameterSymbols = declaration.parameters.map {
            ParameterSymbol(it.name, resolveType(it.type))
        }
        val symbol = FunctionSymbol(
            declaration.name,
            parameterSymbols,
            resolveType(declaration.returnType),
            owner = owner
        )
        if (owner == null) {
            if (!globalScope.define(symbol)) {
                diagnostics.error(declaration.span, "duplicate function '${declaration.name}'")
            }
        } else if (owner.methods.putIfAbsent(declaration.name, symbol) != null) {
            diagnostics.error(declaration.span, "duplicate function '${declaration.name}'")
        }
        functions[declaration] = symbol
    }

    private fun bindFunction(declaration: FunctionDeclaration): BoundFunction {
        val function = functions.getValue(declaration)
        val scope = Scope(globalScope)
        val owner = function.owner

        if (owner != null) {
            scope.define(ParameterSymbol("this", owner.type))
        }
        for (parameter in function.parameters) {
            if (!scope.define(parameter)) {
                diagnostics.error(
                    declaration.span,
                    "duplicate parameter '${parameter.name}'"
                )
            }
        }

        val body = bindBlock(declaration.body, scope, function.returnType, owner)
        return BoundFunction(function, body, function.parameters, owner?.type)
    }

    private fun bindBlock(
        block: BlockStatement,
        scope: Scope,
        expectedReturnType: RType,
        owner: ClassSymbol? = null
    ): BoundBlockStatement {
        val statements = block.statements.map {
            bindStatement(it, scope, expectedReturnType, owner)
        }
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
            if (expression.type != expectedReturnType) {
                diagnostics.error(
                    statement.span,
                    "return type mismatch: expected ${expectedReturnType.displayName}, " +
                        "found ${expression.type.displayName}"
                )
            }
            BoundReturnStatement(expression, statement.span)
        }

        is VariableDeclarationStatement -> {
            val type = resolveType(statement.type)
            val initializer = bindExpression(statement.initializer, scope, owner)
            if (initializer.type != type) {
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
            val target = bindExpression(statement.target, scope, owner)
            val mutable = when (target) {
                is BoundNameExpression -> target.symbol.mutable
                is BoundMemberExpression -> target.field.mutable
                else -> diagnostics.fail(statement.target.span, "assignment target is not writable")
            }
            if (!mutable) {
                diagnostics.fail(statement.target.span, "cannot assign to immutable value")
            }
            val expression = bindExpression(statement.expression, scope, owner)
            if (expression.type != target.type) {
                diagnostics.error(
                    statement.expression.span,
                    "assignment type mismatch: expected ${target.type.displayName}, " +
                        "found ${expression.type.displayName}"
                )
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
            val start = bindExpression(statement.start, scope, owner)
            val end = bindExpression(statement.end, scope, owner)
            requireInt32(start, statement.start.span)
            requireInt32(end, statement.end.span)
            val loopScope = Scope(scope)
            val symbol = VariableSymbol(statement.name, Int32Type, mutable = true)
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
        if (expression.type != Int32Type) {
            diagnostics.fail(span, "condition requires Int32")
        }
    }

    private fun bindExpression(
        expression: Expression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression = when (expression) {
        is IntegerLiteral -> BoundIntLiteral(expression.value, expression.span)
        is StringLiteral -> BoundStringLiteral(expression.value, expression.span)

        is NameExpression -> {
            val symbol = scope.resolve(expression.name)
            if (symbol == null && owner != null) {
                val field = owner.fields[expression.name]
                if (field != null) {
                    val receiver = BoundThisExpression(
                        ParameterSymbol("this", owner.type),
                        expression.span
                    )
                    return BoundMemberExpression(receiver, field, expression.span)
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
            val classType = resolveClassType(expression.type)
            val arguments = expression.arguments.map { bindExpression(it, scope, owner) }
            if (arguments.isNotEmpty()) {
                diagnostics.fail(expression.span, "constructors are not supported; use new ${classType.name}()")
            }
            BoundNewExpression(classType, arguments, expression.span)
        }

        is MemberAccessExpression -> {
            val receiver = bindExpression(expression.receiver, scope, owner)
            val classSymbol = receiver.type as? ClassType
                ?: diagnostics.fail(expression.span, "member access requires an object value")
            val field = classes[classSymbol.name]?.fields?.get(expression.name)
                ?: diagnostics.fail(
                    expression.span,
                    "unknown field '${expression.name}' on '${classSymbol.name}'"
                )
            BoundMemberExpression(receiver, field, expression.span)
        }

        is BinaryExpression -> bindBinary(expression, scope, owner)
        is CallExpression -> bindCall(expression, scope, owner)
    }

    private fun bindBinary(
        expression: BinaryExpression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression {
        val left = bindExpression(expression.left, scope, owner)
        val right = bindExpression(expression.right, scope, owner)
        if (left.type != Int32Type || right.type != Int32Type) {
            diagnostics.fail(
                expression.span,
                "binary arithmetic currently requires Int32 operands"
            )
        }
        val operator = when (expression.operator) {
            BinaryOperator.ADD -> BoundBinaryOperator.ADD_I32
            BinaryOperator.SUB -> BoundBinaryOperator.SUB_I32
            BinaryOperator.MUL -> BoundBinaryOperator.MUL_I32
            BinaryOperator.DIV -> BoundBinaryOperator.DIV_I32
            BinaryOperator.EQUALS -> BoundBinaryOperator.EQ_I32
            BinaryOperator.NOT_EQUALS -> BoundBinaryOperator.NE_I32
            BinaryOperator.LESS -> BoundBinaryOperator.LT_I32
            BinaryOperator.LESS_EQUALS -> BoundBinaryOperator.LE_I32
            BinaryOperator.GREATER -> BoundBinaryOperator.GT_I32
            BinaryOperator.GREATER_EQUALS -> BoundBinaryOperator.GE_I32
        }
        return BoundBinaryExpression(left, operator, right, expression.span)
    }

    private fun bindCall(
        expression: CallExpression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression {
        val arguments = expression.arguments.map { bindExpression(it, scope, owner) }
        var receiver: BoundExpression? = null
        val symbol: FunctionSymbol
        when (val target = expression.target) {
            is NameExpression -> {
                if (target.name == "println" &&
                    arguments.size == 1 &&
                    arguments[0].type == StringType
                ) {
                    symbol = FunctionSymbol(
                        "println",
                        listOf(ParameterSymbol("value", StringType)),
                        UnitType,
                        builtinTarget = "printStringln"
                    )
                } else {
                    val resolved = scope.resolve(target.name)
                    if (resolved is FunctionSymbol) {
                        symbol = resolved
                    } else if (resolved == null && owner != null) {
                        symbol = owner.methods[target.name]
                            ?: diagnostics.fail(target.span, "unknown function '${target.name}'")
                        receiver = BoundThisExpression(
                            ParameterSymbol("this", owner.type),
                            target.span
                        )
                    } else {
                        diagnostics.fail(target.span, "unknown function '${target.name}'")
                    }
                }
            }
            is MemberAccessExpression -> {
                receiver = bindExpression(target.receiver, scope, owner)
                val classType = receiver.type as? ClassType
                    ?: diagnostics.fail(target.span, "method receiver must be an object value")
                symbol = classes[classType.name]?.methods?.get(target.name)
                    ?: diagnostics.fail(
                        target.span,
                        "unknown method '${target.name}' on '${classType.name}'"
                    )
            }
            else -> diagnostics.fail(expression.span, "call target must be a function or method")
        }

        if (arguments.size != symbol.parameters.size) {
            diagnostics.fail(
                expression.span,
                "function '${symbol.name}' expects ${symbol.parameters.size} arguments, " +
                    "found ${arguments.size}"
            )
        }
        arguments.zip(symbol.parameters).forEachIndexed { index, (argument, parameter) ->
            if (argument.type != parameter.type) {
                diagnostics.error(
                    expression.arguments[index].span,
                    "argument ${index + 1}: expected ${parameter.type.displayName}, " +
                        "found ${argument.type.displayName}"
                )
            }
        }
        return BoundCallExpression(symbol, arguments, expression.span, receiver = receiver)
    }

    private fun resolveClassType(type: TypeReference): ClassType =
        classes[type.name]?.type
            ?: diagnostics.fail(type.span, "unknown type '${type.name}'")

    private fun resolveType(type: TypeReference): RType = when (type.name) {
        "Int32" -> Int32Type
        "Unit" -> UnitType
        "String" -> StringType
        else -> resolveClassType(type)
    }
}
