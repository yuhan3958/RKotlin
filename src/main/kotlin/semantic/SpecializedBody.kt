package me.rkt.semantic

/** Copies one bound body with concrete types while preserving local symbol identity. */
internal class SpecializedBody(
    private val specialization: GenericSpecialization,
    private val arguments: Map<String, RType>
) {
    private val symbols = java.util.IdentityHashMap<ValueSymbol, ValueSymbol>()
    private fun type(value: RType) = specialization.type(value, arguments)
    private fun symbol(value: ValueSymbol): ValueSymbol = symbols.getOrPut(value) {
        when (value) {
            is VariableSymbol -> VariableSymbol(value.name, type(value.type), value.mutable)
            is ParameterSymbol -> value.copy(type = type(value.type))
            is FieldSymbol -> error("unqualified field in bound body")
        }
    }

    fun rewrite(body: BoundFunction, target: FunctionSymbol): BoundFunction {
        body.parameters.zip(target.parameters).forEach { (old, new) -> symbols[old] = new }
        return BoundFunction(target, statement(body.body) as BoundBlockStatement, target.parameters, target.owner?.type)
    }

    private fun statement(value: BoundStatement): BoundStatement = when (value) {
        is BoundBlockStatement -> value.copy(statements = value.statements.map(::statement))
        is BoundReturnStatement -> value.copy(expression = expression(value.expression))
        is BoundVariableDeclarationStatement -> value.copy(symbol = symbol(value.symbol) as VariableSymbol, initializer = expression(value.initializer))
        is BoundAssignmentStatement -> value.copy(target = expression(value.target), expression = expression(value.expression))
        is BoundExpressionStatement -> value.copy(expression = expression(value.expression))
        is BoundIfStatement -> value.copy(condition = expression(value.condition),
            thenBranch = statement(value.thenBranch) as BoundBlockStatement,
            elseBranch = value.elseBranch?.let { statement(it) as BoundBlockStatement })
        is BoundWhileStatement -> value.copy(condition = expression(value.condition), body = statement(value.body) as BoundBlockStatement)
        is BoundForStatement -> value.copy(symbol = symbol(value.symbol) as VariableSymbol,
            start = expression(value.start), end = expression(value.end), body = statement(value.body) as BoundBlockStatement,
            iterable = value.iterable?.let(::expression), indexSymbol = value.indexSymbol?.let { symbol(it) as VariableSymbol },
            elementAccess = value.elementAccess?.let(::expression), iteratorCondition = value.iteratorCondition?.let(::expression),
            iteratorSymbol = value.iteratorSymbol?.let { symbol(it) as VariableSymbol },
            iteratorInitializer = value.iteratorInitializer?.let(::expression))
    }

    private fun expression(value: BoundExpression): BoundExpression = when (value) {
        is BoundVoidLiteral -> value
        is BoundIntLiteral -> value.copy(type = type(value.type))
        is BoundStringLiteral -> value
        is BoundNullLiteral -> value
        is BoundNameExpression -> value.copy(symbol = symbol(value.symbol), type = type(value.type))
        is BoundThisExpression -> value.copy(symbol = symbol(value.symbol) as ParameterSymbol, type = type(value.type))
        is BoundNewExpression -> value.copy(classType = type(value.classType) as ClassType, type = type(value.type),
            arguments = value.arguments.map(::expression), constructor = value.constructor?.let {
                specialization.function(it, value.classType, arguments)
            })
        is BoundObjectReference -> value.copy(classType = type(value.classType) as ClassType, type = type(value.type))
        is BoundMemberExpression -> value.copy(receiver = expression(value.receiver),
            field = specialization.field(value.field, value.receiver.type, arguments), type = type(value.type),
            getter = value.getter?.let { specialization.function(it, value.receiver.type, arguments) },
            setter = value.setter?.let { specialization.function(it, value.receiver.type, arguments) })
        is BoundElvisExpression -> value.copy(nullable = expression(value.nullable), fallback = expression(value.fallback), type = type(value.type))
        is BoundDereferenceExpression -> value.copy(pointer = expression(value.pointer), type = type(value.type))
        is BoundAddressExpression -> value.copy(value = expression(value.value) as BoundNameExpression, type = type(value.type))
        is BoundPointerWriteExpression -> value.copy(pointer = expression(value.pointer), value = expression(value.value), type = type(value.type))
        is BoundPointerAddExpression -> value.copy(pointer = expression(value.pointer), offset = expression(value.offset), type = type(value.type) as ManagedPointerType)
        is BoundBufferGetExpression -> value.copy(pointer = expression(value.pointer), index = expression(value.index), type = type(value.type))
        is BoundBufferSetExpression -> value.copy(pointer = expression(value.pointer), index = expression(value.index), value = expression(value.value), type = type(value.type))
        is BoundBufferLengthExpression -> value.copy(pointer = expression(value.pointer))
        is BoundBufferAllocationExpression -> value.copy(length = expression(value.length), type = type(value.type) as ManagedPointerType)
        is BoundArrayLiteral -> value.copy(values = value.values.map(::expression), elementType = type(value.elementType), type = type(value.type) as ClassType)
        is BoundFreeExpression -> value.copy(pointer = expression(value.pointer))
        is BoundTypeIsFreedExpression -> value.copy(value = expression(value.value))
        is BoundManagedPointerExpression -> value.copy(initializer = expression(value.initializer), type = type(value.type) as ManagedPointerType)
        is BoundBinaryExpression -> value.copy(left = expression(value.left), right = expression(value.right), type = type(value.type))
        is BoundCallExpression -> value.copy(function = specialization.function(value.function, value.receiver?.type, arguments),
            arguments = value.arguments.map(::expression), type = type(value.type), receiver = value.receiver?.let(::expression))
    }
}
