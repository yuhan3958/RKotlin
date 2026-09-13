package me.rkt.ir

data class IrModule(
    val functions: List<IrFunction>,
    val classes: List<IrClass> = emptyList(),
    val sources: List<IrSourceModule> = emptyList(),
    val rootSource: String = ""
)

data class IrSourceModule(
    val sourcePath: String,
    val outputName: String,
    val imports: List<String>
)

data class IrClass(
    val name: String,
    val fields: List<IrField>,
    val objectLike: Boolean = false,
    val baseName: String? = null,
    val methods: List<IrMethod> = emptyList(),
    val sourcePath: String = "",
    val isInterface: Boolean = false,
    val interfaceNames: List<String> = emptyList()
)

data class IrMethod(
    val name: String,
    val functionName: String,
    val ownerName: String,
    val parameters: List<IrType>,
    val returnType: IrType,
    val virtual: Boolean = true
)

data class IrField(
    val name: String,
    val type: IrType
)
