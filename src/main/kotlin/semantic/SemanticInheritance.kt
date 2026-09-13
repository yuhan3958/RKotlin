package me.rkt.semantic

import me.rkt.ast.*

/** Links the class hierarchy and validates superclass and interface contracts. */
internal class SemanticInheritance(
    private val context: SemanticContext,
    private val typeResolver: SemanticTypeResolver
) {
    private val diagnostics get() = context.diagnostics
    private val classes get() = context.classes
    private val nativeTypes get() = context.nativeTypes
    private var typeParameters
        get() = context.typeParameters
        set(value) { context.typeParameters = value }
    private fun resolveClassType(type: TypeReference) = typeResolver.resolveClassType(type)

    fun registerInheritance(module: AstModule) {
        for (declaration in module.declarations.filterIsInstance<ClassDeclaration>()) {
            val owner = classes.getValue(declaration.name)
            typeParameters = owner.typeParameters.toSet()
            declaration.baseType?.let { baseType ->
                val base = classes[baseType.name] ?: diagnostics.fail(baseType.span, "unknown superclass '${baseType.name}'")
                val resolvedBase = resolveClassType(baseType)
                if (base.isInterface) {
                    owner.interfaces += base
                    owner.interfaceTypes += resolvedBase
                } else {
                    val scalarAlias = owner.name == "Int" && base.name == "Int32"
                    if (baseType.nullable || baseType.arguments.isNotEmpty() || base.type.objectLike ||
                        (!scalarAlias && base.name != "Type" && (base.name in nativeTypes || owner.name in nativeTypes)) ||
                        base.name == "Pointer" || (owner.name == "Pointer" && base.name != "Type")) {
                        diagnostics.fail(baseType.span, "superclass must be an ordinary non-generic class")
                    }
                    owner.baseClass = base
                }
            }
            for (interfaceType in declaration.interfaceTypes) {
                val resolvedInterface = resolveClassType(interfaceType)
                val interfaceSymbol = classes[interfaceType.name]
                    ?: diagnostics.fail(interfaceType.span, "unknown interface '${interfaceType.name}'")
                if (!interfaceSymbol.isInterface) {
                    diagnostics.fail(interfaceType.span, "'${interfaceSymbol.name}' is not an interface")
                }
                owner.interfaces += interfaceSymbol
                owner.interfaceTypes += resolvedInterface
            }
            typeParameters = emptySet()
        }
        val root = classes["Type"]
        if (root != null) {
            classes.values
                .filter { it !== root && it.baseClass == null }
                .forEach { it.baseClass = root }
        }
        val visiting = mutableSetOf<String>()
        val complete = mutableSetOf<String>()
        fun inherit(owner: ClassSymbol) {
            if (owner.name in complete) return
            if (!visiting.add(owner.name)) diagnostics.fail(owner.declarationSpan!!, "cyclic inheritance involving '${owner.name}'")
            owner.interfaces.forEach(::inherit)
            owner.baseClass?.let { base ->
                inherit(base)
                for ((name, field) in base.fields) {
                    if (name in owner.fields) diagnostics.fail(owner.declarationSpan!!, "inherited field '$name' cannot be redeclared")
                    owner.fields[name] = field
                }
                for ((name, inherited) in base.methods) {
                    val method = owner.methods[name]
                    if (method == null) {
                        owner.methods[name] = inherited
                    } else {
                        if (inherited.visibility == Visibility.PRIVATE || !method.overriding) {
                            diagnostics.fail(method.declarationSpan ?: owner.declarationSpan!!, "method '$name' requires an accessible superclass method and override")
                        }
                        if (method.parameters.map { it.type } != inherited.parameters.map { it.type } ||
                            !isCovariantOverrideReturn(method.returnType, inherited.returnType)) {
                            diagnostics.fail(method.declarationSpan!!, "override '$name' must preserve the method signature")
                        }
                        if (method.visibility.ordinal > inherited.visibility.ordinal) {
                            diagnostics.fail(method.declarationSpan!!, "override '$name' cannot reduce visibility")
                        }
                    }
                }
                if (owner.constructor?.synthetic == true && (base.constructor?.parameters?.isNotEmpty() == true ||
                        base.constructor?.visibility == Visibility.PRIVATE)) {
                    diagnostics.fail(owner.declarationSpan!!, "declare a constructor calling an accessible superclass constructor")
                }
            }
            for (method in owner.methods.values.filter { it.owner === owner && it.overriding }) {
                val overridesInterface = owner.interfaces.any { interfaceSymbol ->
                    interfaceSymbol.methods[method.name]?.let { required ->
                        required.owner === interfaceSymbol && required.visibility != Visibility.PRIVATE
                    } == true
                }
                if (owner.baseClass?.methods?.containsKey(method.name) != true && !overridesInterface) {
                    diagnostics.fail(method.declarationSpan!!, "nothing to override: '${method.name}'")
                }
            }
            visiting.remove(owner.name)
            complete.add(owner.name)
        }
        classes.values.forEach(::inherit)
        classes.values.filterNot { it.isInterface }.forEach { owner ->
            owner.interfaces.forEach { interfaceSymbol ->
                interfaceSymbol.methods.values
                    .filter { it.owner === interfaceSymbol }
                    .forEach { declaration ->
                        val required = SemanticRules(context, typeResolver)
                            .specializeGenericFunction(declaration, owner.type)
                        val implementation = owner.methods[required.name]
                        if (implementation == null ||
                            implementation.parameters.map { it.type } != required.parameters.map { it.type } ||
                            !isCovariantOverrideReturn(implementation.returnType, required.returnType) ||
                            implementation.visibility.ordinal > required.visibility.ordinal
                        ) {
                            diagnostics.fail(
                                owner.declarationSpan!!,
                                "class '${owner.name}' does not implement interface method '${required.name}'"
                            )
                        }
                    }
            }
        }
    }

    private fun isCovariantOverrideReturn(actual: RType, inherited: RType): Boolean {
        if (actual == inherited) return true
        return inherited is ManagedPointerType &&
            inherited.kind == ManagedPointerKind.POINTER &&
            inherited.pointee == WildcardType &&
            actual is ManagedPointerType
    }


}
