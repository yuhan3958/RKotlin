package me.rkt.lower

import me.rkt.ir.*
import me.rkt.semantic.*

/** Registers, labels, and locals belonging to exactly one lowered function. */
internal class FunctionContext {
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
