package com.example.dopaminecut2.service

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/** Fits the modal before measuring, including rotations and resized windows. */
class GoalInterventionLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (childCount > 0) {
            val density = resources.displayMetrics.density
            val margin = (48 * density).roundToInt()
            val width = (View.MeasureSpec.getSize(widthMeasureSpec) - margin).coerceAtLeast(1)
            val height = (View.MeasureSpec.getSize(heightMeasureSpec) - margin).coerceAtLeast(1)
            val card = getChildAt(0)
            val cardWidth = min((360 * density).roundToInt(), width)
            card.measure(View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            card.layoutParams.width = cardWidth
            card.layoutParams.height = min(card.measuredHeight, height)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
