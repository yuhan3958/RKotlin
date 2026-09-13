package me.rkt.lower

import me.rkt.ir.*
import me.rkt.semantic.*

/** Owns function setup and statement/control-flow lowering. */
internal object IrFunctionLowering {
    fun lowerFunction(function: BoundFunction): IrFunction {
        val context = FunctionContext()
        context.functionName = function.symbol.name
        context.ownerName = function.symbol.owner?.name
        context.returnType = IrLoweringRules.lowerType(function.symbol.returnType)

        val receiver = function.receiver?.let {
            IrParameter(0, "this", IrLoweringRules.lowerType(it)).also { parameter ->
                context.receiver = parameter
            }
        }
        val parameters = function.parameters.mapIndexed { index, symbol ->
            IrParameter(index + if (receiver == null) 0 else 1, symbol.name, IrLoweringRules.lowerType(symbol.type)).also {
                context.parameters[symbol] = it
            }
        }.let { if (receiver == null) it else listOf(receiver) + it }

        if (function.symbol.name == "constructor") {
            function.symbol.owner!!.fields.values.filter {
                it.ownerName == function.symbol.owner.name && it.type == StringType
            }.forEach { context.instructions += IrFieldStoreInstruction(receiver!!, it.name, IrStringConstant("")) }
        }

        for (statement in function.body.statements) {
            lowerStatement(statement, context)
        }

        return IrFunction(
            function.symbol.generatedName,
            parameters,
            context.allLocals(),
            IrLoweringRules.lowerType(function.symbol.returnType),
            context.instructions,
            function.body.span.source.path.toAbsolutePath().normalize().toString()
        )
    }

    private fun lowerStatement(statement: BoundStatement, context: FunctionContext) {
        when (statement) {
            is BoundReturnStatement -> {
                val value = IrLoweringRules.coerce(IrExpressionLowering.lowerExpression(statement.expression, context), context.returnType, context)
                context.instructions += IrReturnInstruction(
                    if (value == IrUnitValue) null else value
                )
            }

            is BoundVariableDeclarationStatement -> {
                val local = context.newLocal(
                    statement.symbol,
                    IrLoweringRules.lowerType(statement.symbol.type)
                )
                val initializer = IrLoweringRules.coerce(IrExpressionLowering.lowerExpression(statement.initializer, context), local.type, context)
                context.instructions += IrStoreInstruction(local, initializer)
            }

            is BoundAssignmentStatement -> {
                val targetReceiver = (statement.target as? BoundMemberExpression)?.let {
                    IrExpressionLowering.lowerExpression(it.receiver, context)
                }
                val value = IrLoweringRules.coerce(IrExpressionLowering.lowerExpression(statement.expression, context), IrLoweringRules.lowerType(statement.target.type), context)
                when (val target = statement.target) {
                    is BoundNameExpression -> {
                        val local = context.locals[target.symbol as VariableSymbol]
                            ?: error("unmapped local symbol: ${target.symbol.name}")
                        context.instructions += IrStoreInstruction(local, value)
                    }
                    is BoundMemberExpression -> {
                        val receiver = targetReceiver!!
                        val setter = "set${target.field.name.replaceFirstChar { it.uppercaseChar() }}"
                        context.instructions += if ((context.functionName == setter || context.functionName == "constructor") &&
                            context.ownerName == target.field.ownerName) {
                            IrFieldStoreInstruction(IrLoweringRules.coerce(receiver, IrObjectType(target.field.ownerName), context), target.field.name, value)
                        } else {
                            val method = target.setter ?: error("missing setter")
                            IrCallInstruction(null, IrLoweringRules.callName(method, target.direct),
                                listOf(IrLoweringRules.coerce(receiver, IrLoweringRules.lowerType(method.owner!!.type), context), value), IrVoid)
                        }
                    }
                    is BoundDereferenceExpression -> {
                        context.instructions += IrPointerStoreInstruction(
                            IrExpressionLowering.lowerExpression(target.pointer, context),
                            value
                        )
                    }
                    is BoundBufferGetExpression -> {
                        context.instructions += IrBufferSetInstruction(
                            IrExpressionLowering.lowerExpression(target.pointer, context),
                            IrLoweringRules.coerce(IrExpressionLowering.lowerExpression(target.index, context), IrI32, context),
                            value,
                            IrLoweringRules.lowerType(target.type)
                        )
                    }
                    else -> error("unsupported assignment target")
                }
            }

            is BoundExpressionStatement -> {
                IrExpressionLowering.lowerExpression(statement.expression, context)
            }

            is BoundIfStatement -> {
                val thenLabel = context.newLabel()
                val elseLabel = context.newLabel()
                val endLabel = context.newLabel()
                val condition = IrExpressionLowering.lowerExpression(statement.condition, context)
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
                val condition = IrExpressionLowering.lowerExpression(statement.condition, context)
                context.instructions += IrBranchInstruction(condition, bodyLabel, endLabel)
                context.instructions += IrLabelInstruction(bodyLabel)
                lowerStatement(statement.body, context)
                context.instructions += IrJumpInstruction(conditionLabel)
                context.instructions += IrLabelInstruction(endLabel)
            }

            is BoundForStatement -> {
                val local = context.newLocal(statement.symbol, IrLoweringRules.lowerType(statement.symbol.type))
                val indexLocal = statement.indexSymbol?.let { context.newLocal(it, IrI32) }
                val iteratorLocal = statement.iteratorSymbol?.let { context.newLocal(it, IrLoweringRules.lowerType(it.type)) }
                if (iteratorLocal != null) {
                    context.instructions += IrStoreInstruction(
                        iteratorLocal,
                        IrExpressionLowering.lowerExpression(
                            (statement.iteratorInitializer ?: error("missing iterator initializer")),
                            context
                        )
                    )
                }
                if (statement.iteratorCondition == null) {
                    context.instructions += IrStoreInstruction(
                        indexLocal ?: local,
                        IrExpressionLowering.lowerExpression(statement.start, context)
                    )
                }
                val end = if (statement.iteratorCondition == null) {
                    IrExpressionLowering.lowerExpression(statement.end, context)
                } else {
                    null
                }
                val conditionLabel = context.newLabel()
                val bodyLabel = context.newLabel()
                val endLabel = context.newLabel()
                context.instructions += IrLabelInstruction(conditionLabel)
                val current = context.newRegister(IrI32)
                val condition = context.newRegister(IrI32)
                if (statement.iteratorCondition != null) {
                    context.instructions += IrCastInstruction(
                        condition,
                        IrLoweringRules.coerce(IrExpressionLowering.lowerExpression(statement.iteratorCondition, context), IrI32, context)
                    )
                } else {
                    context.instructions += IrLoadInstruction(current, indexLocal ?: local)
                    context.instructions += IrBinaryInstruction(
                        condition,
                        if (statement.iterable != null) IrBinaryOperator.LT_I32 else IrBinaryOperator.LE_I32,
                        current,
                        end!!
                    )
                }
                context.instructions += IrBranchInstruction(condition, bodyLabel, endLabel)
                context.instructions += IrLabelInstruction(bodyLabel)
                if (statement.iterable != null) {
                    val access = statement.elementAccess ?: error("missing for-in element access")
                    context.instructions += IrStoreInstruction(
                        local,
                        IrLoweringRules.coerce(IrExpressionLowering.lowerExpression(access, context), local.type, context)
                    )
                }
                lowerStatement(statement.body, context)
                if (statement.iteratorCondition != null) {
                    context.instructions += IrJumpInstruction(conditionLabel)
                    context.instructions += IrLabelInstruction(endLabel)
                    return
                }
                val finished = context.newRegister(IrI32)
                val incrementLabel = context.newLabel()
                context.instructions += IrBinaryInstruction(
                    finished,
                    if (statement.iterable != null) IrBinaryOperator.GE_I32 else IrBinaryOperator.EQ_I32,
                    current,
                    end!!
                )
                context.instructions += IrBranchInstruction(finished, endLabel, incrementLabel)
                context.instructions += IrLabelInstruction(incrementLabel)
                val incremented = context.newRegister(IrI32)
                context.instructions += IrBinaryInstruction(
                    incremented,
                    IrBinaryOperator.ADD_I32,
                    current,
                    IrIntConstant(1)
                )
                context.instructions += IrStoreInstruction(indexLocal ?: local, incremented)
                context.instructions += IrJumpInstruction(conditionLabel)
                context.instructions += IrLabelInstruction(endLabel)
            }

            is BoundBlockStatement -> {
                statement.statements.forEach { lowerStatement(it, context) }
            }
        }
    }

}
