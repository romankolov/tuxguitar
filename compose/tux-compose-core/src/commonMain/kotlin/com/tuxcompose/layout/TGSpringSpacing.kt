// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import kotlin.math.log2
import kotlin.math.pow

/**
 * Independently implemented spring/rod water filling. Rhythmic springs have
 * positive compliance (weight); glyph rods are hard minimums. Unlike a squeezing
 * renderer, a target below the rods returns the minimum and lets page wrapping
 * fragment the measure. It NEVER scales notation or violates a glyph minimum.
 */
internal fun distributeSprings(minima: List<Float>, weights: List<Double>, target: Float): List<Float> {
    require(minima.size==weights.size && target.isFinite())
    require(minima.all {it.isFinite() && it>=0f} && weights.all {it.isFinite() && it>0.0})
    if(minima.isEmpty()) return emptyList()
    val minimum=minima.sumOf {it.toDouble()}
    if(target<=minimum) return minima.toList()
    val order=minima.indices.sortedBy {minima[it]/weights[it]}
    var fixed=minimum
    var compliance=0.0
    var force=0.0
    for(i in order) {
        val threshold=minima[i]/weights[i]
        if(compliance>0.0 && fixed+threshold*compliance>=target) break
        fixed-=minima[i];compliance+=weights[i]
        force=(target-fixed)/compliance
    }
    return minima.indices.map {maxOf(minima[it].toDouble(),force*weights[it]).toFloat()}
}

internal data class TGElasticSpacing(val ticks: List<Long>,val minima: List<Float>,val weights: List<Double>) {
    val width: Float get() = minima.sum()
    fun offsets(extra: Float=0f): Map<Long,Float> {
        val widths=distributeSprings(minima,weights,width+maxOf(0f,extra))
        var x=0f
        return ticks.indices.associate {i->(ticks[i] to x).also {x+=widths[i]}}
    }
}

/** Shared onsets across voices/tracks, but glyph/caption lanes remain independent. */
internal fun elasticMeasureSpacing(start: Long,end: Long,durations: Map<Long,Long>,ink: List<TGMeasureInk>,
    reference: Long,ratio: Float,minimumDurationWidth: Float): Pair<TGMeasureSpacing,TGElasticSpacing> {
    require(reference>0 && ratio.isFinite() && ratio in 1.2f..2f)
    val ticks=(durations.keys+start).distinct().sorted()
    val exponent=log2(ratio.toDouble())
    val weights=ticks.mapIndexed {i,tick->
        val gap=(ticks.getOrNull(i+1)?.minus(tick) ?: (end-tick)).coerceAtLeast(1L)
        val shortest=(durations[tick] ?: gap).coerceAtLeast(1L)
        gap.toDouble()/shortest*(gap.toDouble()/reference).pow(exponent)*minimumDurationWidth
    }
    val widths=weights.map {it.toFloat()}
    var x=0f
    val base=ticks.indices.associate {i->(ticks[i] to x).also {x+=widths[i]}}
    val safe=balanceMeasureSpacing(base,x,end,ink)
    // Preserve all solved cross-lane constraints when distributing row slack.
    // They are hard rods, even for captions attached across several onsets.
    val floors=ticks.indices.map {i->
        ((ticks.getOrNull(i+1)?.let {safe.offsets.getValue(it)} ?: safe.rhythm)-safe.offsets.getValue(ticks[i])).coerceAtLeast(0f)
    }
    return safe to TGElasticSpacing(ticks,floors,weights)
}
