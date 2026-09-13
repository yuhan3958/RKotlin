package me.rkt.lower

import me.rkt.ir.*
import me.rkt.semantic.*

/** Assembles module metadata and synthetic members; delegates function bodies. */
class IrLowering {
    fun lower(module: SemanticModule): IrModule =
        IrModule(
            module.functions.map(IrFunctionLowering::lowerFunction) + module.classes.flatMap(::lowerAccessors),
            module.classes.filter {
                IrLoweringRules.lowerType(it.type) is IrObjectType &&
                    it.name !in setOf("Pointer", "BufferPointer")
            }.map { classSymbol ->
                IrClass(
                    classSymbol.name,
                    classSymbol.fields.values.filter { it.ownerName == classSymbol.name }.map {
                        IrField(it.name, IrLoweringRules.lowerType(it.type))
                    },
                    classSymbol.type.objectLike,
                    classSymbol.baseClass?.name,
                    classSymbol.methods.values.filter { it.builtinTarget == null }.map {
                        IrMethod(it.name, it.generatedName, it.owner!!.name,
                            it.parameters.map { p -> IrLoweringRules.lowerType(p.type) }, IrLoweringRules.lowerType(it.returnType),
                            virtual = IrLoweringRules.virtual(it))
                    },
                    classSymbol.declarationSpan!!.source.path.toAbsolutePath().normalize().toString(),
                    classSymbol.isInterface,
                    classSymbol.interfaces.map { it.name }
                )
            }
        )

    private fun lowerAccessors(owner: ClassSymbol): List<IrFunction> {
        if (owner.isInterface) return emptyList()
        return buildList {
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
                val type = IrLoweringRules.lowerType(field.type)
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
    }

}
