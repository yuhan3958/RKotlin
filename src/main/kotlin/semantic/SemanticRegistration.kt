package me.rkt.semantic

import me.rkt.ast.*

class SemanticRegistration(
    private val context: SemanticContext,
    private val typeResolver: SemanticTypeResolver
) {
    private val diagnostics get() = context.diagnostics
    private val globalScope get() = context.globalScope
    private val classes get() = context.classes
    private val functions get() = context.functions
    private val constructors get() = context.constructors
    private val nativeTypes get() = context.nativeTypes
    private val overloads get() = context.overloads
    private var typeParameters
        get() = context.typeParameters
        set(value) { context.typeParameters = value }
    private val libraryRoot get() = context.libraryRoot
    private fun resolveClassType(type: TypeReference) = typeResolver.resolveClassType(type)
    private fun resolveType(type: TypeReference) = typeResolver.resolveType(type)

    fun registerDeclarations(module: AstModule) {
        registerNativeTypes(module)
        registerClasses(module)
        registerFunctions(module)
    }

    fun complete(module: AstModule) {
        registerAccessors()
        registerInheritance(module)
    }

    private fun registerNativeTypes(module: AstModule) {
        module.declarations.filterIsInstance<ClassDeclaration>().forEach { declaration ->
            val type = when (declaration.name) {
                "Int32", "Int" -> Int32Type
                "Bool" -> BoolType
                "String" -> StringType
                "Void", "Unit" -> UnitType
                else -> null
            }
            if (type != null) {
                if (!declaration.span.source.path.toAbsolutePath().normalize().startsWith(libraryRoot)) {
                    diagnostics.fail(declaration.span, "standard type '${declaration.name}' cannot be redefined")
                }
                nativeTypes[declaration.name] = type
            }
        }
    }

    private fun registerClasses(module: AstModule) {
        val declarations = module.declarations.filter {
            it is ClassDeclaration || it is ObjectDeclaration
        }
        for (declaration in declarations) {
            if (declaration is ClassDeclaration) {
                if (declaration.name == "Pointer" && !declaration.span.source.path.toAbsolutePath().normalize().startsWith(libraryRoot)) {
                    diagnostics.fail(declaration.span, "Pointer is defined by the standard library")
                }
                if (declaration.typeParameters.isNotEmpty() &&
                    declaration.name !in setOf("Pointer", "BufferPointer", "Array")) {
                    diagnostics.fail(declaration.span, "generic class declarations are not yet supported")
                }
            }
            val name = when (declaration) {
                is ClassDeclaration -> declaration.name
                is ObjectDeclaration -> declaration.name
                else -> error("not a type declaration")
            }
            val type = ClassType(name, declaration is ObjectDeclaration)
            val visibility = when (declaration) {
                is ClassDeclaration -> declaration.visibility
                is ObjectDeclaration -> declaration.visibility
                else -> Visibility.PUBLIC
            }
            val symbol = ClassSymbol(
                name,
                type,
                visibility = visibility,
                declarationSpan = declaration.span
            )
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
                else -> error("not a type declaration")
            }
            typeParameters = if (declaration is ClassDeclaration) {
                declaration.typeParameters.toSet()
            } else {
                emptySet()
            }
            for (member in members) {
                if (member is FieldDeclaration) {
                    val field = FieldSymbol(
                        member.name,
                        resolveType(member.type),
                        member.mutable,
                        member.visibility,
                        classSymbol.name
                    )
                    if (field.type == UnitType) diagnostics.fail(member.span, "a field requires a value type")
                    if (classSymbol.fields.putIfAbsent(member.name, field) != null) {
                        diagnostics.error(member.span, "duplicate field '${member.name}'")
                    }
                }
            }
            typeParameters = emptySet()
        }
    }

    private fun registerFunctions(module: AstModule) {
        for (declaration in module.declarations) {
            when (declaration) {
                is FunctionDeclaration -> registerFunction(declaration, null)
                is ClassDeclaration -> declaration.members.forEach { member ->
                    when (member) {
                        is FunctionDeclaration -> registerFunction(member, classes[declaration.name])
                        is ConstructorDeclaration -> registerConstructor(member, classes.getValue(declaration.name))
                        is FieldDeclaration -> Unit
                    }
                }
                is ObjectDeclaration -> declaration.members
                    .filterIsInstance<FunctionDeclaration>()
                    .forEach { registerFunction(it, classes[declaration.name]) }
                is ImportDeclaration -> Unit
                is NativeTypeDeclaration -> Unit
            }
        }
    }

    private fun registerAccessors() {
        for (owner in classes.values) {
            if (owner.constructor == null && !owner.type.objectLike && owner.name !in nativeTypes &&
                owner.name !in setOf("Pointer", "BufferPointer")) {
                if (owner.fields.values.any { it.type is ClassType || it.type is ManagedPointerType }) {
                    diagnostics.fail(owner.declarationSpan!!, "non-null object fields require an explicit constructor")
                }
                owner.constructor = FunctionSymbol("constructor", emptyList(), UnitType,
                    owner = owner, synthetic = true)
            }
            for (field in owner.fields.values) {
                val suffix = field.name.replaceFirstChar { it.uppercaseChar() }
                val getter = FunctionSymbol("get$suffix", emptyList(), field.type,
                    owner = owner, visibility = field.visibility, synthetic = true)
                val setter = FunctionSymbol("set$suffix", listOf(ParameterSymbol("value", field.type)), UnitType,
                    owner = owner, visibility = field.visibility, synthetic = true)
                for (accessor in if (field.mutable) listOf(getter, setter) else listOf(getter)) {
                    val existing = owner.methods.putIfAbsent(accessor.name, accessor)
                    if (existing != null && (existing.returnType != accessor.returnType ||
                            existing.parameters.map { it.type } != accessor.parameters.map { it.type })) {
                        diagnostics.fail(owner.declarationSpan!!, "invalid accessor '${accessor.name}' signature")
                    }
                }
            }
        }
    }

    private fun registerConstructor(
        declaration: ConstructorDeclaration,
        owner: ClassSymbol
    ) {
        if (owner.constructor != null) {
            diagnostics.error(declaration.span, "duplicate constructor '${owner.name}'")
            return
        }
        typeParameters = if (owner.name in setOf("Pointer", "BufferPointer", "Array")) {
            setOf("T")
        } else {
            emptySet()
        }
        val symbol = FunctionSymbol(
            "constructor",
            declaration.parameters.map { ParameterSymbol(it.name, resolveType(it.type)) },
            UnitType,
            owner = owner,
            visibility = declaration.visibility,
            declarationSpan = declaration.span
        )
        typeParameters = emptySet()
        if (symbol.parameters.any { it.type == UnitType }) diagnostics.fail(declaration.span, "a parameter requires a value type")
        owner.constructor = symbol
        constructors[declaration] = symbol
    }

    private fun registerInheritance(module: AstModule) {
        for (declaration in module.declarations.filterIsInstance<ClassDeclaration>()) {
            val baseType = declaration.baseType ?: continue
            val owner = classes.getValue(declaration.name)
            val base = classes[baseType.name] ?: diagnostics.fail(baseType.span, "unknown superclass '${baseType.name}'")
            resolveClassType(baseType)
            val scalarAlias = owner.name == "Int" && base.name == "Int32"
            if (baseType.nullable || baseType.arguments.isNotEmpty() || base.type.objectLike ||
                (!scalarAlias && base.name != "Type" && (base.name in nativeTypes || owner.name in nativeTypes)) ||
                base.name == "Pointer" || (owner.name == "Pointer" && base.name != "Type")) {
                diagnostics.fail(baseType.span, "superclass must be an ordinary non-generic class")
            }
            owner.baseClass = base
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
                if (owner.baseClass?.methods?.containsKey(method.name) != true) {
                    diagnostics.fail(method.declarationSpan!!, "nothing to override: '${method.name}'")
                }
            }
            visiting.remove(owner.name)
            complete.add(owner.name)
        }
        classes.values.forEach(::inherit)
    }

    private fun isCovariantOverrideReturn(actual: RType, inherited: RType): Boolean {
        if (actual == inherited) return true
        return inherited is ManagedPointerType &&
            inherited.kind == ManagedPointerKind.POINTER &&
            inherited.pointee == PointerWildcardType &&
            actual is ManagedPointerType
    }


    private fun registerFunction(
        declaration: FunctionDeclaration,
        owner: ClassSymbol?
    ) {
        typeParameters = if (owner?.name in setOf("Pointer", "BufferPointer", "Array")) setOf("T") else emptySet()
        declaration.nativeTarget?.let { target ->
            if (!declaration.span.source.path.toAbsolutePath().normalize().startsWith(libraryRoot)) {
                diagnostics.fail(declaration.span, "intrinsics are restricted to the standard library")
            }
            if (target !in Intrinsics.targets) {
                diagnostics.fail(declaration.span, "unknown intrinsic '$target'")
            }
        }
        val parameterSymbols = declaration.parameters.map {
            ParameterSymbol(it.name, resolveType(it.type))
        }
        if (parameterSymbols.any { it.type == UnitType }) diagnostics.fail(declaration.span, "a parameter requires a value type")
        val symbol = FunctionSymbol(
            declaration.name,
            parameterSymbols,
            resolveType(declaration.returnType),
            builtinTarget = declaration.nativeTarget,
            owner = owner,
            visibility = declaration.visibility,
            declarationSpan = declaration.span,
            overriding = declaration.overriding
        )
        if (owner == null) {
            val candidates = overloads.getOrPut(symbol.name) { mutableListOf() }
            if (candidates.any { it.parameters.map { p -> p.type } == parameterSymbols.map { p -> p.type } }) {
                diagnostics.error(declaration.span, "duplicate function '${declaration.name}'")
            }
            if (candidates.isEmpty() && !globalScope.define(symbol)) {
                diagnostics.error(declaration.span, "duplicate declaration '${declaration.name}'")
            }
            candidates += symbol
        } else if (owner.methods.putIfAbsent(declaration.name, symbol) != null) {
            diagnostics.error(declaration.span, "duplicate function '${declaration.name}'")
        }
        functions[declaration] = symbol
        typeParameters = emptySet()
    }
}
