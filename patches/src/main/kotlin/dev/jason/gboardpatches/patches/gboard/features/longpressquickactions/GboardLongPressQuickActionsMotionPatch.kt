package dev.jason.gboardpatches.patches.gboard.features.longpressquickactions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import dev.jason.gboardpatches.patches.gboard.shared.GboardMethodTarget
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallEmitter
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

private val POINTER_MOTION = GboardMethodTarget(
    classType = "Lpvi;",
    name = "t",
    parameterTypes = listOf("Landroid/view/MotionEvent;", "I"),
    returnType = "V",
)

private val POINTER_RETARGET = GboardMethodTarget(
    classType = "Lpvi;",
    name = "F",
    parameterTypes = listOf("Landroid/view/MotionEvent;", "I"),
    returnType = "V",
)

private val BACKSPACE_MOTION_CALL =
    RuntimeCallId.LONG_PRESS_QUICK_ACTIONS_RUNTIME_MAYBE_INTERCEPT_BACKSPACE_MOTION
private val BACKSPACE_RETARGET_CALL =
    RuntimeCallId.LONG_PRESS_QUICK_ACTIONS_RUNTIME_MAYBE_SUPPRESS_BACKSPACE_RETARGET

internal val gboardLongPressQuickActionsMotionPatch = bytecodePatch(
    description = "在 18.0.3 MotionEvent / retarget 層攔截 Backspace 上滑，避免滑入 L 鍵。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        POINTER_MOTION.resolve(this).injectBackspaceMotionInterceptor(
            BACKSPACE_MOTION_CALL,
            "jasondev_continue_backspace_motion",
        )
        POINTER_RETARGET.resolve(this).injectBackspaceMotionInterceptor(
            BACKSPACE_RETARGET_CALL,
            "jasondev_continue_backspace_retarget",
        )
    }
}

private fun MutableMethod.injectBackspaceMotionInterceptor(
    runtimeCall: RuntimeCallId,
    label: String,
) {
    val implementation = implementation ?: error("$definingClass->$name has no implementation")
    check(returnType == "V" && parameters.size == 2) {
        "Unexpected Backspace motion target prototype: $definingClass->$name"
    }
    check(implementation.registerCount >= 4) {
        "Backspace motion target has no local scratch register: $definingClass->$name"
    }

    addInstructions(0, "nop")
    val continuation = implementation.instructions[0]
    val delegate = """
        ${RuntimeCallEmitter.invoke(runtimeCall, "p0, p1, p2")}

        move-result v0

        if-eqz v0, :$label

        return-void
    """.trimIndent()
    addInstructionsWithLabels(
        0,
        delegate,
        ExternalLabel(label, continuation),
    )
}
