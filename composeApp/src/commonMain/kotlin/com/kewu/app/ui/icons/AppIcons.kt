package com.kewu.app.ui.icons

import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.ResourceItem

// 三个底部标签的图标：直接按资源插件打包进 APK 的相对路径构造
// （生成的 Res 是 internal 且位于另一个包，跨包取不到，这里手搓一份等价描述）
@OptIn(InternalResourceApi::class)
object AppIcons {
    val Classroom: DrawableResource = DrawableResource(
        "drawable:kewu_class",
        setOf(ResourceItem(setOf(), "composeResources/kewu.composeapp.generated.resources/drawable/kewu_class.xml", -1, -1)),
    )
    val Assistant: DrawableResource = DrawableResource(
        "drawable:kewu_assistant",
        setOf(ResourceItem(setOf(), "composeResources/kewu.composeapp.generated.resources/drawable/kewu_assistant.xml", -1, -1)),
    )
    val Task: DrawableResource = DrawableResource(
        "drawable:kewu_task",
        setOf(ResourceItem(setOf(), "composeResources/kewu.composeapp.generated.resources/drawable/kewu_task.xml", -1, -1)),
    )
}
