package me.rkt.backend

import me.rkt.ir.IrModule

fun interface Backend<R> {
    fun generate(module: IrModule): R
}
