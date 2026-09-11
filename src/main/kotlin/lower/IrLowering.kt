package me.rkt.lower

import me.rkt.ir.*
import me.rkt.semantic.*

class IrLowering {
    fun lower(module: SemanticModule): IrModule =
        IrModule(module.functions.map(::lowerFunction))

    private fun lowerFunction(function: BoundFunction): IrFunction {
        val context = FunctionContext()

        val parameters = function.parameters.mapIndexed { index, symbol ->
            IrParameter(index, symbol.name, lowerType(symbol.type)).also {
                context.parameters[symbol] = it
            }
        }

        for (statement in function.body.statements) {
            lowerStatement(statement, context)
        }

        return IrFunction(
            function.symbol.name,
            parameters,
            context.locals.values.toList(),
            lowerType(function.symbol.returnType),
            context.instructions
        )
    }

    private fun lowerStatement(statement: BoundStatement, context: FunctionContext) {
        when (statement) {
            is BoundReturnStatement -> {
                val value = lowerExpression(statement.expression, context)
                context.instructions += IrReturnInstruction(
                    if (value == IrUnitValue) null else value
                )
            }

            is BoundVariableDeclarationStatement -> {
                val local = context.newLocal(
                    statement.symbol,
                    lowerType(statement.symbol.type)
                )
                val initializer = lowerExpression(statement.initializer, context)
                context.instructions += IrStoreInstruction(local, initializer)
            }

            is BoundAssignmentStatement -> {
                val local = context.locals[statement.symbol as VariableSymbol]
                    ?: error("unmapped local symbol: ${statement.symbol.name}")
                context.instructions += IrStoreInstruction(
                    local,
                    lowerExpression(statement.expression, context)
                )
            }

            is BoundExpressionStatement -> {
                lowerExpression(statement.expression, context)
            }

            is BoundIfStatement -> {
                val thenLabel = context.newLabel()
                val elseLabel = context.newLabel()
                val endLabel = context.newLabel()
                val condition = lowerExpression(statement.condition, context)
                context.instructions += IrBranchInstruction(condition, thenLabel, elseLabel)
                context.instructions += IrLabelInstruction(thenLabel)
                lowerStatement(statement.thenBranch, context)
                context.instructions += IrJumpInstruction(endLabel)
                context.instructions += IrLabelInstruction(elseLabel)
                statement.elseBranch?.let { lowerStatement(it, context) }
                context.instructions += IrLabelInstruction(endLabel)
            }

            is BoundWhileStatement -> {
                val conditionLabel = context.newLabel()
                val bodyLabel = context.newLabel()
                val endLabel = context.newLabel()
                context.instructions += IrLabelInstruction(conditionLabel)
                val condition = lowerExpression(statement.condition, context)
                context.instructions += IrBranchInstruction(condition, bodyLabel, endLabel)
                context.instructions += IrLabelInstruction(bodyLabel)
                lowerStatement(statement.body, context)
                context.instructions += IrJumpInstruction(conditionLabel)
                context.instructions += IrLabelInstruction(endLabel)
            }

            is BoundForStatement -> {
                val local = context.newLocal(statement.symbol, IrI32)
                context.instructions += IrStoreInstruction(
                    local,
                    lowerExpression(statement.start, context)
                )
                val conditionLabel = context.newLabel()
                val bodyLabel = context.newLabel()
                val endLabel = context.newLabel()
                context.instructions += IrLabelInstruction(conditionLabel)
                val current = context.newRegister(IrI32)
                context.instructions += IrLoadInstruction(current, local)
                val end = lowerExpression(statement.end, context)
                val condition = context.newRegister(IrI32)
                context.instructions += IrBinaryInstruction(
                    condition,
                    IrBinaryOperator.LE_I32,
                    current,
                    end
                )
                context.instructions += IrBranchInstruction(condition, bodyLabel, endLabel)
                context.instructions += IrLabelInstruction(bodyLabel)
                lowerStatement(statement.body, context)
                val incremented = context.newRegister(IrI32)
                context.instructions += IrBinaryInstruction(
                    incremented,
                    IrBinaryOperator.ADD_I32,
                    current,
                    IrIntConstant(1)
                )
                context.instructions += IrStoreInstruction(local, incremented)
                context.instructions += IrJumpInstruction(conditionLabel)
                context.instructions += IrLabelInstruction(endLabel)
            }

            is BoundBlockStatement -> {
                statement.statements.forEach { lowerStatement(it, context) }
            }
        }
    }

    private fun lowerExpression(
        expression: BoundExpression,
        context: FunctionContext
    ): IrValue = when (expression) {
        is BoundIntLiteral -> IrIntConstant(expression.value)
        is BoundStringLiteral -> IrStringConstant(expression.value)

        is BoundNameExpression -> when (val symbol = expression.symbol) {
            is ParameterSymbol -> context.parameters[symbol]
                ?: error("unmapped parameter symbol: ${symbol.name}")
            is VariableSymbol -> {
                val local = context.locals[symbol]
                    ?: error("unmapped local symbol: ${symbol.name}")
                val result = context.newRegister(lowerType(symbol.type))
                context.instructions += IrLoadInstruction(result, local)
                result
            }
        }

        is BoundBinaryExpression -> {
            val left = lowerExpression(expression.left, context)
            val right = lowerExpression(expression.right, context)
            val result = context.newRegister(IrI32)

            context.instructions += IrBinaryInstruction(
                result,
                when (expression.operator) {
                    BoundBinaryOperator.ADD_I32 -> IrBinaryOperator.ADD_I32
                    BoundBinaryOperator.SUB_I32 -> IrBinaryOperator.SUB_I32
                    BoundBinaryOperator.MUL_I32 -> IrBinaryOperator.MUL_I32
                    BoundBinaryOperator.DIV_I32 -> IrBinaryOperator.DIV_I32
                    BoundBinaryOperator.EQ_I32 -> IrBinaryOperator.EQ_I32
                    BoundBinaryOperator.NE_I32 -> IrBinaryOperator.NE_I32
                    BoundBinaryOperator.LT_I32 -> IrBinaryOperator.LT_I32
                    BoundBinaryOperator.LE_I32 -> IrBinaryOperator.LE_I32
                    BoundBinaryOperator.GT_I32 -> IrBinaryOperator.GT_I32
                    BoundBinaryOperator.GE_I32 -> IrBinaryOperator.GE_I32
                },
                left,
                right
            )

            result
        }

        is BoundCallExpression -> {
            val args = expression.arguments.map { lowerExpression(it, context) }
            val returnType = lowerType(expression.type)
            val result = if (returnType == IrVoid) null else context.newRegister(returnType)

            context.instructions += IrCallInstruction(
                result,
                expression.function.builtinTarget ?: expression.function.name,
                args,
                returnType
            )

            result ?: IrUnitValue
        }
    }

    private fun lowerType(type: RType): IrType = when (type) {
        Int32Type -> IrI32
        UnitType -> IrVoid
        StringType -> IrString
    }

    private class FunctionContext {
        val instructions = mutableListOf<IrInstruction>()
        val parameters = mutableMapOf<ValueSymbol, IrParameter>()
        val locals = linkedMapOf<VariableSymbol, IrLocal>()
        private var nextRegisterId = 0
        private var nextLocalId = 0
        private var nextLabelId = 0

        fun newRegister(type: IrType): IrRegister =
            IrRegister(nextRegisterId++, type)

        fun newLocal(symbol: VariableSymbol, type: IrType): IrLocal {
            val local = IrLocal(nextLocalId++, symbol.name, type)
            locals[symbol] = local
            return local
        }

        fun newLabel(): Int = nextLabelId++
    }
}
