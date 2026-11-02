// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

/** Direct port of TGTrackSpacing's 19 lanes and its SCORE/TAB/MIXED permutations. */
enum class TGTrackLane {
    Top, LoopMarker, Marker, Text, Buffer, RepeatEnding, Chord, ScoreUp, Score,
    ScoreDown, Tuplet1, Tuplet2, Effects, PickStroke, TabSeparator, Tab, Lyric, Bottom, Bend,
}

class TGSpacing(style: TGLayoutStyles, private val metricScale: Float = 1f) {
    private val order = when (style) {
        TGLayoutStyles.Score -> intArrayOf(0,1,2,3,4,5,6,11,12,13,9,14,7,8,15,16,17,18,10)
        TGLayoutStyles.Tablature -> intArrayOf(0,1,2,3,4,5,6,16,17,18,9,13,7,8,11,12,14,15,10)
        TGLayoutStyles.Mixed -> intArrayOf(0,1,2,3,4,5,6,8,9,10,7,11,12,13,15,16,17,18,14)
    }
    init { require(metricScale > 0f && metricScale.isFinite()) }
    private val sizes = FloatArray(19)
    fun register(lane: TGTrackLane, size: Float) = registerPhysical(lane,size*metricScale)
    internal fun registerPhysical(lane: TGTrackLane, size: Float) { sizes[order[lane.ordinal]] = maxOf(sizes[order[lane.ordinal]], size) }
    fun size(lane: TGTrackLane): Float = sizes[order[lane.ordinal]]/metricScale
    internal fun physicalPosition(lane: TGTrackLane): Float = (0 until order[lane.ordinal]).fold(0f) { sum,i -> sum+sizes[i] }
    fun position(lane: TGTrackLane): Float = physicalPosition(lane)/metricScale
    internal val physicalHeight: Float get() = sizes.sum()
    val height: Float get() = physicalHeight/metricScale
}
