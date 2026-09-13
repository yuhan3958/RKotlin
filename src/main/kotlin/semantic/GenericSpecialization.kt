package me.rkt.semantic

import me.rkt.diagnostic.DiagnosticReporter
import me.rkt.ast.Visibility
import java.security.MessageDigest

/** Materializes reachable generic classes before IR lowering. No type parameter is erased. */
internal class GenericSpecialization(private val diagnostics: DiagnosticReporter) {
    private lateinit var templates: Map<String, ClassSymbol>
    private val instances = linkedMapOf<ClassType, ClassSymbol>()
    private val pending = ArrayDeque<Pair<ClassSymbol, ClassSymbol>>()
    private val bindings = mutableMapOf<ClassSymbol, Map<String, RType>>()
    private var depth = 0

    private fun nesting(type: RType): Int = when (type) {
        is ClassType -> 1 + (type.typeArguments.maxOfOrNull(::nesting) ?: 0)
        is NullableType -> 1 + nesting(type.underlying)
        is ManagedPointerType -> 1 + nesting(type.pointee)
        else -> 0
    }

    fun specialize(module: SemanticModule): SemanticModule {
        templates = module.classes.associateBy { it.name }
        module.classes.filter { it.typeParameters.isEmpty() }.forEach { instance(it.type) }
        val functions = mutableListOf<BoundFunction>()
        module.functions.filter { it.symbol.owner == null }.forEach {
            functions += SpecializedBody(this, emptyMap()).rewrite(it, function(it.symbol, null, emptyMap()))
        }
        val bodies = module.functions.filter { it.symbol.owner != null }.groupBy { it.symbol.owner!! }
        while (pending.isNotEmpty()) {
            val (template, target) = pending.removeFirst()
            for (body in bodies[template].orEmpty()) {
                val symbol = if (body.symbol.name == "constructor") target.constructor!!
                    else target.methods.getValue(body.symbol.name)
                functions += SpecializedBody(this, bindings.getValue(target)).rewrite(body, symbol)
            }
        }
        // Views dispatch using the real object's type tag, including implementations discovered in bodies.
        val views = instances.filterKeys(WildcardProjection::isView)
        for ((source, target) in instances) {
            if (WildcardProjection.isView(source)) continue
            for ((view, projection) in views) {
                val actual = TypeSubstitution.viewAs(source, templates.getValue(view.name), templates) ?: continue
                if (WildcardProjection.accepts(view, actual) && projection !in target.interfaces) {
                    target.interfaces += projection
                    target.interfaceTypes += projection.type
                }
            }
        }
        return module.copy(functions = functions, classes = instances.values.toList())
    }

    fun type(type: RType, arguments: Map<String, RType>): RType = concrete(TypeSubstitution.apply(type, arguments))

    private fun concrete(type: RType): RType = when (type) {
        is ClassType -> {
            check(!type.typeParameter) { "unresolved type parameter '${type.name}' during specialization" }
            instance(type).type
        }
        is ManagedPointerType -> type.copy(pointee = concrete(type.pointee))
        is NullableType -> NullableType(concrete(type.underlying))
        else -> type
    }

    private fun instance(sourceType: ClassType): ClassSymbol {
        instances[sourceType]?.let { return it }
        val template = templates.getValue(sourceType.name)
        check(sourceType.typeArguments.size == template.typeParameters.size) { "unresolved generic type ${sourceType.displayName}" }
        if (++depth > 64 || nesting(sourceType) > 64) {
            diagnostics.fail(template.declarationSpan!!, "generic instantiation exceeds the nesting limit: ${sourceType.displayName}")
        }
        val arguments = template.typeParameters.zip(sourceType.typeArguments).toMap()
        val projected = WildcardProjection.isView(sourceType)
        val name = if (arguments.isEmpty()) template.name else {
            val digest = MessageDigest.getInstance("SHA-256").digest(sourceType.displayName.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
            "${template.name}__${digest}"
        }
        if (arguments.isNotEmpty() && (name in templates || instances.values.any { it.name == name })) {
            diagnostics.fail(template.declarationSpan!!, "specialized type name collision: $name")
        }
        val target = ClassSymbol(name, ClassType(name, template.type.objectLike),
            visibility = template.visibility, declarationSpan = template.declarationSpan,
            isInterface = template.isInterface || projected)
        instances[sourceType] = target
        bindings[target] = arguments
        target.baseClass = template.baseClass?.let { instance(it.type) }
        template.interfaceTypes.forEach {
            val parentType = if (projected) WildcardProjection.output(it, arguments)
                else TypeSubstitution.apply(it, arguments)
            val parent = instance(parentType as ClassType)
            target.interfaces += parent
            target.interfaceTypes += parent.type
        }
        template.fields.forEach { (name, field) ->
            val declaring = if (field.ownerName == template.name) target else instance(templates.getValue(field.ownerName).type)
            val fieldType = if (projected) WildcardProjection.output(field.type, arguments)
                else TypeSubstitution.apply(field.type, arguments)
            target.fields[name] = field.copy(type = concrete(fieldType), ownerName = declaring.name)
        }
        fun signature(symbol: FunctionSymbol): FunctionSymbol {
            val owner = if (symbol.owner === template) target else symbol.owner?.let { instance(it.type) }
            val resolved = WildcardProjection.function(symbol, arguments)
            return resolved.copy(owner = owner,
                visibility = if (projected && owner === target) Visibility.PUBLIC else symbol.visibility,
                parameters = resolved.parameters.map { it.copy(type = concrete(it.type)) },
                returnType = concrete(resolved.returnType))
        }
        template.methods.forEach { (name, method) ->
            val resolved = WildcardProjection.function(method, arguments)
            if (!projected || (resolved.unavailableParameters.isEmpty() && !WildcardProjection.unreadable(resolved.returnType))) {
                target.methods[name] = signature(method)
            }
        }
        if (!projected) {
            target.constructor = template.constructor?.let(::signature)
            pending += template to target
        }
        depth--
        return target
    }

    fun function(symbol: FunctionSymbol, receiver: RType?, arguments: Map<String, RType>): FunctionSymbol {
        val owner = symbol.owner
        if (symbol.builtinTarget != null) {
            val pointer = ((receiver as? NullableType)?.underlying ?: receiver) as? ManagedPointerType
            val substitutions = if (pointer != null && owner != null) {
                arguments + owner.typeParameters.zip(listOf(TypeSubstitution.apply(pointer.pointee, arguments)))
            } else arguments
            return symbol.copy(owner = null,
                parameters = symbol.parameters.map { it.copy(type = type(it.type, substitutions)) },
                returnType = type(symbol.returnType, substitutions))
        }
        if (owner == null) return symbol.copy(
            parameters = symbol.parameters.map { it.copy(type = type(it.type, arguments)) },
            returnType = type(symbol.returnType, arguments))
        val sourceReceiver = (receiver as? NullableType)?.underlying ?: receiver
        val view = (sourceReceiver as? ClassType)?.let { TypeSubstitution.viewAs(it, owner, templates) }
            ?: owner.type
        val target = instance(TypeSubstitution.apply(view, arguments) as ClassType)
        return if (symbol.name == "constructor") target.constructor!! else target.methods.getValue(symbol.name)
    }

    fun field(field: FieldSymbol, receiver: RType, arguments: Map<String, RType>): FieldSymbol {
        val owner = templates.getValue(field.ownerName)
        val raw = (receiver as? NullableType)?.underlying ?: receiver
        val view = TypeSubstitution.viewAs(raw as ClassType, owner, templates) ?: owner.type
        return instance(TypeSubstitution.apply(view, arguments) as ClassType).fields.getValue(field.name)
    }
}
