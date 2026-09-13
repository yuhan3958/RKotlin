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
    private fun resolveType(type: TypeReference) = typeResolver.resolveType(type)

    fun registerDeclarations(module: AstModule) {
        registerNativeTypes(module)
        registerClasses(module)
        registerFunctions(module)
    }

    fun complete(module: AstModule) {
        registerAccessors()
        SemanticInheritance(context, typeResolver).registerInheritance(module)
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
            }
            val name = when (declaration) {
                is ClassDeclaration -> declaration.name
                is ObjectDeclaration -> declaration.name
                else -> error("not a type declaration")
            }
            val type = ClassType(name, declaration is ObjectDeclaration,
                (declaration as? ClassDeclaration)?.typeParameters.orEmpty().map {
                    ClassType(it, typeParameter = true)
                })
            val visibility = when (declaration) {
                is ClassDeclaration -> declaration.visibility
                is ObjectDeclaration -> declaration.visibility
                else -> Visibility.PUBLIC
            }
            val symbol = ClassSymbol(
                name,
                type,
                visibility = visibility,
                declarationSpan = declaration.span,
                isInterface = declaration is ClassDeclaration && declaration.isInterface,
                typeParameters = (declaration as? ClassDeclaration)?.typeParameters.orEmpty()
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
        typeParameters = owner.typeParameters.toSet()
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

    private fun registerFunction(
        declaration: FunctionDeclaration,
        owner: ClassSymbol?
    ) {
        typeParameters = owner?.typeParameters?.toSet().orEmpty()
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
