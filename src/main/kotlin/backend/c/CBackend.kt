package me.rkt.backend.c

import me.rkt.backend.Backend
import me.rkt.ir.IrModule

/** Backend entry point; each generation receives isolated module emission state. */
class CBackend(private val moduleDirectory: String = "rkotlin.modules") : Backend<COutput> {
    override fun generate(module: IrModule): COutput =
        CModuleEmitter(moduleDirectory, module).generate()
}
