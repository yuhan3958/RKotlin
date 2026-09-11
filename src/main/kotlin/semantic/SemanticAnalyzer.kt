package me.rkt.semantic

import me.rkt.ast.*
import me.rkt.diagnostic.DiagnosticReporter
import me.rkt.source.SourceSpan

class SemanticAnalyzer(
    private val diagnostics: DiagnosticReporter
) {
    private val globalScope = Scope()
    private val functions = linkedMapOf<FunctionDeclaration, FunctionSymbol>()

    fun analyze(module: AstModule): SemanticModule {
        registerBuiltins()
        registerFunctions(module)
        val boundFunctions = module.declarations
            .filterIsInstance<FunctionDeclaration>()
            .map(::bindFunction)

        diagnostics.throwIfErrors()
        return SemanticModule(boundFunctions)
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

    private fun registerFunctions(module: AstModule) {
        for (declaration in module.declarations.filterIsInstance<FunctionDeclaration>()) {
            val parameterSymbols = declaration.parameters.map {
                ParameterSymbol(it.name, resolveType(it.type))
            }

            val symbol = FunctionSymbol(
                declaration.name,
                parameterSymbols,
                resolveType(declaration.returnType)
            )

            if (!globalScope.define(symbol)) {
                diagnostics.error(declaration.span, "duplicate function '${declaration.name}'")
            }

            functions[declaration] = symbol
        }
    }

    private fun bindFunction(declaration: FunctionDeclaration): BoundFunction {
        val function = functions.getValue(declaration)
        val scope = Scope(globalScope)

        for (parameter in function.parameters) {
            if (!scope.define(parameter)) {
                diagnostics.error(
                    declaration.span,
                    "duplicate parameter '${parameter.name}'"
                )
            }
        }

        val body = bindBlock(declaration.body, scope, function.returnType)
        return BoundFunction(function, body, function.parameters)
    }

    private fun bindBlock(
        block: BlockStatement,
        scope: Scope,
        expectedReturnType: RType
    ): BoundBlockStatement {
        val statements = block.statements.map {
            bindStatement(it, scope, expectedReturnType)
        }

        return BoundBlockStatement(statements, block.span)
    }

    private fun bindStatement(
        statement: Statement,
        scope: Scope,
        expectedReturnType: RType
    ): BoundStatement = when (statement) {
        is ReturnStatement -> {
            val expression = bindExpression(statement.expression, scope)

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
            val initializer = bindExpression(statement.initializer, scope)

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
            val symbol = scope.resolve(statement.name)
                ?: diagnostics.fail(
                    statement.span,
                    "unknown name '${statement.name}'"
                )

            if (symbol !is ValueSymbol) {
                diagnostics.fail(
                    statement.span,
                    "'${statement.name}' is not a variable"
                )
            }

            if (!symbol.mutable) {
                diagnostics.fail(
                    statement.span,
                    "cannot assign to immutable value '${statement.name}'"
                )
            }

            val expression = bindExpression(statement.expression, scope)
            if (expression.type != symbol.type) {
                diagnostics.error(
                    statement.expression.span,
                    "assignment type mismatch: expected ${symbol.type.displayName}, " +
                        "found ${expression.type.displayName}"
                )
            }

            BoundAssignmentStatement(symbol, expression, statement.span)
        }

        is ExpressionStatement -> BoundExpressionStatement(
            bindExpression(statement.expression, scope),
            statement.span
        )

        is IfStatement -> {
            val condition = bindCondition(statement.condition, scope)
            val thenBranch = bindBlock(statement.thenBranch, Scope(scope), expectedReturnType)
            val elseBranch = statement.elseBranch?.let {
                bindBlock(it, Scope(scope), expectedReturnType)
            }
            BoundIfStatement(condition, thenBranch, elseBranch, statement.span)
        }

        is WhileStatement -> {
            val condition = bindCondition(statement.condition, scope)
            val body = bindBlock(statement.body, Scope(scope), expectedReturnType)
            BoundWhileStatement(condition, body, statement.span)
        }

        is ForStatement -> {
            val start = bindExpression(statement.start, scope)
            val end = bindExpression(statement.end, scope)
            requireInt32(start, statement.start.span)
            requireInt32(end, statement.end.span)
            val loopScope = Scope(scope)
            val symbol = VariableSymbol(statement.name, Int32Type, mutable = true)
            if (!loopScope.define(symbol)) {
                diagnostics.error(statement.span, "duplicate variable '${statement.name}'")
            }
            val body = bindBlock(statement.body, loopScope, expectedReturnType)
            BoundForStatement(symbol, start, end, body, statement.span)
        }

        is BlockStatement -> bindBlock(statement, Scope(scope), expectedReturnType)
    }

    private fun bindCondition(expression: Expression, scope: Scope): BoundExpression {
        val bound = bindExpression(expression, scope)
        requireInt32(bound, expression.span)
        return bound
    }

    private fun requireInt32(expression: BoundExpression, span: SourceSpan) {
        if (expression.type != Int32Type) {
            diagnostics.fail(span, "condition requires Int32")
        }
    }

    private fun bindExpression(expression: Expression, scope: Scope): BoundExpression =
        when (expression) {
            is IntegerLiteral -> BoundIntLiteral(expression.value, expression.span)
            is StringLiteral -> BoundStringLiteral(expression.value, expression.span)

            is NameExpression -> {
                val symbol = scope.resolve(expression.name)
                    ?: diagnostics.fail(
                        expression.span,
                        "unknown name '${expression.name}'"
                    )

                if (symbol !is ValueSymbol) {
                    diagnostics.fail(
                        expression.span,
                        "'${expression.name}' is not a value"
                    )
                }

                BoundNameExpression(symbol, expression.span)
            }

            is BinaryExpression -> bindBinary(expression, scope)
            is CallExpression -> bindCall(expression, scope)
        }

    private fun bindBinary(
        expression: BinaryExpression,
        scope: Scope
    ): BoundExpression {
        val left = bindExpression(expression.left, scope)
        val right = bindExpression(expression.right, scope)

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

        return BoundBinaryExpression(
            left,
            operator,
            right,
            expression.span
        )
    }

    private fun bindCall(
        expression: CallExpression,
        scope: Scope
    ): BoundExpression {
        val target = expression.target as? NameExpression
            ?: diagnostics.fail(expression.span, "call target must be a function name")

        val arguments = expression.arguments.map { bindExpression(it, scope) }

        val symbol = if (
            target.name == "println" &&
            arguments.size == 1 &&
            arguments[0].type == StringType
        ) {
            FunctionSymbol(
                "println",
                listOf(ParameterSymbol("value", StringType)),
                UnitType,
                builtinTarget = "printStringln"
            )
        } else {
            scope.resolve(target.name)
        }
            ?: diagnostics.fail(target.span, "unknown function '${target.name}'")

        if (symbol !is FunctionSymbol) {
            diagnostics.fail(target.span, "'${target.name}' is not a function")
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

        return BoundCallExpression(symbol, arguments, expression.span)
    }

    private fun resolveType(type: TypeReference): RType = when (type.name) {
        "Int32" -> Int32Type
        "Unit" -> UnitType
        "String" -> StringType
        else -> diagnostics.fail(type.span, "unknown type '${type.name}'")
    }
}
