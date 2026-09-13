package me.rkt.semantic

import me.rkt.ast.*
import me.rkt.diagnostic.DiagnosticReporter
import me.rkt.source.SourceSpan
import java.nio.file.Path

class SemanticAnalyzer(
    private val diagnostics: DiagnosticReporter
) {
    private val globalScope = Scope()
    private val classes = linkedMapOf<String, ClassSymbol>()
    private val functions = linkedMapOf<FunctionDeclaration, FunctionSymbol>()
    private val constructors = linkedMapOf<ConstructorDeclaration, FunctionSymbol>()
    private val nativeTypes = linkedMapOf<String, RType>()
    private val overloads = linkedMapOf<String, MutableList<FunctionSymbol>>()
    private var typeParameters: Set<String> = emptySet()
    private var constructing: ClassSymbol? = null
    private val initializedFields = mutableSetOf<String>()
    private var blockDepth = 0
    private val libraryRoot = Path.of("src", "main", "rk", "rkotlin").toAbsolutePath().normalize()

    fun analyze(module: AstModule): SemanticModule {
        registerNativeTypes(module)
        registerClasses(module)
        registerFunctions(module)
        diagnostics.throwIfErrors()
        registerAccessors()
        registerInheritance(module)

        val boundFunctions = module.declarations
            .flatMap { declaration ->
                when (declaration) {
                    is FunctionDeclaration -> declaration.body?.let { listOf(bindFunction(declaration)) } ?: emptyList()
                    is ClassDeclaration -> declaration.members.mapNotNull { member ->
                        when (member) {
                            is FunctionDeclaration -> member.body?.let { bindFunction(member) }
                            is ConstructorDeclaration -> bindConstructor(member)
                            is FieldDeclaration -> null
                        }
                    }
                    is ObjectDeclaration -> declaration.members
                        .filterIsInstance<FunctionDeclaration>()
                        .filter { it.body != null }
                        .map(::bindFunction)
                    is ImportDeclaration -> emptyList()
                    is NativeTypeDeclaration -> emptyList()
                }
            }

        diagnostics.throwIfErrors()
        return SemanticModule(boundFunctions, classes.values.toList(), module.imports)
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
                    declaration.name !in setOf("Pointer", "BufferPointer")) {
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
        val symbol = FunctionSymbol(
            "constructor",
            declaration.parameters.map { ParameterSymbol(it.name, resolveType(it.type)) },
            UnitType,
            owner = owner,
            visibility = declaration.visibility,
            declarationSpan = declaration.span
        )
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
                        if (method.parameters.map { it.type } != inherited.parameters.map { it.type } || method.returnType != inherited.returnType) {
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

    private fun isSubclass(actual: ClassSymbol?, expected: ClassSymbol): Boolean {
        var current = actual
        while (current != null) {
            if (current === expected) return true
            current = current.baseClass
        }
        return false
    }

    private fun checkAccess(symbol: FunctionSymbol, owner: ClassSymbol?, span: SourceSpan) {
        val declaring = symbol.owner ?: return
        val allowed = when (symbol.visibility) {
            Visibility.PUBLIC -> true
            Visibility.PRIVATE -> owner === declaring
            Visibility.PROTECTED -> isSubclass(owner, declaring)
        }
        if (!allowed) diagnostics.fail(span, "'${symbol.name}' is ${symbol.visibility.name.lowercase()} in '${declaring.name}'")
    }

    private fun member(receiver: BoundExpression, field: FieldSymbol, span: SourceSpan, owner: ClassSymbol?, safe: Boolean = false): BoundMemberExpression {
        val receiverClass = classes.getValue((unwrapNullable(receiver.type) as ClassType).name)
        val suffix = field.name.replaceFirstChar { it.uppercaseChar() }
        val getter = receiverClass.methods.getValue("get$suffix")
        checkAccess(getter, owner, span)
        return BoundMemberExpression(receiver, field, span, if (safe) nullable(field.type) else field.type,
            safe, getter, receiverClass.methods["set$suffix"])
    }

    private fun nullable(type: RType): RType = if (type is NullableType) type else NullableType(type)

    private fun registerFunction(
        declaration: FunctionDeclaration,
        owner: ClassSymbol?
    ) {
        typeParameters = if (owner?.name in setOf("Pointer", "BufferPointer")) setOf("T") else emptySet()
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

    private fun bindFunction(declaration: FunctionDeclaration): BoundFunction {
        val function = functions.getValue(declaration)
        val scope = Scope(globalScope)
        val owner = function.owner

        if (owner != null) {
            scope.define(ParameterSymbol("this", nativeTypes[owner.name] ?: owner.type))
        }
        for (parameter in function.parameters) {
            if (!scope.define(parameter)) {
                diagnostics.error(
                    declaration.span,
                    "duplicate parameter '${parameter.name}'"
                )
            }
        }

        val body = bindBlock(declaration.body ?: error("native function has no body"), scope, function.returnType, owner)
        if (function.returnType != UnitType && !alwaysReturns(body)) {
            diagnostics.fail(declaration.span, "function '${function.name}' must return a value on every path")
        }
        return BoundFunction(function, body, function.parameters, owner?.type)
    }

    private fun alwaysReturns(statement: BoundStatement): Boolean = when (statement) {
        is BoundReturnStatement -> true
        is BoundBlockStatement -> statement.statements.any(::alwaysReturns)
        is BoundIfStatement -> alwaysReturns(statement.thenBranch) && statement.elseBranch?.let(::alwaysReturns) == true
        else -> false
    }

    private fun bindConstructor(declaration: ConstructorDeclaration): BoundFunction {
        val function = constructors.getValue(declaration)
        val owner = function.owner ?: error("constructor without class")
        val scope = Scope(globalScope)
        scope.define(ParameterSymbol("this", owner.type))
        function.parameters.forEach { parameter ->
            if (!scope.define(parameter)) {
                diagnostics.error(declaration.span, "duplicate parameter '${parameter.name}'")
            }
        }
        constructing = owner
        initializedFields.clear()
        val first = (declaration.body.statements.firstOrNull() as? ExpressionStatement)?.expression as? CallExpression
        val explicitSuper = (first?.target as? NameExpression)?.name == "super"
        val prefix = mutableListOf<BoundStatement>()
        owner.baseClass?.let { base ->
            val constructor = base.constructor ?: error("missing superclass constructor")
            checkAccess(constructor, owner, declaration.span)
            val arguments = if (explicitSuper) first!!.arguments.map { bindExpression(it, scope, owner) } else emptyList()
            checkArguments(base.name, arguments, constructor.parameters, declaration.span)
            prefix += BoundExpressionStatement(BoundCallExpression(constructor, arguments, declaration.span,
                receiver = BoundThisExpression(ParameterSymbol("this", base.type), declaration.span), direct = true), declaration.span)
        }
        if (explicitSuper && owner.baseClass == null) diagnostics.fail(first!!.span, "super requires a superclass")
        val statements = if (explicitSuper) declaration.body.statements.drop(1) else declaration.body.statements
        val body = bindBlock(declaration.body.copy(statements = statements), scope, UnitType, owner)
        for (field in owner.fields.values.filter { it.ownerName == owner.name }) {
            if ((field.type is ClassType || field.type is ManagedPointerType) && field.name !in initializedFields) {
                diagnostics.fail(declaration.span, "constructor must initialize non-null field '${field.name}'")
            }
        }
        constructing = null
        initializedFields.clear()
        return BoundFunction(function, body.copy(statements = prefix + body.statements), function.parameters, owner.type)
    }

    private fun bindBlock(
        block: BlockStatement,
        scope: Scope,
        expectedReturnType: RType,
        owner: ClassSymbol? = null
    ): BoundBlockStatement {
        blockDepth++
        val statements = block.statements.map {
            bindStatement(it, scope, expectedReturnType, owner)
        }
        blockDepth--
        return BoundBlockStatement(statements, block.span)
    }

    private fun bindStatement(
        statement: Statement,
        scope: Scope,
        expectedReturnType: RType,
        owner: ClassSymbol?
    ): BoundStatement = when (statement) {
        is ReturnStatement -> {
            val expression = bindExpression(statement.expression, scope, owner)
            if (!isAssignable(expectedReturnType, expression.type)) {
                diagnostics.error(
                    statement.span,
                    "return type mismatch: expected ${expectedReturnType.displayName}, " +
                        "found ${expression.type.displayName}"
                )
            }
            BoundReturnStatement(expression, statement.span)
        }

        is VariableDeclarationStatement -> {
            val initializer = bindExpression(statement.initializer, scope, owner)
            val type = statement.type?.let(::resolveType) ?: initializer.type
            if (type == NullType || type == UnitType) {
                diagnostics.fail(statement.span, "variable requires a concrete value type")
            }
            if (!isAssignable(type, initializer.type)) {
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
            val target = bindExpression(statement.target, scope, owner)
            if (target is BoundMemberExpression) {
                if (target.safe) diagnostics.fail(statement.span, "safe access is not an assignment target")
                target.setter?.let { checkAccess(it, owner, statement.span) }
            }
            val mutable = when (target) {
                is BoundNameExpression -> target.symbol.mutable
                is BoundMemberExpression -> target.field.mutable || (
                    constructing === owner && owner != null && blockDepth == 1 &&
                        target.field.ownerName == owner.name &&
                        (target.receiver is BoundThisExpression ||
                            (target.receiver as? BoundNameExpression)?.symbol?.name == "this") &&
                        initializedFields.add(target.field.name))
                is BoundDereferenceExpression -> true
                is BoundBufferGetExpression -> {
                    val pointerType = target.pointer.type as? ManagedPointerType
                        ?: diagnostics.fail(statement.target.span, "invalid buffer index target")
                    pointerType.writable
                }
                else -> diagnostics.fail(statement.target.span, "assignment target is not writable")
            }
            if (!mutable) {
                diagnostics.fail(statement.target.span, "cannot assign to immutable value")
            }
            val expression = bindExpression(statement.expression, scope, owner)
            if (!isAssignable(target.type, expression.type)) {
                diagnostics.error(
                    statement.expression.span,
                    "assignment type mismatch: expected ${target.type.displayName}, " +
                        "found ${expression.type.displayName}"
                )
            }
            if (target is BoundMemberExpression && constructing === owner && owner != null && blockDepth == 1 &&
                target.field.ownerName == owner.name && (target.receiver is BoundThisExpression ||
                    (target.receiver as? BoundNameExpression)?.symbol?.name == "this")) {
                initializedFields.add(target.field.name)
            }
            BoundAssignmentStatement(target, expression, statement.span)
        }

        is ExpressionStatement -> BoundExpressionStatement(
            bindExpression(statement.expression, scope, owner),
            statement.span
        )

        is IfStatement -> {
            val condition = bindCondition(statement.condition, scope, owner)
            val thenBranch = bindBlock(
                statement.thenBranch,
                Scope(scope),
                expectedReturnType,
                owner
            )
            val elseBranch = statement.elseBranch?.let {
                bindBlock(it, Scope(scope), expectedReturnType, owner)
            }
            BoundIfStatement(condition, thenBranch, elseBranch, statement.span)
        }

        is WhileStatement -> {
            val condition = bindCondition(statement.condition, scope, owner)
            val body = bindBlock(statement.body, Scope(scope), expectedReturnType, owner)
            BoundWhileStatement(condition, body, statement.span)
        }

        is ForStatement -> {
            val start = bindExpression(statement.start, scope, owner)
            val end = bindExpression(statement.end, scope, owner)
            requireInt32(start, statement.start.span)
            requireInt32(end, statement.end.span)
            if (start.type != Int32Type || end.type != Int32Type) diagnostics.fail(statement.span, "range bounds require Int")
            val loopScope = Scope(scope)
            val symbol = VariableSymbol(statement.name, Int32Type, mutable = false)
            if (!loopScope.define(symbol)) {
                diagnostics.error(statement.span, "duplicate variable '${statement.name}'")
            }
            val body = bindBlock(statement.body, loopScope, expectedReturnType, owner)
            BoundForStatement(symbol, start, end, body, statement.span)
        }

        is BlockStatement -> bindBlock(statement, Scope(scope), expectedReturnType, owner)
    }

    private fun bindCondition(
        expression: Expression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression {
        val bound = bindExpression(expression, scope, owner)
        requireInt32(bound, expression.span)
        return bound
    }

    private fun requireInt32(expression: BoundExpression, span: SourceSpan) {
        if (expression.type != Int32Type && expression.type != BoolType) {
            diagnostics.fail(span, "condition requires Bool or Int32")
        }
    }

    private fun bindExpression(
        expression: Expression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression {
        return when (expression) {
        is IntegerLiteral -> BoundIntLiteral(expression.value, expression.span)
        is BooleanLiteral -> BoundIntLiteral(if (expression.value) 1 else 0, expression.span, BoolType)
        is VoidLiteral -> BoundVoidLiteral(expression.span)
        is StringLiteral -> BoundStringLiteral(expression.value, expression.span)
        is NullLiteral -> BoundNullLiteral(expression.span)

        is NameExpression -> {
            if (expression.name == "super") {
                val base = owner?.baseClass ?: diagnostics.fail(expression.span, "super requires a superclass")
                return BoundThisExpression(ParameterSymbol("this", base.type), expression.span)
            }
            val symbol = scope.resolve(expression.name)
            if (symbol == null && owner != null) {
                val field = owner.fields[expression.name]
                if (field != null) {
                    val receiver = BoundThisExpression(
                        ParameterSymbol("this", owner.type),
                        expression.span
                    )
                    return member(receiver, field, expression.span, owner)
                }
            }
            val resolved = symbol
                ?: diagnostics.fail(expression.span, "unknown name '${expression.name}'")
            if (resolved is ClassSymbol && resolved.type.objectLike) {
                return BoundObjectReference(resolved.type, expression.span)
            }
            if (resolved !is ValueSymbol) {
                diagnostics.fail(expression.span, "'${expression.name}' is not a value")
            }
            BoundNameExpression(resolved, expression.span)
        }

        is NewExpression -> {
            if (expression.type.nullable) diagnostics.fail(expression.span, "construct a non-null type")
            if (expression.type.name !in setOf("Pointer", "BufferPointer") &&
                expression.type.arguments.isNotEmpty()) {
                diagnostics.fail(expression.span, "type '${expression.type.name}' does not accept type arguments")
            }
            val standardType = nativeTypes[expression.type.name]
            if (standardType != null) {
                val arguments = expression.arguments.map { bindExpression(it, scope, owner) }
                if (arguments.isEmpty()) {
                    return when (standardType) {
                        Int32Type -> BoundIntLiteral(0, expression.span)
                        BoolType -> BoundIntLiteral(0, expression.span, BoolType)
                        StringType -> BoundStringLiteral("", expression.span)
                        UnitType -> BoundVoidLiteral(expression.span)
                        else -> error("unsupported standard constructor")
                    }
                }
                if (arguments.size != 1 || arguments.single().type != standardType || standardType == UnitType) {
                    diagnostics.fail(expression.span, "invalid ${expression.type.name} constructor arguments")
                }
                return arguments.single()
            }
            if (expression.type.name in setOf("Pointer", "BufferPointer")) {
                val managedType = resolveType(expression.type) as? ManagedPointerType
                    ?: diagnostics.fail(expression.type.span, "Pointer requires one type argument")
                if (managedType.kind == ManagedPointerKind.BUFFER) {
                    if (expression.arguments.size != 1) {
                        diagnostics.fail(expression.span, "BufferPointer constructor expects one Int length")
                    }
                    val length = bindExpression(expression.arguments.single(), scope, owner)
                    if (length.type != Int32Type) {
                        diagnostics.fail(expression.span, "BufferPointer constructor expects one Int length")
                    }
                    return BoundBufferAllocationExpression(
                        length,
                        managedType,
                        expression.span
                    )
                }
                if (expression.arguments.size != 1) {
                    diagnostics.fail(expression.span, "Pointer constructor expects one initializer")
                }
                if (managedType.pointee == PointerWildcardType) {
                    diagnostics.fail(expression.span, "${managedType.kind.displayName}<*> cannot be constructed")
                }
                val initializer = bindExpression(expression.arguments.single(), scope, owner)
                if (!isAssignable(managedType.pointee, initializer.type)) {
                    diagnostics.fail(expression.span, "Pointer initializer must be ${managedType.pointee.displayName}")
                }
                return BoundManagedPointerExpression(initializer, managedType, expression.span)
            }
            val classType = resolveClassType(expression.type)
            if (classType.objectLike) diagnostics.fail(expression.span, "object declarations cannot be constructed")
            val arguments = expression.arguments.map { bindExpression(it, scope, owner) }
            val classSymbol = classes.getValue(classType.name)
            val constructor = classSymbol.constructor
            if (constructor == null && arguments.isNotEmpty()) {
                diagnostics.fail(expression.span, "class '${classSymbol.name}' has no constructor")
            }
            if (constructor != null) {
                checkArguments(classSymbol.name, arguments, constructor.parameters, expression.span)
                checkAccess(constructor, owner, expression.span)
            }
            BoundNewExpression(classType, arguments, constructor, expression.span)
        }

        is AllocationExpression -> {
            val pointee = resolveType(expression.elementType)
            if (pointee == UnitType || pointee == PointerWildcardType) {
                diagnostics.fail(expression.span, "alloc requires a concrete value type")
            }
            val length = bindExpression(expression.length, scope, owner)
            if (length.type != Int32Type) {
                diagnostics.fail(expression.length.span, "alloc length must be Int")
            }
            BoundBufferAllocationExpression(
                length,
                ManagedPointerType(pointee, kind = ManagedPointerKind.BUFFER),
                expression.span
            )
        }

        is IndexExpression -> {
            val receiver = bindExpression(expression.receiver, scope, owner)
            if (receiver.type is NullableType) {
                diagnostics.fail(expression.span, "nullable BufferPointer must be resolved before indexing")
            }
            val pointerType = unwrapNullable(receiver.type) as? ManagedPointerType
                ?: diagnostics.fail(expression.span, "indexing requires BufferPointer")
            if (pointerType.kind != ManagedPointerKind.BUFFER) {
                diagnostics.fail(expression.span, "indexing requires BufferPointer")
            }
            if (pointerType.pointee == PointerWildcardType) {
                diagnostics.fail(expression.span, "cannot index BufferPointer<*>")
            }
            val index = bindExpression(expression.index, scope, owner)
            if (index.type != Int32Type) {
                diagnostics.fail(expression.index.span, "buffer index must be Int")
            }
            BoundBufferGetExpression(receiver, index, pointerType.pointee, expression.span)
        }

        is UnaryExpression -> {
            val operand = bindExpression(expression.operand, scope, owner)
            if (expression.operator != UnaryOperator.NOT || operand.type != BoolType) {
                diagnostics.fail(expression.span, "operator 'NOT' requires Bool")
            }
            BoundCallExpression(
                classes.getValue("Bool").methods.getValue("not"),
                emptyList(),
                expression.span,
                BoolType,
                receiver = operand
            )
        }

        is MemberAccessExpression -> {
            val receiver = bindExpression(expression.receiver, scope, owner)
            val receiverType = unwrapNullable(receiver.type)
            if (receiver.type is NullableType && !expression.safe) {
                diagnostics.fail(expression.span, "nullable receiver requires '?.'")
            }
            val classSymbol = receiverType as? ClassType
                ?: diagnostics.fail(expression.span, "member access requires an object value")
            val field = classes[classSymbol.name]?.fields?.get(expression.name)
                ?: diagnostics.fail(
                    expression.span,
                    "unknown field '${expression.name}' on '${classSymbol.name}'"
                )
            if (expression.safe && !isNullableCapable(field.type)) {
                diagnostics.fail(expression.span, "safe access currently requires a reference-valued field")
            }
            member(receiver, field, expression.span, owner, expression.safe && receiver.type is NullableType).copy(
                direct = (expression.receiver as? NameExpression)?.name == "super")
        }

        is BinaryExpression -> bindBinary(expression, scope, owner)
        is CallExpression -> bindCall(expression, scope, owner)
        is ElvisExpression -> {
            val nullable = bindExpression(expression.nullable, scope, owner)
            val nullableType = nullable.type as? NullableType
                ?: diagnostics.fail(expression.nullable.span, "left side of '?:' must be nullable")
            val fallback = bindExpression(expression.fallback, scope, owner)
            if (!isAssignable(nullableType.underlying, fallback.type)) {
                diagnostics.fail(expression.fallback.span, "Elvis fallback must be ${nullableType.underlying.displayName}")
            }
            BoundElvisExpression(nullable, fallback, nullableType.underlying, expression.span)
        }
    }

    }

    private fun bindBinary(
        expression: BinaryExpression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression {
        val left = bindExpression(expression.left, scope, owner)
        val right = bindExpression(expression.right, scope, owner)
        if ((left.type == NullType || right.type == NullType) &&
            expression.operator in setOf(BinaryOperator.EQUALS, BinaryOperator.NOT_EQUALS)) {
            val other = if (left.type == NullType) right.type else left.type
            if (other !is NullableType && other != NullType) diagnostics.fail(expression.span, "null comparison requires a nullable value")
            return BoundBinaryExpression(left,
                if (expression.operator == BinaryOperator.EQUALS) BoundBinaryOperator.EQ_I32 else BoundBinaryOperator.NE_I32,
                right, expression.span, BoolType)
        }
        val leftType = left.type
        if (expression.operator in setOf(BinaryOperator.ADD, BinaryOperator.SUB) &&
            leftType is ManagedPointerType &&
            leftType.kind == ManagedPointerKind.BUFFER) {
            if (right.type != Int32Type) {
                diagnostics.fail(expression.span, "operator 'ADD' has incompatible operands")
            }
            return BoundPointerAddExpression(
                left,
                right,
                ManagedPointerType(leftType.pointee, leftType.writable, leftType.kind),
                expression.span,
                if (expression.operator == BinaryOperator.ADD) 1 else -1
            )
        }
        val name = when (expression.operator) {
            BinaryOperator.ADD -> "plus"
            BinaryOperator.SUB -> "minus"
            BinaryOperator.MUL -> "times"
            BinaryOperator.DIV -> "div"
            BinaryOperator.EQUALS -> "equals"
            BinaryOperator.NOT_EQUALS -> "notEquals"
            BinaryOperator.LESS -> "lessThan"
            BinaryOperator.LESS_EQUALS -> "lessOrEqual"
            BinaryOperator.GREATER -> "greaterThan"
            BinaryOperator.GREATER_EQUALS -> "greaterOrEqual"
            BinaryOperator.AND -> "and"
            BinaryOperator.OR -> "or"
            BinaryOperator.XOR -> "xor"
        }
        val method = classes[left.type.displayName]?.methods?.get(name)
            ?: diagnostics.fail(expression.span, "operator '${expression.operator}' is not defined on ${left.type.displayName}")
        checkAccess(method, owner, expression.span)
        if (method.parameters.size != 1 || !isAssignable(method.parameters.single().type, right.type)) {
            diagnostics.fail(expression.span, "operator '$name' has incompatible operands")
        }
        return BoundCallExpression(method, listOf(right), expression.span, receiver = left)
    }

    private fun bindCall(
        expression: CallExpression,
        scope: Scope,
        owner: ClassSymbol?
    ): BoundExpression {
        val arguments = expression.arguments.map { bindExpression(it, scope, owner) }
        var receiver: BoundExpression? = null
        val symbol: FunctionSymbol
        when (val target = expression.target) {
            is NameExpression -> {
                run {
                    val resolved = scope.resolve(target.name)
                    if (resolved is FunctionSymbol) {
                        val matches = overloads[target.name].orEmpty().filter { candidate ->
                            (candidate.visibility != Visibility.PRIVATE || candidate.declarationSpan?.source == target.span.source) &&
                                candidate.parameters.size == arguments.size &&
                                candidate.parameters.zip(arguments).all { (p, a) -> isAssignable(p.type, a.type) }
                        }
                        val best = matches.filter { candidate -> matches.none { other ->
                            other !== candidate && other.parameters.zip(candidate.parameters).all { (a, b) -> isAssignable(b.type, a.type) } &&
                                other.parameters.zip(candidate.parameters).any { (a, b) -> a.type != b.type }
                        } }
                        if (best.size != 1) diagnostics.fail(target.span, "no unique accessible overload for '${target.name}'")
                        symbol = best.single()
                    } else if (resolved == null && owner != null) {
                        symbol = owner.methods[target.name]
                            ?: diagnostics.fail(target.span, "unknown function '${target.name}'")
                        receiver = BoundThisExpression(
                            ParameterSymbol("this", nativeTypes[owner.name] ?: owner.type),
                            target.span
                        )
                    } else {
                        diagnostics.fail(target.span, "unknown function '${target.name}'")
                    }
                }
            }
            is MemberAccessExpression -> {
                receiver = bindExpression(target.receiver, scope, owner)
                if (receiver.type is NullableType && unwrapNullable(receiver.type) is ManagedPointerType) {
                    diagnostics.fail(target.span, "nullable receiver must be resolved before calling a memory method")
                }
                if (target.name == "isFreed" && receiver.type !is NullableType) {
                    if (arguments.isNotEmpty()) {
                        diagnostics.fail(expression.span, "isFreed expects no arguments")
                    }
                    return BoundTypeIsFreedExpression(receiver, expression.span)
                }
                if (receiver.type is ManagedPointerType && target.name == "address") {
                    if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "address expects no arguments")
                    val value = receiver as? BoundNameExpression
                        ?: diagnostics.fail(target.span, "address requires a local variable")
                    if (value.symbol !is VariableSymbol && value.symbol !is ParameterSymbol) {
                        diagnostics.fail(target.span, "address requires a local variable")
                    }
                    return BoundAddressExpression(value, expression.span)
                }
                val pointerType = unwrapNullable(receiver.type) as? ManagedPointerType
                if (pointerType != null) {
                    val pointerClass = when (pointerType.kind) {
                        ManagedPointerKind.POINTER -> "Pointer"
                        ManagedPointerKind.BUFFER -> "BufferPointer"
                    }
                    if (classes[pointerClass]?.methods?.containsKey(target.name) != true) {
                        diagnostics.fail(target.span, "unknown Pointer method '${target.name}'")
                    }
                    return when (target.name) {
                        "add", "plus" -> {
                            if (pointerType.kind != ManagedPointerKind.BUFFER) {
                                diagnostics.fail(expression.span, "pointer arithmetic requires BufferPointer")
                            }
                            if (arguments.size != 1 || arguments[0].type != Int32Type) {
                                diagnostics.fail(expression.span, "operator 'plus' has incompatible operands")
                            }
                            BoundPointerAddExpression(
                                receiver,
                                arguments.single(),
                                ManagedPointerType(
                                    pointerType.pointee,
                                    pointerType.writable,
                                    pointerType.kind
                                ),
                                expression.span
                            )
                        }
                        "get" -> {
                            if (pointerType.kind != ManagedPointerKind.BUFFER) {
                                diagnostics.fail(expression.span, "get requires BufferPointer")
                            }
                            if (pointerType.pointee == PointerWildcardType) {
                                diagnostics.fail(expression.span, "cannot get from BufferPointer<*>")
                            }
                            if (arguments.size != 1 || arguments[0].type != Int32Type) {
                                diagnostics.fail(expression.span, "get expects one Int index")
                            }
                            BoundBufferGetExpression(receiver, arguments.single(), pointerType.pointee, expression.span)
                        }
                        "set" -> {
                            if (pointerType.kind != ManagedPointerKind.BUFFER) {
                                diagnostics.fail(expression.span, "set requires BufferPointer")
                            }
                            if (!pointerType.writable) {
                                diagnostics.fail(expression.span, "cannot write through the address of a val")
                            }
                            if (pointerType.pointee == PointerWildcardType) {
                                diagnostics.fail(expression.span, "cannot set BufferPointer<*>")
                            }
                            if (arguments.size != 2 ||
                                arguments[0].type != Int32Type ||
                                !isAssignable(pointerType.pointee, arguments[1].type)) {
                                diagnostics.fail(expression.span, "set expects an Int index and a ${pointerType.pointee.displayName} value")
                            }
                            BoundBufferSetExpression(receiver, arguments[0], arguments[1], expression.span)
                        }
                        "length" -> {
                            if (pointerType.kind != ManagedPointerKind.BUFFER) {
                                diagnostics.fail(expression.span, "length requires BufferPointer")
                            }
                            if (arguments.isNotEmpty()) {
                                diagnostics.fail(expression.span, "length expects no arguments")
                            }
                            BoundBufferLengthExpression(receiver, expression.span)
                        }
                        "read" -> {
                            if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "read expects no arguments")
                            if (pointerType.pointee == PointerWildcardType) {
                                diagnostics.fail(expression.span, "cannot read from Pointer<*>")
                            }
                            BoundDereferenceExpression(receiver, expression.span, pointerType.pointee)
                        }
                        "write" -> {
                            if (!pointerType.writable) diagnostics.fail(expression.span, "cannot write through the address of a val")
                            if (pointerType.pointee == PointerWildcardType) {
                                diagnostics.fail(expression.span, "cannot write to Pointer<*>")
                            }
                            if (arguments.size != 1 || !isAssignable(pointerType.pointee, arguments[0].type)) {
                                diagnostics.fail(expression.span, "write expects one ${pointerType.pointee.displayName} argument")
                            }
                            BoundPointerWriteExpression(receiver, arguments[0], expression.span)
                        }
                        "free" -> {
                            if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "free expects no arguments")
                            BoundFreeExpression(receiver, expression.span)
                        }
                        "isFreed" -> {
                            if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "isFreed expects no arguments")
                            BoundCallExpression(classes.getValue(pointerClass).methods.getValue("isFreed"),
                                emptyList(), expression.span, receiver = receiver)
                        }
                        "toString" -> {
                            if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "toString expects no arguments")
                            BoundCallExpression(classes.getValue(pointerClass).methods.getValue("toString"),
                                emptyList(), expression.span, receiver = receiver)
                        }
                        else -> diagnostics.fail(target.span, "unknown Pointer method '${target.name}'")
                    }
                }
                if ((receiver.type in setOf(Int32Type, BoolType, StringType) ||
                        receiver.type is ClassType) && target.name == "address") {
                    if (arguments.isNotEmpty()) diagnostics.fail(expression.span, "address expects no arguments")
                    val value = receiver as? BoundNameExpression
                        ?: diagnostics.fail(target.span, "address requires a local variable")
                    if (value.symbol !is VariableSymbol && value.symbol !is ParameterSymbol) {
                        diagnostics.fail(target.span, "address requires a local variable")
                    }
                    return BoundAddressExpression(value, expression.span)
                }
                if (receiver.type is NullableType && !target.safe) {
                    diagnostics.fail(target.span, "nullable receiver requires '?.'")
                }
                val classType = (unwrapNullable(receiver.type) as? ClassType)
                    ?: classes[unwrapNullable(receiver.type).displayName]?.type
                    ?: diagnostics.fail(target.span, "method receiver must be an object value")
                symbol = classes[classType.name]?.methods?.get(target.name)
                    ?: diagnostics.fail(
                        target.span,
                        "unknown method '${target.name}' on '${classType.name}'"
                    )
                checkAccess(symbol, owner, target.span)
            }
            else -> diagnostics.fail(expression.span, "call target must be a function or method")
        }

        if (symbol.owner == null && symbol.visibility == Visibility.PRIVATE &&
            symbol.declarationSpan?.source != expression.span.source) {
            diagnostics.fail(expression.span, "function '${symbol.name}' is private")
        }
        checkAccess(symbol, owner, expression.span)
        if (arguments.size != symbol.parameters.size) {
            diagnostics.fail(
                expression.span,
                "function '${symbol.name}' expects ${symbol.parameters.size} arguments, " +
                    "found ${arguments.size}"
            )
        }
        arguments.zip(symbol.parameters).forEachIndexed { index, (argument, parameter) ->
            if (!isAssignable(parameter.type, argument.type)) {
                diagnostics.error(
                    expression.arguments[index].span,
                    "argument ${index + 1}: expected ${parameter.type.displayName}, " +
                        "found ${argument.type.displayName}"
                )
            }
        }
        val safe = (expression.target as? MemberAccessExpression)?.safe == true && receiver?.type is NullableType
        if (safe && symbol.returnType != UnitType && !isNullableCapable(symbol.returnType)) {
            diagnostics.fail(expression.span, "safe call currently requires a reference return type")
        }
        val resultType = if (safe && symbol.returnType != UnitType) {
            nullable(symbol.returnType)
        } else {
            symbol.returnType
        }
        return BoundCallExpression(
            symbol,
            arguments,
            expression.span,
            resultType,
            receiver,
            safe,
            ((expression.target as? MemberAccessExpression)?.receiver as? NameExpression)?.name == "super"
        )
    }

    private fun resolveClassType(type: TypeReference): ClassType =
        classes[type.name]?.let { symbol ->
            if (symbol.visibility == Visibility.PRIVATE &&
                symbol.declarationSpan?.source != type.span.source
            ) {
                diagnostics.fail(type.span, "type '${type.name}' is private")
            }
            symbol.type
        } ?: diagnostics.fail(type.span, "unknown type '${type.name}'")

    private fun resolveType(type: TypeReference): RType = when (type.name) {
        "Pointer", "BufferPointer" -> {
            if (type.name !in classes) diagnostics.fail(type.span, "${type.name} is not imported")
            if (type.arguments.size != 1) {
                diagnostics.fail(type.span, "${type.name} requires exactly one type argument")
            }
            val argument = type.arguments.single()
            val pointee = if (argument.name == "*" && argument.arguments.isEmpty() && !argument.nullable) {
                PointerWildcardType
            } else {
                resolveType(argument)
            }
            if (pointee == UnitType) diagnostics.fail(type.span, "${type.name} requires a value type")
            ManagedPointerType(
                pointee,
                kind = if (type.name == "BufferPointer") {
                    ManagedPointerKind.BUFFER
                } else {
                    ManagedPointerKind.POINTER
                }
            )
        }
        else -> {
            if (type.arguments.isNotEmpty()) diagnostics.fail(type.span, "type '${type.name}' does not accept type arguments")
            nativeTypes[type.name] ?: if (type.name in typeParameters) ClassType(type.name) else resolveClassType(type)
        }
    }.let { base ->
        var result = base
        if (type.nullable) {
            if (!isNullableCapable(result)) {
                diagnostics.fail(type.span, "only object, String, and pointer types may be nullable")
            }
            NullableType(result)
        } else {
            result
        }
    }

    private fun unwrapNullable(type: RType): RType =
        if (type is NullableType) type.underlying else type

    private fun isNullableCapable(type: RType): Boolean =
        type == Int32Type || type == BoolType || type == StringType || type is ClassType || type is ManagedPointerType || type is NullableType

    private fun isAssignable(expected: RType, actual: RType): Boolean {
        if (expected == actual) return true
        if (expected is NullableType) return actual == NullType ||
            isAssignable(expected.underlying, unwrapNullable(actual))
        if (expected is ManagedPointerType && actual is ManagedPointerType) {
            if (expected.kind != actual.kind) return false
            if (expected.pointee == PointerWildcardType) return true
            if (actual.pointee == PointerWildcardType) return false
            return isAssignable(expected.pointee, actual.pointee)
        }
        if (expected is ClassType && actual is ClassType) return isSubclass(classes[actual.name], classes.getValue(expected.name))
        return false
    }

    private fun checkArguments(
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
