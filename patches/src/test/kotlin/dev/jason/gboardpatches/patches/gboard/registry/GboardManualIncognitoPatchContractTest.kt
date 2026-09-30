package dev.jason.gboardpatches.patches.gboard.registry

import dev.jason.gboardpatches.patches.gboard.shared.accesspoint.gboardAccessPointContributions1803Patch
import dev.jason.gboardpatches.patches.gboard.features.manualincognito.gboardManualIncognitoFeatureMarkerPatch
import dev.jason.gboardpatches.patches.gboard.features.manualincognito.gboardManualIncognitoLifecyclePatch
import dev.jason.gboardpatches.patches.gboard.features.manualincognito.gboardManualIncognitoPolicyPatch
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesSettingsPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GboardManualIncognitoPatchContractTest {
    @Test
    fun publicPatchOwnsComplete1803PortAndKeepsSettingsMasterOffByDefault() {
        val patch = gboardManualIncognitoModePatch
        assertEquals("Incognito Mode Toggle", patch.name)
        assertEquals(
            "在 Access Point 工具栏添加无痕模式切换按钮，并可设置无痕模式下是否激活剪贴板与语音输入\n" +
                "Add an Incognito toggle to the Access Point toolbar and configure clipboard and voice typing availability while Incognito mode is active.",
            patch.description,
        )
        assertTrue(patch.default)
        assertEquals(
            listOf(
                gboardPatchesSettingsPatch,
                gboardManualIncognitoFeatureMarkerPatch,
                gboardManualIncognitoLifecyclePatch,
                gboardManualIncognitoPolicyPatch,
                gboardAccessPointContributions1803Patch,
            ),
            patch.dependencies.toList(),
        )
        assertEquals(
            "18.0.3.954559732-release-arm64-v8a",
            patch.compatibility!!.single().targets.single().version,
        )
    }
}
