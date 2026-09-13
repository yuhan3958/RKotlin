package me.rkt.lower

import me.rkt.ir.*
import me.rkt.semantic.*
import me.rkt.ast.Visibility

class IrLowering {
    fun lower(module: SemanticModule): IrModule =
        IrModule(
            module.functions.map(::lowerFunction) + module.classes.flatMap(::lowerAccessors),
            module.classes.filter { lowerType(it.type) is IrObjectType && it.name != "Pointer" }.map { classSymbol ->
                IrClass(
                    classSymbol.name,
                    classSymbol.fields.values.filter { it.ownerName == classSymbol.name }.map {
                        IrField(it.name, lowerType(it.type))
                    },
                    classSymbol.type.objectLike,
                    classSymbol.baseClass?.name,
                    classSymbol.methods.values.filter { virtual(it) }.map {
                        IrMethod(it.name, it.generatedName, it.owner!!.name,
                            it.parameters.map { p -> lowerType(p.type) }, lowerType(it.returnType))
                    },
                    classSymbol.declarationSpan!!.source.path.toAbsolutePath().normalize().toString()
                )
            }
        )

    private fun lowerAccessors(owner: ClassSymbol): List<IrFunction> = buildList {
        val receiver = IrParameter(0, "this", IrObjectType(owner.name))
        if (owner.constructor?.synthetic == true) {
            val instructions = mutableListOf<IrInstruction>()
            owner.fields.values.filter { it.ownerName == owner.name && it.type == StringType }.forEach {
                instructions += IrFieldStoreInstruction(receiver, it.name, IrStringConstant(""))
            }
            owner.baseClass?.constructor?.let { base ->
                val parent = IrRegister(0, IrObjectType(base.owner!!.name))
                instructions += IrCastInstruction(parent, receiver)
                instructions += IrCallInstruction(null, base.generatedName, listOf(parent), IrVoid)
            }
            instructions += IrReturnInstruction(null)
            add(IrFunction("${owner.name}_constructor", listOf(receiver), emptyList(), IrVoid,
                instructions, owner.declarationSpan!!.source.path.toAbsolutePath().normalize().toString()))
        }
        for (field in owner.fields.values.filter { it.ownerName == owner.name }) {
            val suffix = field.name.replaceFirstChar { it.uppercaseChar() }
            val type = lowerType(field.type)
            if (owner.methods["get$suffix"]?.synthetic == true) {
                val result = IrRegister(0, type)
                add(IrFunction("${owner.name}_get$suffix", listOf(receiver), emptyList(), type,
                    listOf(IrFieldLoadInstruction(result, receiver, field.name), IrReturnInstruction(result)),
                    owner.declarationSpan!!.source.path.toAbsolutePath().normalize().toString()))
            }
            if (field.mutable && owner.methods["set$suffix"]?.synthetic == true) {
                val value = IrParameter(1, "value", type)
                add(IrFunction("${owner.name}_set$suffix", listOf(receiver, value), emptyList(), IrVoid,
                    listOf(IrFieldStoreInstruction(receiver, field.name, value), IrReturnInstruction(null)),
                    owner.declarationSpan!!.source.path.toAbsolutePath().normalize().toString()))
            }
        }
    }

    private fun lowerFunction(function: BoundFunction): IrFunction {
        val context = FunctionContext()
        context.functionName = function.symbol.name
        context.ownerName = function.symbol.owner?.name
        context.returnType = lowerType(function.symbol.returnType)

        val receiver = function.receiver?.let {
            IrParameter(0, "this", lowerType(it)).also { parameter ->
                context.receiver = parameter
            }
        }
        val parameters = function.parameters.mapIndexed { index, symbol ->
            IrParameter(index + if (receiver == null) 0 else 1, symbol.name, lowerType(symbol.type)).also {
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
            lowerType(function.symbol.returnType),
            context.instructions,
            function.body.span.source.path.toAbsolutePath().normalize().toString()
        )
    }

    private fun lowerStatement(statement: BoundStatement, context: FunctionContext) {
        when (statement) {
            is BoundReturnStatement -> {
                val value = coerce(lowerExpression(statement.expression, context), context.returnType, context)
                context.instructions += IrReturnInstruction(
                    if (value == IrUnitValue) null else value
                )
            }

            is BoundVariableDeclarationStatement -> {
                val local = context.newLocal(
                    statement.symbol,
                    lowerType(statement.symbol.type)
                )
                val initializer = coerce(lowerExpression(statement.initializer, context), local.type, context)
                context.instructions += IrStoreInstruction(local, initializer)
            }

            is BoundAssignmentStatement -> {
                val targetReceiver = (statement.target as? BoundMemberExpression)?.let {
                    lowerExpression(it.receiver, context)
                }
                val value = coerce(lowerExpression(statement.expression, context), lowerType(statement.target.type), context)
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
                            IrFieldStoreInstruction(coerce(receiver, IrObjectType(target.field.ownerName), context), target.field.name, value)
                        } else {
                            val method = target.setter ?: error("missing setter")
                            IrCallInstruction(null, callName(method, target.direct),
                                listOf(coerce(receiver, lowerType(method.owner!!.type), context), value), IrVoid)
                        }
                    }
                    is BoundDereferenceExpression -> {
                        context.instructions += IrPointerStoreInstruction(
                            lowerExpression(target.pointer, context),
                            value
                        )
                    }
                    else -> error("unsupported assignment target")
                }
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
                val end = lowerExpression(statement.end, context)
                val conditionLabel = context.newLabel()
                val bodyLabel = context.newLabel()
                val endLabel = context.newLabel()
                context.instructions += IrLabelInstruction(conditionLabel)
                val current = context.newRegister(IrI32)
                context.instructions += IrLoadInstruction(current, local)
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
                val finished = context.newRegister(IrI32)
                val incrementLabel = context.newLabel()
                context.instructions += IrBinaryInstruction(finished, IrBinaryOperator.EQ_I32, current, end)
                context.instructions += IrBranchInstruction(finished, endLabel, incrementLabel)
                context.instructions += IrLabelInstruction(incrementLabel)
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
        is BoundVoidLiteral -> IrUnitValue
        is BoundStringLiteral -> IrStringConstant(expression.value)
        is BoundNullLiteral -> IrNullConstant(IrPointerType(IrVoid))
        is BoundThisExpression -> coerce(context.receiver ?: error("unmapped method receiver"), lowerType(expression.type), context)

        is BoundNewExpression -> {
            val result = context.newRegister(lowerType(expression.type))
            context.instructions += IrNewObjectInstruction(
                result,
                lowerType(expression.type) as IrObjectType
            )
            expression.constructor?.let { constructor ->
                context.instructions += IrCallInstruction(
                    null,
                    constructor.generatedName,
                    listOf(result) + expression.arguments.zip(constructor.parameters).map { (argument, parameter) ->
                        coerce(lowerExpression(argument, context), lowerType(parameter.type), context)
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
            val result = context.newRegister(lowerType(expression.type))
            val getter = expression.getter ?: error("missing getter")
            context.instructions += if (!expression.safe && context.functionName == getter.name &&
                context.ownerName == expression.field.ownerName) {
                IrFieldLoadInstruction(result, coerce(receiver, IrObjectType(expression.field.ownerName), context), expression.field.name)
            } else {
                IrCallInstruction(result, callName(getter, expression.direct),
                    listOf(coerce(receiver, lowerType(getter.owner!!.type), context)), lowerType(getter.returnType), expression.safe)
            }
            result
        }

        is BoundElvisExpression -> {
            val nullable = lowerExpression(expression.nullable, context)
            val resultType = lowerType(expression.type)
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
                ?: error("unmapped address local")
            val result = context.newRegister(lowerType(expression.type))
            context.instructions += IrAddressInstruction(result, local)
            result
        }

        is BoundPointerWriteExpression -> {
            val pointer = lowerExpression(expression.pointer, context)
            val value = coerce(lowerExpression(expression.value, context), (pointer.type as IrPointerType).pointee, context)
            context.instructions += IrPointerStoreInstruction(pointer, value)
            IrUnitValue
        }

        is BoundDereferenceExpression -> {
            val result = context.newRegister(lowerType(expression.type))
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

        is BoundManagedPointerExpression -> {
            val pointeeType = lowerType(expression.type.pointee)
            val result = context.newRegister(lowerType(expression.type))
            context.instructions += IrManagedPointerInstruction(
                result,
                coerce(lowerExpression(expression.initializer, context), pointeeType, context),
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
                val result = context.newRegister(lowerType(symbol.type))
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
            val returnType = lowerType(expression.type)
            val actualReturnType = lowerType(expression.function.returnType)
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
                    add(coerce(it, lowerType(expression.function.owner!!.type), context))
                }
                addAll(expression.arguments.zip(expression.function.parameters).map { (argument, parameter) ->
                    coerce(lowerExpression(argument, context), lowerType(parameter.type), context)
                })
            }
            val result = if (actualReturnType == IrVoid) null else context.newRegister(actualReturnType)

            context.instructions += IrCallInstruction(
                result,
                callName(expression.function, expression.direct),
                args,
                actualReturnType
            )
            if (expression.safe) {
                if (safeLocal != null) context.instructions += IrStoreInstruction(safeLocal, coerce(result!!, returnType, context))
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

    private fun lowerType(type: RType): IrType = when (type) {
        Int32Type -> IrI32
        BoolType -> IrI32
        UnitType -> IrVoid
        StringType -> IrString
        NullType -> IrPointerType(IrVoid)
        is NullableType -> if (type.underlying == Int32Type || type.underlying == BoolType) IrNullableI32 else lowerType(type.underlying)
        is ManagedPointerType -> IrPointerType(lowerType(type.pointee))
        is ClassType -> when (type.name) {
            "Int", "Int32", "Bool" -> IrI32
            "String" -> IrString
            "Unit", "Void" -> IrVoid
            else -> IrObjectType(type.name)
        }
    }

    private fun coerce(value: IrValue, expected: IrType, context: FunctionContext): IrValue {
        if (expected is IrObjectType && value.type is IrObjectType && expected != value.type) {
            return context.newRegister(expected).also { context.instructions += IrCastInstruction(it, value) }
        }
        if (expected != IrNullableI32 || value.type != IrI32) return value
        return context.newRegister(IrNullableI32).also { context.instructions += IrBoxInstruction(it, value) }
    }

    private fun virtual(function: FunctionSymbol): Boolean = function.owner?.let {
        lowerType(it.type) is IrObjectType && it.name != "Pointer" && function.name != "constructor" &&
            function.visibility != Visibility.PRIVATE && function.builtinTarget == null
    } == true

    private fun callName(function: FunctionSymbol, direct: Boolean = false): String =
        function.builtinTarget ?: if (!direct && virtual(function)) "dispatch_${function.generatedName}" else function.generatedName

    private class FunctionContext {
        var returnType: IrType = IrVoid
        var functionName: String = ""
        var ownerName: String? = null
        val instructions = mutableListOf<IrInstruction>()
        val parameters = mutableMapOf<ValueSymbol, IrParameter>()
        var receiver: IrParameter? = null
        val locals = linkedMapOf<VariableSymbol, IrLocal>()
        private val temporaryLocals = mutableListOf<IrLocal>()
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

        fun newTemporaryLocal(type: IrType): IrLocal =
            IrLocal(nextLocalId++, "temporary", type).also(temporaryLocals::add)

        fun allLocals(): List<IrLocal> = locals.values + temporaryLocals

        fun newLabel(): Int = nextLabelId++
    }
}
