package app.wayfinder

import android.hardware.input.InputManager
import android.view.InputDevice

/**
 * Input layer, phase 0 — runs as root in app_process (via [PServiceBridge.runEntryPoint]).
 * Hides / shows an input device from apps with InputManager's hidden
 * disableInputDevice / enableInputDevice, so the layer's clone can be the only controller
 * games see (player 1). Prints one "OK …" / "ERR …" line.
 *
 *   list                         gamepads: id, enabled, name, vendor:product, descriptor
 *   disable <id> | enable <id>
 *   enable-all                   re-enable every disabled device (crash cleanup)
 */
object PadTool {
    private val im: InputManager by lazy { InputManager::class.java.getMethod("getInstance").invoke(null) as InputManager }

    private fun enabled(id: Int): Boolean = runCatching {
        im.javaClass.getMethod("isInputDeviceEnabled", Int::class.javaPrimitiveType).invoke(im, id) as Boolean
    }.getOrDefault(true)

    private fun setEnabled(id: Int, on: Boolean) {
        im.javaClass.getMethod(if (on) "enableInputDevice" else "disableInputDevice", Int::class.javaPrimitiveType).invoke(im, id)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.getOrNull(0)) {
                "list" -> println("OK " + im.inputDeviceIds.toList().mapNotNull { im.getInputDevice(it) }
                    .filter { (it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD }
                    .joinToString(" | ") { "${it.id} ${if (enabled(it.id)) "on" else "OFF"} ${it.name} %04x:%04x ${it.descriptor.take(8)}".format(it.vendorId, it.productId) })
                "disable", "enable" -> {
                    val id = args[1].toInt()
                    setEnabled(id, args[0] == "enable")
                    println("OK ${args[0]} $id → ${if (enabled(id)) "on" else "OFF"}")
                }
                "enable-all" -> {
                    val off = im.inputDeviceIds.toList().filter { !enabled(it) }
                    off.forEach { setEnabled(it, true) }
                    println("OK re-enabled ${off.size}")
                }
                else -> println("ERR usage")
            }
        } catch (e: Throwable) {
            println("ERR ${e.cause ?: e}")
        }
        System.exit(0)
    }
}
