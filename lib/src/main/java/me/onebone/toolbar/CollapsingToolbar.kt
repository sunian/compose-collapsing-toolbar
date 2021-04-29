/*
 * Copyright (c) 2021 onebone <me@onebone.me>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */

package me.onebone.toolbar

import androidx.annotation.FloatRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

fun interface CollapsingToolbarHeightChangeListener {
	fun onChange(minHeight: Int, maxHeight: Int)
}

fun interface CollapsingToolbarVisibleHeightChangeListener {
	fun onChange(height: Int)
}

data class CollapsingToolbarState(
	/**
	 * [minHeight] indicates the height when a toolbar is collapsed
	 */
	var minHeight: Int,
	/**
	 * [maxHeight] indicates the height when a toolbar is expanded
	 */
	var maxHeight: Int,
	/**
	 * [height] indicates current height
	 */
	var height: Int,
	var onHeightChange: CollapsingToolbarHeightChangeListener? = null,
	var onVisibleHeightChange: CollapsingToolbarVisibleHeightChangeListener? = null
) {
	val progress: Float
		@FloatRange(from = 0.0, to = 1.0)
		get() = ((height - minHeight).toFloat() / (maxHeight - minHeight)).coerceIn(0f, 1f)
}

@Composable
fun rememberCollapsingToolbarState(listener: CollapsingToolbarHeightChangeListener? = null): CollapsingToolbarState {
	return remember { CollapsingToolbarState(0, 0, 0, listener) }
}

@Composable
fun CollapsingToolbar(
	modifier: Modifier = Modifier,
	collapsingToolbarState: CollapsingToolbarState = rememberCollapsingToolbarState(),
	content: @Composable CollapsingToolbarScope.() -> Unit
) {
	val state = rememberUpdatedState(collapsingToolbarState)

	val measurePolicy = remember { CollapsingToolbarMeasurePolicy(state) }

	Layout(
		content = { CollapsingToolbarScopeInstance.content() },
		measurePolicy = measurePolicy,
		modifier = modifier
	)
}

private class CollapsingToolbarMeasurePolicy(
	private val collapsingToolbarState: State<CollapsingToolbarState>
): MeasurePolicy {
	override fun MeasureScope.measure(
		measurables: List<Measurable>,
		constraints: Constraints
	): MeasureResult {
		val placeStrategy = arrayOfNulls<Any>(measurables.size)

		var width = 0
		var height = 0

		var minHeight = Int.MAX_VALUE
		var maxHeight = 0

		val placeables = measurables.mapIndexed { i, measurable ->
			// measure with no height constraints
			val placeable = measurable.measure(
				constraints.copy(
					minHeight = 0,
					maxHeight = Constraints.Infinity
				)
			)
			placeStrategy[i] = measurable.parentData

			width = max(placeable.width, width)
			height = max(placeable.height, height)

			minHeight = min(minHeight, placeable.measuredHeight)
			maxHeight = max(maxHeight, placeable.measuredHeight)

			placeable
		}

		width = width.coerceIn(constraints.minWidth, constraints.maxWidth)
		height = height.coerceIn(constraints.minHeight, constraints.maxHeight)

		collapsingToolbarState.value.also {
			if(it.minHeight != minHeight || it.maxHeight != maxHeight) {
				it.onHeightChange?.onChange(minHeight, maxHeight)
			}

			if(it.height != height) {
				it.onVisibleHeightChange?.onChange(height)
			}

			it.minHeight = minHeight
			it.maxHeight = maxHeight
			it.height = height
		}

		return layout(width, height) {
			placeables.forEachIndexed { i, placeable ->
				when(val strategy = placeStrategy[i]) {
					is CollapsingToolbarRoadData -> {
						val collapsed = strategy.whenCollapsed
						val expanded = strategy.whenExpanded

						val collapsedOffset = collapsed.align(
							size = IntSize(placeable.width, placeable.height),
							space = IntSize(width, height),
							// TODO LayoutDirection
							layoutDirection = LayoutDirection.Ltr
						)

						val expandedOffset = expanded.align(
							size = IntSize(placeable.width, placeable.height),
							space = IntSize(width, height),
							// TODO LayoutDirection
							layoutDirection = LayoutDirection.Ltr
						)

						val progress = collapsingToolbarState.value.progress
						val offset = collapsedOffset + (expandedOffset - collapsedOffset) * progress
						placeable.place(offset.x, offset.y)
					}
					// TODO parallax
					CollapsingToolbarParallaxData -> placeable.place(0, 0)
					else -> placeable.place(0, 0)
				}
			}
		}
	}
}

interface CollapsingToolbarScope {
	fun Modifier.road(whenCollapsed: Alignment, whenExpanded: Alignment): Modifier

	fun Modifier.parallax(): Modifier

	fun Modifier.pin(): Modifier
}

object CollapsingToolbarScopeInstance: CollapsingToolbarScope {
	override fun Modifier.road(whenCollapsed: Alignment, whenExpanded: Alignment): Modifier {
		return this.then(RoadModifier(whenCollapsed, whenExpanded))
	}

	override fun Modifier.parallax(): Modifier {
		return this.then(ParallaxModifier())
	}

	override fun Modifier.pin(): Modifier {
		return this.then(PinModifier())
	}
}

internal class RoadModifier(
	private val whenCollapsed: Alignment,
	private val whenExpanded: Alignment
): ParentDataModifier {
	override fun Density.modifyParentData(parentData: Any?): Any {
		return CollapsingToolbarRoadData(
			this@RoadModifier.whenCollapsed, this@RoadModifier.whenExpanded
		)
	}
}

internal class ParallaxModifier: ParentDataModifier {
	override fun Density.modifyParentData(parentData: Any?): Any {
		return CollapsingToolbarParallaxData
	}
}

internal class PinModifier: ParentDataModifier {
	override fun Density.modifyParentData(parentData: Any?): Any {
		return CollapsingToolbarPinData
	}
}

internal data class CollapsingToolbarRoadData(
	var whenCollapsed: Alignment,
	var whenExpanded: Alignment
)

internal object CollapsingToolbarPinData
internal object CollapsingToolbarParallaxData
