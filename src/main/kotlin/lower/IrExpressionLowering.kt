package me.rkt.lower

import me.rkt.ir.*
import me.rkt.semantic.*

/** Lowers value expressions using the current function context. */
internal object IrExpressionLowering {
    fun lowerExpression(
        expression: BoundExpression,
        context: FunctionContext
    ): IrValue = when (expression) {
        is BoundIntLiteral -> IrIntConstant(expression.value)
        is BoundVoidLiteral -> IrUnitValue
        is BoundStringLiteral -> IrStringConstant(expression.value)
        is BoundNullLiteral -> IrNullConstant(IrPointerType(IrVoid))
        is BoundThisExpression -> IrLoweringRules.coerce(context.receiver ?: error("unmapped method receiver"), IrLoweringRules.lowerType(expression.type), context)

        is BoundNewExpression -> {
            val result = context.newRegister(IrLoweringRules.lowerType(expression.type))
            context.instructions += IrNewObjectInstruction(
                result,
                IrLoweringRules.lowerType(expression.type) as IrObjectType
            )
            expression.constructor?.let { constructor ->
                context.instructions += IrCallInstruction(
                    null,
                    constructor.generatedName,
                    listOf(result) + expression.arguments.zip(constructor.parameters).map { (argument, parameter) ->
                        IrLoweringRules.coerce(lowerExpression(argument, context), IrLoweringRules.lowerType(parameter.type), context)
                    },
                    IrVoid
                )
            }
            result
        }

        is BoundObjectReference -> IrObjectReference(
            expression.classType.name,
            IrObjectType(expression.classType.name)
        )

        is BoundMemberExpression -> {
            val receiver = lowerExpression(expression.receiver, context)
            val result = context.newRegister(IrLoweringRules.lowerType(expression.type))
            val getter = expression.getter ?: error("missing getter")
            context.instructions += if (!expression.safe && context.functionName == getter.name &&
                context.ownerName == expression.field.ownerName) {
                IrFieldLoadInstruction(result, IrLoweringRules.coerce(receiver, IrObjectType(expression.field.ownerName), context), expression.field.name)
            } else {
                IrCallInstruction(result, IrLoweringRules.callName(getter, expression.direct),
                    listOf(IrLoweringRules.coerce(receiver, IrLoweringRules.lowerType(getter.owner!!.type), context)), IrLoweringRules.lowerType(getter.returnType), expression.safe)
            }
            result
        }

        is BoundElvisExpression -> {
            val nullable = lowerExpression(expression.nullable, context)
            val resultType = IrLoweringRules.lowerType(expression.type)
            val local = context.newTemporaryLocal(resultType)
            val presentLabel = context.newLabel()
            val fallbackLabel = context.newLabel()
            val endLabel = context.newLabel()
            context.instructions += IrBranchInstruction(nullable, presentLabel, fallbackLabel)
            context.instructions += IrLabelInstruction(presentLabel)
            val present = if (nullable.type == IrNullableI32) {
                context.newRegister(IrI32).also { context.instructions += IrUnboxInstruction(it, nullable) }
            } else nullable
            context.instructions += IrStoreInstruction(local, present)
            context.instructions += IrJumpInstruction(endLabel)
            context.instructions += IrLabelInstruction(fallbackLabel)
            context.instructions += IrStoreInstruction(
                local,
                lowerExpression(expression.fallback, context)
            )
            context.instructions += IrJumpInstruction(endLabel)
            context.instructions += IrLabelInstruction(endLabel)
            val result = context.newRegister(resultType)
            context.instructions += IrLoadInstruction(result, local)
            result
        }

        is BoundAddressExpression -> {
            val local = context.locals[expression.value.symbol]
            val result = context.newRegister(IrLoweringRules.lowerType(expression.type))
            if (local != null) {
                context.instructions += IrAddressInstruction(result, local)
            } else {
                val parameter = context.parameters[expression.value.symbol]
                    ?: error("unmapped address value")
                context.instructions += IrAddressValueInstruction(result, parameter)
            }
            result
        }

        is BoundPointerWriteExpression -> {
            val pointer = lowerExpression(expression.pointer, context)
            val value = IrLoweringRules.coerce(lowerExpression(expression.value, context), (pointer.type as IrPointerType).pointee, context)
            context.instructions += IrPointerStoreInstruction(pointer, value)
            IrUnitValue
        }

        is BoundPointerAddExpression -> {
            val result = context.newRegister(IrLoweringRules.lowerType(expression.type))
            context.instructions += IrPointerAddInstruction(
                result,
                lowerExpression(expression.pointer, context),
                IrLoweringRules.coerce(lowerExpression(expression.offset, context), IrI32, context),
                IrLoweringRules.lowerType(expression.type.pointee),
                expression.direction
            )
            result
        }

        is BoundBufferGetExpression -> {
            val result = context.newRegister(IrLoweringRules.lowerType(expression.type))
            context.instructions += IrBufferGetInstruction(
                result,
                lowerExpression(expression.pointer, context),
                IrLoweringRules.coerce(lowerExpression(expression.index, context), IrI32, context),
                IrLoweringRules.lowerType(expression.type)
            )
            result
        }

        is BoundBufferSetExpression -> {
            context.instructions += IrBufferSetInstruction(
                lowerExpression(expression.pointer, context),
                IrLoweringRules.coerce(lowerExpression(expression.index, context), IrI32, context),
                IrLoweringRules.coerce(
                    lowerExpression(expression.value, context),
                    IrLoweringRules.lowerType((expression.pointer.type as ManagedPointerType).pointee),
                    context
                ),
                IrLoweringRules.lowerType((expression.pointer.type as ManagedPointerType).pointee)
            )
            IrUnitValue
        }

        is BoundBufferLengthExpression -> {
            val result = context.newRegister(IrI32)
            context.instructions += IrBufferLengthInstruction(
                result,
                lowerExpression(expression.pointer, context)
            )
            result
        }

        is BoundBufferAllocationExpression -> {
            val result = context.newRegister(IrLoweringRules.lowerType(expression.type))
            context.instructions += IrBufferAllocationInstruction(
                result,
                IrLoweringRules.coerce(lowerExpression(expression.length, context), IrI32, context),
                IrLoweringRules.lowerType(expression.type.pointee)
            )
            result
        }

        is BoundArrayLiteral -> {
            val arrayType = IrLoweringRules.lowerType(expression.type) as IrObjectType
            val result = context.newRegister(arrayType)
            context.instructions += IrNewObjectInstruction(result, arrayType)
            context.instructions += IrArrayInitializeInstruction(
                result,
                IrIntConstant(expression.values.size),
                IrLoweringRules.lowerType(expression.elementType)
            )
            val elementType = IrLoweringRules.lowerType(expression.elementType)
            val data = context.newRegister(IrPointerType(elementType, IrPointerKind.BUFFER))
            context.instructions += IrFieldLoadInstruction(data, result, "data")
            expression.values.forEachIndexed { index, item ->
                context.instructions += IrBufferSetInstruction(data, IrIntConstant(index),
                    IrLoweringRules.coerce(lowerExpression(item, context), elementType, context), elementType)
            }
            result
        }

        is BoundDereferenceExpression -> {
            val result = context.newRegister(IrLoweringRules.lowerType(expression.type))
            context.instructions += IrPointerLoadInstruction(
                result,
                lowerExpression(expression.pointer, context)
            )
            result
        }

        is BoundFreeExpression -> {
            context.instructions += IrFreeInstruction(lowerExpression(expression.pointer, context))
            IrUnitValue
        }

        is BoundTypeIsFreedExpression -> {
            val result = context.newRegister(IrI32)
            context.instructions += IrTypeIsFreedInstruction(
                result,
                lowerExpression(expression.value, context)
            )
            result
        }

        is BoundManagedPointerExpression -> {
            val pointeeType = IrLoweringRules.lowerType(expression.type.pointee)
            val result = context.newRegister(IrLoweringRules.lowerType(expression.type))
            context.instructions += IrManagedPointerInstruction(
                result,
                IrLoweringRules.coerce(lowerExpression(expression.initializer, context), pointeeType, context),
                pointeeType
            )
            result
        }

        is BoundNameExpression -> when (val symbol = expression.symbol) {
            is ParameterSymbol -> if (symbol.name == "this") {
                context.receiver ?: error("unmapped method receiver")
            } else {
                context.parameters[symbol]
                    ?: error("unmapped parameter symbol: ${symbol.name}")
            }
            is VariableSymbol -> {
                val local = context.locals[symbol]
                    ?: error("unmapped local symbol: ${symbol.name}")
                val result = context.newRegister(IrLoweringRules.lowerType(symbol.type))
                context.instructions += IrLoadInstruction(result, local)
                result
            }
            is FieldSymbol -> error("field reference must be qualified")
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
            var receiver = expression.receiver?.let { lowerExpression(it, context) }
            val returnType = IrLoweringRules.lowerType(expression.type)
            val actualReturnType = IrLoweringRules.lowerType(expression.function.returnType)
            val safeLocal = if (expression.safe && returnType != IrVoid) context.newTemporaryLocal(returnType) else null
            val missing = if (expression.safe) context.newLabel() else -1
            val end = if (expression.safe) context.newLabel() else -1
            if (expression.safe) {
                val present = context.newLabel()
                context.instructions += IrBranchInstruction(receiver!!, present, missing)
                context.instructions += IrLabelInstruction(present)
            }
            if (receiver?.type == IrNullableI32) {
                val unboxed = context.newRegister(IrI32)
                context.instructions += IrUnboxInstruction(unboxed, receiver)
                receiver = unboxed
            }
            val args = buildList {
                receiver?.let {
                    add(if (expression.function.builtinTarget != null) it else
                        IrLoweringRules.coerce(it, IrLoweringRules.lowerType(expression.function.owner!!.type), context))
                }
                addAll(expression.arguments.zip(expression.function.parameters).map { (argument, parameter) ->
                    IrLoweringRules.coerce(lowerExpression(argument, context), IrLoweringRules.lowerType(parameter.type), context)
                })
            }
            val result = if (actualReturnType == IrVoid) null else context.newRegister(actualReturnType)

            context.instructions += IrCallInstruction(
                result,
                IrLoweringRules.callName(expression.function, expression.direct),
                args,
                actualReturnType
            )
            if (expression.safe) {
                if (safeLocal != null) context.instructions += IrStoreInstruction(safeLocal, IrLoweringRules.coerce(result!!, returnType, context))
                context.instructions += IrJumpInstruction(end)
                context.instructions += IrLabelInstruction(missing)
                if (safeLocal != null) context.instructions += IrStoreInstruction(safeLocal, IrNullConstant(returnType))
                context.instructions += IrLabelInstruction(end)
            }
            if (safeLocal != null) {
                context.newRegister(returnType).also { context.instructions += IrLoadInstruction(it, safeLocal) }
            } else result ?: IrUnitValue
        }
    }

}
