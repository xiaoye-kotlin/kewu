package com.kewu.app.ui.icons

import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.ResourceItem

// 三个底部标签的图标，直接从 composeResources 里的矢量图取
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
