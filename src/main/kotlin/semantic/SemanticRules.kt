package me.rkt.semantic

import me.rkt.ast.*
import me.rkt.source.SourceSpan

/** Shared access, compatibility, and generic substitution rules; owns no body state. */
internal class SemanticRules(
    private val context: SemanticContext,
    private val typeResolver: SemanticTypeResolver
) {
    private val diagnostics get() = context.diagnostics
    private val classes get() = context.classes
    private fun unwrapNullable(type: RType) = typeResolver.unwrapNullable(type)

    private fun isSubclass(actual: ClassSymbol?, expected: ClassSymbol): Boolean {
        var current = actual
        while (current != null) {
            if (current === expected) return true
            current = current.baseClass
        }
        return false
    }

    fun checkAccess(symbol: FunctionSymbol, owner: ClassSymbol?, span: SourceSpan) {
        val declaring = symbol.owner ?: return
        val allowed = when (symbol.visibility) {
            Visibility.PUBLIC -> true
            Visibility.PRIVATE -> owner === declaring
            Visibility.PROTECTED -> isSubclass(owner, declaring)
        }
        if (!allowed) diagnostics.fail(span, "'${symbol.name}' is ${symbol.visibility.name.lowercase()} in '${declaring.name}'")
    }

    fun member(receiver: BoundExpression, field: FieldSymbol, span: SourceSpan, owner: ClassSymbol?, safe: Boolean = false): BoundMemberExpression {
        val receiverClass = classes.getValue((unwrapNullable(receiver.type) as ClassType).name)
        val suffix = field.name.replaceFirstChar { it.uppercaseChar() }
        val getter = specializeGenericFunction(receiverClass.methods.getValue("get$suffix"), receiver)
        checkAccess(getter, owner, span)
        checkCallable(getter, span)
        val resolvedField = field.copy(type = getter.returnType)
        return BoundMemberExpression(receiver, resolvedField, span, if (safe) nullable(resolvedField.type) else resolvedField.type,
            safe, getter, receiverClass.methods["set$suffix"]?.let { specializeGenericFunction(it, receiver) })
    }

    fun nullable(type: RType): RType = if (type is NullableType) type else NullableType(type)
    fun requireInt32(expression: BoundExpression, span: SourceSpan) {
        if (expression.type != Int32Type && expression.type != BoolType) {
            diagnostics.fail(span, "condition requires Bool or Int32")
        }
    }

    fun specializeGenericFunction(
        function: FunctionSymbol,
        receiver: BoundExpression?
    ): FunctionSymbol {
        val receiverType = receiver?.type?.let(::unwrapNullable) as? ClassType ?: return function
        return specializeGenericFunction(function, receiverType)
    }

    fun specializeGenericFunction(
        function: FunctionSymbol,
        receiverType: ClassType
    ): FunctionSymbol {
        val declaring = function.owner ?: return function
        val view = TypeSubstitution.viewAs(receiverType, declaring, classes) ?: return function
        val substitutions = declaring.typeParameters.zip(view.typeArguments).toMap()
        return WildcardProjection.function(function, substitutions)
    }

    fun checkCallable(function: FunctionSymbol, span: SourceSpan) {
        if (function.unavailableParameters.isNotEmpty()) {
            diagnostics.fail(span, "cannot pass values to '${function.name}': its parameter type is hidden by '*'")
        }
        if (WildcardProjection.unreadable(function.returnType)) {
            diagnostics.fail(span, "cannot read '${function.name}': its value type is hidden by '*'")
        }
    }

    fun isAssignable(expected: RType, actual: RType): Boolean {
        if (expected == WildcardType || actual == WildcardType) return false
        if (expected == actual) return true
        if (expected is NullableType) return actual == NullType ||
            isAssignable(expected.underlying, unwrapNullable(actual))
        if (expected is ManagedPointerType && actual is ManagedPointerType) {
            if (expected.kind != actual.kind) return false
            if (expected.pointee == WildcardType) return true
            if (actual.pointee == WildcardType) return false
            return expected.pointee == actual.pointee
        }
        if (expected is ClassType && actual is ClassType) {
            if (expected.typeParameter || actual.typeParameter) return false
            val expectedSymbol = classes[expected.name] ?: return false
            val view = TypeSubstitution.viewAs(actual, expectedSymbol, classes) ?: return false
            return WildcardProjection.accepts(expected, view)
        }
        return false
    }

    fun checkArguments(
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
