package com.kewu.app.ui.liquidglass

import androidx.compose.runtime.Composable
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop

/**
 * 创建一块用于记录「身后内容」的玻璃层，供 [KewuGlassCard] 采样模糊。
 *
 * ★ coui 硬约束：玻璃 sampler（[KewuGlassCard] 内部的 `drawBackdrop`）必须位于被采样层
 * （挂了 `layerBackdrop` 的 Box）**之外**，且 sampler 不能被 `alpha()` / `graphicsLayer` 包裹，
 * 否则首帧 RenderThread SIGSEGV。本 app 所有玻璃内容页都遵守这一结构。
 */
@Composable
fun rememberGlassBackdrop(): LayerBackdrop = rememberLayerBackdrop()
