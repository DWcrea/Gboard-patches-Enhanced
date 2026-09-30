package dev.jason.gboardpatches.patches.gboard.registry

import dev.jason.gboardpatches.patches.gboard.features.longpressquickactions.gboardLongPressQuickActionsFeatureMarkerPatch
import dev.jason.gboardpatches.patches.gboard.features.longpressquickactions.gboardLongPressQuickActionsInputEventPatch
import dev.jason.gboardpatches.patches.gboard.features.longpressquickactions.gboardLongPressQuickActionsGesturePatch
import dev.jason.gboardpatches.patches.gboard.features.longpressquickactions.gboardLongPressQuickActionsPointerOwnerPatch
import dev.jason.gboardpatches.patches.gboard.features.longpressquickactions.gboardLongPressQuickActionsMotionPatch
import dev.jason.gboardpatches.patches.gboard.features.zhuyinslide.gboardZhuyinSlidePointerAnchorPatch
import dev.jason.gboardpatches.patches.gboard.features.longpressquickactions.gboardLongPressQuickActionsSoftKeyPatch
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesSettingsPatch
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GboardLongPressQuickActionsPatchContractTest {
    @Test
    fun publicPatchIsIndependentAndExact1803Only() {
        val patch = gboardLongPressQuickActionsPatch
        assertEquals("Long-Press Editing Shortcuts", patch.name)
        assertEquals(LONG_PRESS_DESCRIPTION, patch.description)
        assertTrue(patch.default)

        assertEquals(
            listOf(
                gboardPatchesSettingsPatch,
                gboardLongPressQuickActionsFeatureMarkerPatch,
                gboardLongPressQuickActionsSoftKeyPatch,
                gboardLongPressQuickActionsInputEventPatch,
                gboardLongPressQuickActionsMotionPatch,
                gboardLongPressQuickActionsPointerOwnerPatch,
                gboardZhuyinSlidePointerAnchorPatch,
                gboardLongPressQuickActionsGesturePatch,
            ),
            patch.dependencies.toList(),
        )
        assertFalse(patch.dependencies.any { dependency ->
            dependency.toString().contains("AdvancedVoice", ignoreCase = true)
        })

        assertEquals(
            "18.0.3.954559732-release-arm64-v8a",
            patch.compatibility!!.single().targets.single().version,
        )
    }

    @Test
    fun generatedInventoryContainsExactlyOneLongPressShortcutsRow() {
        val rows = generatedPublishedPatches()
            .filter { it.get("name").asString == "Long-Press Editing Shortcuts" }

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(LONG_PRESS_DESCRIPTION, row.get("description").asString)
        assertTrue(row.get("use").asBoolean)
        assertEquals(0, row.getAsJsonArray("options").size())
        assertEquals(
            listOf("18.0.3.954559732-release-arm64-v8a"),
            row.getAsJsonObject("compatiblePackages")
                .getAsJsonArray("com.google.android.inputmethod.latin")
                .map { it.asString },
        )
    }

    private fun repositoryRoot(): Path {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        return generateSequence(workingDirectory) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            ?: error("Could not locate repository root from $workingDirectory")
    }

    private companion object {
        const val LONG_PRESS_DESCRIPTION =
            "在英文 QWERTY 与注音键盘加入编辑长按快捷键，并支持删除键上滑删除光标前全部内容\n" +
                "Add long-press editing shortcuts and swipe up from Backspace to delete all text " +
                "before the cursor while preserving text after it."
    }
}
