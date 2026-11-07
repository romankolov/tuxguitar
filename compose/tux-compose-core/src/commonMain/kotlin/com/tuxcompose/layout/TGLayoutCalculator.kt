// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.ui.geometry.Rect
import com.tuxcompose.model.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/**
 * Port of TGLayout -> TGMeasureHeaderImpl -> TGMeasureImpl -> TGLayoutVertical/Horizontal.
 * Geometry is computed once per score/style/viewport, never during a cursor frame.
 */
class TGLayoutCalculator(private val config: TGLayoutConfig = TGLayoutConfig(), private val fonts: TGFontMetrics = TGFontMetrics.Approximate) {
    fun durationWidth(duration: TGDuration): Float = config.durationWidths[minOf(duration.index, config.durationWidths.lastIndex)]
    /** Exact TGLayout.getSpacingForQuarter (including legacy integer tick rounding). */
    fun spacingForQuarter(duration: TGDuration): Float = TGDuration.QUARTER_TIME.toFloat() / duration.ticks * durationWidth(duration)

    fun calculate(song: TGSong, viewportWidth: Float, selectedTrackIndex: Int = 0): TGSongLayout {
        require(viewportWidth.isFinite() && viewportWidth > 0)
        if (song.tracks.isEmpty()) return TGSongLayout(viewportWidth,0f,emptyList())
        val indices = if (config.multiTrack) song.tracks.indices.toList() else listOf(selectedTrackIndex.coerceIn(song.tracks.indices))
        val tracks = indices.map { song.tracks[it] }
        if (config.multiTrack) validateAlignment(tracks)
        val count = tracks.maxOf { it.measures.size }
        if (count == 0) return TGSongLayout(viewportWidth,0f,emptyList())
        val compact = config.compact && tracks.size == 1
        // Native headers collect clef/key maxima from ALL song tracks, even in selected-track view.
        val sourceTracks = if (config.nativeSpacing) song.tracks.toList() else tracks
        val sourceMetrics = sourceTracks.map { track -> track.measures.mapIndexed { i,m -> measureMetrics(track,m,track.measures.getOrNull(i-1),compact,false) } }
        val metrics = if (config.nativeSpacing) indices.map { sourceMetrics[it] } else sourceMetrics
        val fitPage = config.orientation == TGLayoutOrientation.Page && config.fitPageToWidth
        // At narrow widths shed optional BLANK margins before allowing an indivisible header
        // or glyph to be clipped. Never change font, note-head, staff or string dimensions.
        val minimumBeat = metrics.flatten().flatMap { it.beatWidths }.minOrNull() ?: config.durationWidths.min()
        val indivisible = if (config.nativeSpacing) 0f else metrics.flatten().maxOf { metric ->
            metric.ink.lanes.maxOfOrNull { lane -> lane.maxOf { it.right+it.gap } } ?: 0f
        }
        val minimum = metrics.flatten().maxOf { it.first }+maxOf(minimumBeat,indivisible)
        val measurePadding = if (fitPage) minOf(config.measurePadding,((viewportWidth-minimum)/2).coerceAtLeast(0f)) else config.measurePadding
        val gutterBudget = (viewportWidth-minimum-2*measurePadding).coerceAtLeast(0f)
        val leftMargin = if (fitPage) minOf(config.pagePadding,gutterBudget/2) else config.pagePadding
        val rightMargin = if (config.nativeSpacing) {
            val requested = config.pageRightPadding/config.renderScale
            if (fitPage) minOf(requested,(gutterBudget-leftMargin).coerceAtLeast(0f)) else requested
        } else leftMargin
        val padding = if (config.nativeSpacing) nativeRound(leftMargin) else leftMargin
        val available = (viewportWidth-leftMargin-rightMargin).coerceAtLeast(1f)
        fun column(index: Int, first: Boolean): Column {
            val measures = tracks.map { it.measures[index] }
            var local = tracks.mapIndexed { ti,t ->
                if (first && config.repeatSystemSignatures) measureMetrics(t,measures[ti],t.measures.getOrNull(index-1),compact,true) else metrics[ti][index]
            }
            if (config.nativeSpacing) {
                val headerMetrics = sourceTracks.mapIndexedNotNull { ti,t ->
                    t.measures.getOrNull(index)?.takeIf { it.start == measures[0].start }?.let { m ->
                        if (first && config.repeatSystemSignatures) measureMetrics(t,m,t.measures.getOrNull(index-1),compact,true) else sourceMetrics[ti][index]
                    }
                }
                val maxClef = headerMetrics.maxOf { it.clef }
                val maxKey = headerMetrics.maxOf { it.key }
                local = local.map { metric ->
                    val clef = if (metric.paintClef || config.multiTrack) maxClef else 0f
                    val key = if (metric.paintKey || config.multiTrack) maxKey else 0f
                    metric.copy(clef=clef,key=key,first=nativePrefix(metric,clef,key))
                }
                val q = local.maxOf { it.quarter }
                val effects = local.maxOf { it.effect }
                val rhythms = local.map { metric ->
                    if (compact) metric.beatWidths.sum().takeIf { it > 0f } ?: (metric.quarter*metric.quarters)
                    else (if (config.multiTrack) q else metric.quarter)*metric.quarters+(if (config.multiTrack) effects else metric.effect)
                }
                val maps = measures.mapIndexed { ti,m ->
                    nativeBeatOffsets(m,local[ti].native!!,compact,if (config.multiTrack) q else local[ti].quarter,effects,config.multiTrack)
                }
                // This envelope is for exceptional fragment cuts ONLY. Ordinary bars retain
                // upstream per-track positions, including its effect-slack distribution.
                val offsets = mutableMapOf<Long,Float>()
                val inkRights = mutableMapOf<Long,Float>()
                val beatEffects = mutableMapOf<Long,Float>()
                maps.forEachIndexed { ti,map ->
                    map.forEach { (tick,x) -> offsets[tick] = maxOf(offsets[tick] ?: 0f,x) }
                    local[ti].ink.lanes.flatten().forEach { ink ->
                        inkRights[ink.tick] = maxOf(inkRights[ink.tick] ?: 0f,(map[ink.tick] ?: 0f)+ink.right+ink.gap)
                    }
                    measures[ti].beats.forEachIndexed { bi,b -> beatEffects[b.start] = maxOf(beatEffects[b.start] ?: 0f,local[ti].beatEffects[bi]) }
                }
                val prefix = local.maxOf { it.first }
                val repeat = local.maxOf { it.repeatWidth }
                val rhythm = rhythms.max()
                return Column(index,rhythm+prefix+2*measurePadding+repeat,q,effects,prefix,maxClef,maxKey,
                    local,local.maxOf { it.quarters },rhythm,repeat,offsets,beatEffects,inkRights,
                    local.maxOf { it.ink.continuationRight },nativeOffsets=maps)
            }
            val q = local.maxOf { it.quarter }
            val quarters = local.maxOf { it.quarters }
            val prefix = local.maxOf { it.first }
            // Share effect slack at its ACTUAL onset, not uniformly over the bar. Simultaneous
            // beats on different tracks must remain aligned even when only one track bends.
            val effects = mutableMapOf<Long,Float>()
            measures.forEachIndexed { ti,m -> m.beats.forEachIndexed { bi,b ->
                effects[b.start] = maxOf(effects[b.start] ?: 0f,local[ti].beatEffects[bi])
            } }
            val starts = measures.flatMap { it.beats }.groupBy { it.start }.entries.sortedBy { it.key }
            var effect = 0f
            var compactX = 0f
            var compactBeat = 0
            val offsets = mutableMapOf<Long,Float>()
            offsets[measures[0].start] = 0f
            starts.forEach { (tick,beats) ->
                val relative = (beats.first().startQuarters-measures[0].start.toDouble()/960).toFloat().coerceAtLeast(0f)
                offsets[tick] = if (compact) compactX else relative*q+effect
                if (compact) compactX += local[0].beatWidths[compactBeat++]
                effect += effects[tick] ?: 0f
            }
            val naturalRhythm = if (compact) local[0].beatWidths.sum() else q*quarters+effect
            val spacing = if (config.optimizeSpacing) balanceMeasureSpacing(offsets,naturalRhythm,
                measures.maxOf { it.end },local.map { it.ink }) else TGMeasureSpacing(offsets,naturalRhythm,emptyMap())
            val repeat = local.maxOf { it.repeatWidth }
            return Column(index,spacing.rhythm+prefix+2*measurePadding+repeat,q,effect,prefix,
                local.maxOf { it.clef },local.maxOf { it.key },local,quarters,spacing.rhythm,repeat,spacing.offsets,effects,spacing.inkRights,
                local.maxOf { it.ink.continuationRight })
        }
        fun continuation(col: Column): List<Metric> = tracks.mapIndexed { ti,t ->
            val m = t.measures[col.index]
            if (config.repeatSystemSignatures) {
                val metric = measureMetrics(t,m,t.measures.getOrNull(col.index-1),compact,true)
                metric.copy(paintTempo=false,paintTripletFeel=false,first=round(metric.clef+metric.key+(if (metric.paintTime) 10f+config.timeSignatureSpacing else 0f)+config.firstNoteSpacing)+metric.ink.leading)
            } else col.metrics[ti].copy(first=config.firstNoteSpacing+col.metrics[ti].ink.leading,clef=0f,key=0f,paintClef=false,paintKey=false,paintTime=false,paintTempo=false,paintTripletFeel=false,previousKey=0)
        }
        val systems = mutableListOf<List<Column>>()
        val filled = mutableListOf<Boolean>()
        var next = 0
        while (next < count) {
            val row = mutableListOf<Column>()
            var used = 0f
            var full = false
            while (next < count) {
                val col = column(next,row.isEmpty())
                val lineBreak = row.isNotEmpty() && tracks.any { it.measures[next].lineBreak }
                if (config.orientation == TGLayoutOrientation.Page && lineBreak) {
                    if (config.nativeSpacing) full = used+col.width >= available
                    break
                }
                if (fitPage && col.width > available) {
                    val cont = continuation(col)
                    val contPrefix = cont.maxOf { it.first }
                    val start = tracks[0].measures[next].start
                    val end = tracks.maxOf { it.measures[next].end }
                    val wrapping = if (config.nativeSpacing && col.nativeOffsets.size > 1)
                        nativeFragmentSpacing(col,tracks.map { it.measures[next] }) else TGMeasureSpacing(col.offsets,col.rhythm,col.inkRights)
                    val fragments = wrapPageMeasure(start,end,wrapping.offsets,wrapping.rhythm,col.effects,available,col.first,contPrefix,measurePadding,col.repeatWidth,compact,
                        tracks[0].measures[next].header.timeSignature.divisionTicks,wrapping.inkRights,col.continuationRight)
                    val pieces = fragments.mapIndexed { fi,fragment ->
                        val local = if (fi == 0) col.metrics else cont
                        val prefix = if (fi == 0) col.first else contPrefix
                        val width = 2*measurePadding+prefix+fragment.contentToX-fragment.fromX+(if (fi == fragments.lastIndex) col.repeatWidth else 0f)
                        col.copy(width=width,first=prefix,clef=local.maxOf { it.clef },key=local.maxOf { it.key },
                            metrics=local,fragment=fragment,original=col)
                    }
                    // TGLayoutVertical's greedy packing applies to the projected widths too.
                    // Retry at a new row before committing a piece, so repeated signatures are
                    // recalculated there. Never force a new row AFTER the last continuation.
                    if (row.isNotEmpty() && used+pieces.first().width >= available) {
                        full = true
                        break
                    }
                    pieces.forEachIndexed { fi,piece ->
                        if (fi > 0) {
                            systems += row.toList(); filled += used <= available
                            row.clear(); used = 0f
                        }
                        row += piece; used += piece.width
                    }
                    next++
                    continue
                }
                if (config.orientation == TGLayoutOrientation.Page && row.isNotEmpty() && used+col.width >= available) {
                    full = true
                    break
                }
                row += col; used += col.width; next++
                if (config.orientation == TGLayoutOrientation.Page && used >= available) { full = true; break }
            }
            if (row.isNotEmpty()) { systems += row; filled += full }
        }
        val ringIndices = tracks.map { TGLetRingIndex(if (config.mergeLetRing) letRingRuns(it) else emptyList()) }
        val staves = mutableListOf<TGStaffLayout>()
        val fragments = mutableMapOf<Pair<Int,Int>,TGMeasureFragments>()
        var top = if (config.nativeSpacing) nativeRound(config.firstTrackSpacing) else padding
        var maxRight = viewportWidth
        systems.forEachIndexed { si,columns ->
            val difference = (available-columns.sumOf { it.width.toDouble() }.toFloat())/columns.size
            val extra = if (config.orientation == TGLayoutOrientation.Page && filled[si] && config.justifySystems)
                if (config.nativeSpacing) difference else difference.coerceAtLeast(0f) else 0f
            tracks.forEachIndexed { ti,track ->
                val lanes = staffSpacing(track,columns.map { track.measures[it.index] })
                fun place(): List<TGMeasureLayout> {
                    var left = padding
                    return columns.map { col ->
                        val m = track.measures[col.index]
                        val metric = col.metrics[ti]
                        val bounds = Rect(left,top,left+col.width+extra,top+lanes.height)
                        val placed = if (col.fragment == null) placeMeasure(track,indices[ti],col.index,m,col,metric,bounds,lanes,compact,extra,measurePadding,ti)
                        else {
                            val original = col.original!!
                            val cached = fragments.getOrPut(indices[ti] to col.index) {
                                TGMeasureFragments(placeMeasure(track,indices[ti],col.index,m,original,original.metrics[ti],
                                    Rect(0f,0f,original.width,lanes.height),lanes,compact,0f,measurePadding,ti))
                            }
                            val slice = if (config.nativeSpacing && original.nativeOffsets.size > 1)
                                trackFragment(col.fragment,original,ti,m) else col.fragment
                            cached.part(slice,bounds,TGFragmentHeader(col.first,if (config.multiTrack && metric.paintClef) col.clef else metric.clef,
                                if (config.multiTrack && metric.paintKey) col.key else metric.key,metric.previousKey,
                                metric.paintClef,metric.paintKey,metric.paintTime,metric.paintTempo),measurePadding,extra,lanes)
                        }
                        left += col.width+extra
                        placed
                    }
                }
                var measures = place()
                val naturalRight = measures.last().bounds.right
                val annotationRight = if (fitPage) viewportWidth-rightMargin else maxOf(naturalRight,viewportWidth-rightMargin)
                val runs = ringIndices[ti].intersecting(measures.first().startTick,measures.last().endTick)
                val annotations = planAnnotations(measures,runs,padding,annotationRight,config,fonts)
                if (annotations != null) {
                    lanes.register(TGTrackLane.Text,annotations.textHeight)
                    lanes.register(TGTrackLane.Effects,annotations.effectHeight)
                    measures = place().map { it.copy(plannedAnnotations=true) }
                }
                val placedAnnotations = annotations?.placed(measures.first().textTop,measures.first().effectsTop).orEmpty()
                val inkRight = placedAnnotations.maxOfOrNull { it.bounds.right } ?: naturalRight
                val left = maxOf(naturalRight,inkRight)
                val right = if (fitPage) minOf(left,viewportWidth) else left
                staves += TGStaffLayout(si,indices[ti],Rect(padding,top,right,top+lanes.height),measures,annotations=placedAnnotations)
                if (!fitPage) maxRight = maxOf(maxRight,if (config.nativeSpacing && config.orientation == TGLayoutOrientation.Horizontal)
                    if (left-padding+leftMargin > viewportWidth) left-padding+leftMargin+config.pagePadding else viewportWidth else left+rightMargin)
                top += if (config.nativeSpacing) kotlin.math.floor(lanes.physicalHeight+config.systemSpacing*config.renderScale+.5f+.5f)/config.renderScale else lanes.height+config.systemSpacing
            }
        }
        return TGSongLayout(maxRight,(if (config.nativeSpacing) top+config.firstTrackSpacing-nativeRound(config.firstTrackSpacing) else top-config.systemSpacing+padding).coerceAtLeast(0f),connectStaves(staves,config))
    }

    /** Java Math.round, in physical pixels, converted back to logical canvas coordinates. */
    private fun nativeRound(value: Float): Float = kotlin.math.floor(value*config.renderScale+.5f)/config.renderScale

    private data class NativeSilence(val y: Float, val height: Float, val hidden: Boolean)

    /** TGMeasureImpl hidden-silence decisions followed by TGVoiceImpl.updateSilenceSpacing. */
    private fun nativeSilences(track: TGTrack, m: TGMeasure, groups: Map<Pair<Int,Int>,Group>, metricScale: Float = 1f): Map<Pair<Int,Int>,NativeSilence> = buildMap {
        m.beats.forEachIndexed { bi,b ->
            val voices = b.voices.filterNot { it.empty }.sortedBy { it.index }
            val hidden = mutableSetOf<Int>()
            voices.filter { it.notes.isEmpty() }.forEach { v ->
                if (voices.any { other -> other.index != v.index && (other.duration ?: b.duration) == (v.duration ?: b.duration) &&
                        (other.notes.isNotEmpty() || other.index !in hidden) }) hidden += v.index
            }
            voices.filter { it.notes.isEmpty() }.forEach { v ->
                val line = (if (config.showStandardNotation) config.staffSpacing else config.stringSpacing)*metricScale
                val count = if (config.showStandardNotation) 5 else track.stringTuning.size
                val scale = line/if (config.showStandardNotation) 9f else 10f
                val d = (v.duration ?: b.duration).value
                val h = scale*when (d) { 1,2 -> 3.6513581f; 4,16 -> 16f; 8 -> 12f; 32 -> 24f; else -> 28f }
                var y = when (d) {
                    1 -> if (config.showStandardNotation) line else line*((count-1)/2)
                    2 -> (if (config.showStandardNotation) 2*line else line*(count/2))-h
                    else -> line*(count-1)/2-h/2
                }
                voices.filter { it.index != v.index }.forEach { other ->
                    if (other.notes.isEmpty()) {
                        if (other.index !in hidden) y = y-3*line/maxOf(2,voices.maxOf { it.index }+1)+3*line*v.index
                    } else {
                        val up = groups.getValue(bi to other.index).up
                        if (config.showStandardNotation) {
                            val positions = other.notes.map { notePosition(track,m,it)*line/2 }
                            y = if (up) maxOf(y,positions.max()+2*line) else minOf(y,positions.min()-h-line)
                        } else y = if (up) maxOf(y,line*other.notes.maxOf { it.string }) else minOf(y,line*(other.notes.minOf { it.string }-1)-h-line)
                    }
                }
                put(bi to v.index,NativeSilence(y,h,v.index in hidden))
            }
        }
    }

    private fun nativePrefix(metric: Metric, clef: Float, key: Float): Float = nativeRound(maxOf(
        (if (metric.paintTempo) nativeRound(45f) else 0f)+(if (metric.paintTripletFeel) nativeRound(55f) else 0f),
        clef+key+(if (metric.paintTime) nativeRound(10f+config.timeSignatureSpacing) else 0f)+config.firstNoteSpacing))

    /**
     * TGMeasureImpl.checkValue and TGLayoutVertical registration, using actual stem directions.
     * Accumulate PHYSICAL floats, as Java does: a logical round-trip at 1.25x can move the
     * track-height + .5 rounding across its threshold, shifting the following row by a pixel.
     */
    private fun nativeStaffSpacing(track: TGTrack, measures: List<TGMeasure>): TGSpacing {
        val scale = config.renderScale
        val staffSpacing = config.staffSpacing*scale
        val stringSpacing = config.stringSpacing*scale
        val lanes = TGSpacing(config.style,scale)
        var lineMinY = if (config.style == TGLayoutStyles.Score) -3*staffSpacing else 0f
        var lineMaxY = if (config.style == TGLayoutStyles.Score) 8*staffSpacing else 0f
        val pixel = 1f
        val tabHeight = (track.stringTuning.size-1)*stringSpacing
        measures.forEach { m ->
            var minY = 0f
            var maxY = 0f
            val groups = voiceGroups(track,m)
            val silences = nativeSilences(track,m,groups,scale)
            m.beats.forEachIndexed { bi,b ->
                val voices = b.voices.filterNot { it.empty }.sortedBy { it.index }
                voices.forEach { v ->
                    if (v.notes.isNotEmpty()) {
                        if (!config.showStandardNotation) {
                            val offset = stringSpacing/2*5+pixel
                            if (groups.getValue(bi to v.index).up) minY = minOf(minY,-offset)
                            else maxY = maxOf(maxY,tabHeight+offset)
                        }
                    } else {
                        val rest = silences.getValue(bi to v.index)
                        if (!rest.hidden) { minY = minOf(minY,rest.y); maxY = maxOf(maxY,rest.y+rest.height) }
                    }
                }
            }
            if (config.showStandardNotation) groups.values.distinctBy { it.id }.forEach { group ->
                val positions = group.positions.flatten()
                if (positions.isNotEmpty()) for (position in listOf(positions.min(),positions.max())) {
                    val y = position*staffSpacing/2
                    if (group.up) {
                        if (y > maxY) maxY = y
                        if (y-28f*(staffSpacing/8) < minY) minY = y-28f*(staffSpacing/8)-2*pixel
                    } else {
                        if (y+35f*(staffSpacing/8) > maxY) maxY = y+35f*(staffSpacing/8)+2*pixel
                        if (y < minY) minY = y
                    }
                }
            }
            lineMinY = minOf(lineMinY,minY)
            lineMaxY = maxOf(lineMaxY,maxY)
        }
        val minY = lineMinY
        val maxY = lineMaxY
        val beats = measures.flatMap { it.beats }
        if (measures.any { it.header.marker != null }) lanes.registerPhysical(TGTrackLane.Marker,15f*scale)
        if (beats.any { it.text != null }) lanes.registerPhysical(TGTrackLane.Text,15f*scale)
        if (config.chordEnabled && beats.any { it.chord != null }) lanes.registerPhysical(TGTrackLane.Chord,
            (if (config.showChords) 42f*scale else 0f)+(if (config.showChordNames) kotlin.math.floor(15f*scale+.5f) else 0f))
        if (measures.any { it.header.repeatAlternative > 0 }) lanes.registerPhysical(TGTrackLane.RepeatEnding,20f*scale)
        if (beats.any { b -> b.voices.any { !it.empty && it.index%2 == 0 && (it.duration ?: b.duration).division != TGDivisionType() } }) lanes.registerPhysical(TGTrackLane.Tuplet1,10f*scale)
        if (beats.any { b -> b.voices.any { !it.empty && it.index%2 == 1 && (it.duration ?: b.duration).division != TGDivisionType() } }) lanes.registerPhysical(TGTrackLane.Tuplet2,10f*scale)
        val effectHeight = beats.maxOfOrNull { b ->
            val effects = b.voices.filterNot { it.empty }.flatMap { it.notes }.map { it.effects }
            listOf(effects.any { it.accented },effects.any { it.heavyAccented },
                !config.showStandardNotation && effects.any { it.harmonic != 0 },effects.any { it.tapping },
                effects.any { it.slapping },effects.any { it.popping },effects.any { it.palmMute },
                effects.any { it.letRing },effects.any { it.fadeIn },effects.any { it.vibrato },effects.any { it.trill != null })
                .count { it }*(config.effectSpacing*scale)
        } ?: 0f
        lanes.registerPhysical(TGTrackLane.Effects,effectHeight)
        if (beats.any { it.pickStroke != TGStrokeDirection.None }) lanes.registerPhysical(TGTrackLane.PickStroke,config.pickStrokeSpacing*scale)
        if (config.showTablature && beats.any { b -> b.voices.any { v -> !v.empty && v.notes.any { it.effects.bend != null } } }) lanes.registerPhysical(TGTrackLane.Bend,8f*scale)
        if (config.showStandardNotation) {
            lanes.registerPhysical(TGTrackLane.ScoreUp,kotlin.math.abs(minY))
            lanes.registerPhysical(TGTrackLane.Score,5*staffSpacing)
            lanes.registerPhysical(TGTrackLane.ScoreDown,maxOf(0f,maxY-4*staffSpacing))
        }
        if (config.showTablature) {
            lanes.registerPhysical(TGTrackLane.TabSeparator,if (config.showStandardNotation) config.scoreTabSpacing*scale else maxOf(kotlin.math.abs(minY),stringSpacing))
            lanes.registerPhysical(TGTrackLane.Tab,if (config.showStandardNotation) tabHeight+stringSpacing+pixel else maxOf(maxY,tabHeight+stringSpacing+pixel))
        }
        lanes.registerPhysical(TGTrackLane.Lyric,10*pixel)
        val target = if (config.showStandardNotation) TGTrackLane.ScoreUp else TGTrackLane.Tab
        val separator = lanes.physicalPosition(target)-lanes.physicalPosition(TGTrackLane.Buffer)
        if (separator < 20f*scale) lanes.registerPhysical(TGTrackLane.Buffer,20f*scale-separator)
        val staffTarget = if (config.showStandardNotation) TGTrackLane.Score else TGTrackLane.Tab
        if (lanes.physicalPosition(staffTarget) < config.staffPadding*scale) lanes.registerPhysical(TGTrackLane.Top,config.staffPadding*scale-lanes.physicalPosition(staffTarget))
        return lanes
    }

    /** Native track coordinates at a common time cut. Used only by the wrapping extension. */
    private fun trackOffset(tick: Long, col: Column, ti: Int, m: TGMeasure): Float {
        val map = col.nativeOffsets[ti]
        map[tick]?.let { return it }
        if (tick >= m.end) return col.rhythm
        val before = map.keys.filter { it < tick }.maxOrNull() ?: m.start
        val after = map.keys.filter { it > tick }.minOrNull() ?: m.end
        val x1 = map[before] ?: 0f
        val x2 = map[after] ?: col.rhythm
        val bi = m.beats.indexOfFirst { it.start == before }
        val effect = col.metrics[ti].beatEffects.getOrElse(bi) { 0f }
        return x1+effect+maxOf(0f,x2-x1-effect)*(tick-before).toFloat()/(after-before).coerceAtLeast(1L)
    }

    private fun nativeFragmentSpacing(col: Column, measures: List<TGMeasure>): TGMeasureSpacing {
        val ticks = (col.offsets.keys+measures[0].start+measures.maxOf { it.end }).distinct().sorted()
        var x = 0f
        val offsets = mutableMapOf(ticks.first() to 0f)
        ticks.zipWithNext().forEach { (a,b) ->
            x += measures.indices.maxOf { ti -> maxOf(0f,trackOffset(b,col,ti,measures[ti])-trackOffset(a,col,ti,measures[ti])) }
            offsets[b] = x
        }
        val rights = mutableMapOf<Long,Float>()
        col.metrics.forEach { metric -> metric.ink.lanes.flatten().forEach { ink ->
            rights[ink.tick] = maxOf(rights[ink.tick] ?: 0f,offsets.getValue(ink.tick)+ink.right+ink.gap)
        } }
        return TGMeasureSpacing(offsets,x,rights)
    }

    private fun trackFragment(slice: TGPageFragment, col: Column, ti: Int, m: TGMeasure): TGPageFragment {
        val from = trackOffset(slice.startTick,col,ti,m)
        return slice.copy(fromX=from,toX=trackOffset(slice.endTick,col,ti,m),contentToX=from+slice.contentToX-slice.fromX)
    }

    private fun validateAlignment(tracks: List<TGTrack>) {
        val reference = tracks.first()
        require(tracks.all { it.measures.size == reference.measures.size }) { "Multi-track measures must have equal counts" }
        tracks.drop(1).forEach { track ->
            require(track.measures.zip(reference.measures).all { (a, b) -> a.start == b.start && a.header.timeSignature == b.header.timeSignature }) { "Multi-track headers must share starts and time signatures" }
        }
    }

    private data class Metric(val quarter: Float, val effect: Float, val first: Float, val clef: Float, val key: Float,
        val quarters: Float, val repeatWidth: Float, val beatWidths: List<Float>, val beatEffects: List<Float>,
        val paintClef: Boolean, val paintKey: Boolean, val paintTime: Boolean, val paintTempo: Boolean, val previousKey: Int, val ink: TGMeasureInk, val native: TGNativeSpacing? = null, val paintTripletFeel: Boolean = false)
    private data class Column(val index: Int, val width: Float, val quarter: Float, val effect: Float, val first: Float, val clef: Float, val key: Float, val metrics: List<Metric>,
        val quarters: Float, val rhythm: Float, val repeatWidth: Float, val offsets: Map<Long,Float>, val effects: Map<Long,Float>,
        val inkRights: Map<Long,Float>, val continuationRight: Float,
        val fragment: TGPageFragment? = null, val original: Column? = null,
        val nativeOffsets: List<Map<Long,Float>> = emptyList())

    private fun measureMetrics(track: TGTrack, m: TGMeasure, prev: TGMeasure?, compact: Boolean, systemFirst: Boolean): Metric {
        val score = config.showStandardNotation
        val paintClef = score && (prev == null || prev.clef != m.clef || systemFirst)
        val paintKey = score && (prev == null || prev.header.keySignature != m.header.keySignature || systemFirst)
        val paintTime = prev == null || prev.header.timeSignature != m.header.timeSignature || systemFirst
        val paintTempo = config.showTempo && (prev == null || prev.header.tempo != m.header.tempo ||
            (config.nativeSpacing && (prev.header.tempoMark ?: TGTempoMark(prev.header.tempo)) != (m.header.tempoMark ?: TGTempoMark(m.header.tempo))))
        val clef = if (paintClef) if (config.nativeSpacing) nativeRound(16f)+nativeRound(config.clefSpacing) else round(16f+config.clefSpacing) else 0f
        val previousKey = if (systemFirst) 0 else prev?.header?.keySignature?.value ?: 0
        val keyCount = m.header.keySignature.count + if (prev != null && !systemFirst) prev.header.keySignature.count else 0
        val key = if (paintKey && keyCount > 0) (if (config.nativeSpacing)
            nativeRound(6f*m.header.keySignature.count)+nativeRound(6f*(if (prev != null && !systemFirst) prev.header.keySignature.count else 0))
            else round(6f*keyCount))+config.keySignatureSpacing else 0f
        val time = if (paintTime) 10f + config.timeSignatureSpacing else 0f
        val first = round(maxOf(if (paintTempo) 45f else 0f, clef + key + time + config.firstNoteSpacing))
        if (config.nativeSpacing) {
            val native = nativeMeasureSpacing(track,m,config,compact)
            val metric = Metric(native.quarter,native.effects,first,clef,key,native.quarters,
                if (m.header.repeatClose > 0) 20f else 0f,native.beatWidths,native.beatEffects,
                paintClef,paintKey,paintTime,paintTempo,previousKey,
                measureInk(track,m,config,fonts,compact).copy(leading=0f),native,
                if (prev == null) m.header.tripletFeel != 0 else prev.header.tripletFeel != m.header.tripletFeel)
            return metric.copy(first=nativePrefix(metric,clef,key))
        }
        val durations = m.beats.flatMap { b -> b.voices.filterNot { it.empty }.map { it.duration ?: b.duration }.ifEmpty { listOf(b.duration) } }
        val minDuration = durations.minByOrNull { it.preciseTicks }
        var quarter = minDuration?.let(::spacingForQuarter) ?: config.durationWidths.min()
        val beatEffects = m.beats.map { beat ->
            val bend = beat.voices.flatMap { it.notes }.maxOfOrNull { note ->
                if (compact || !config.showTablature) 0f else (note.effects.bend?.movements()?.size ?: 0) * 10f
            } ?: 0f
            // Balanced spacing measures the arrow's actual envelope instead of adding its
            // full width on top of rhythmic space that already accommodates the mark.
            bend + if (!config.optimizeSpacing && beat.stroke.direction != TGStrokeDirection.None) strokeOffset(beat,config,fonts)+3f else 0f
        }
        val beatWidths = m.beats.mapIndexed { bi, beat ->
            val duration = beat.voices.filterNot { it.empty }.minByOrNull { (it.duration ?: beat.duration).preciseTicks }?.duration ?: beat.duration
            val textWidth = beat.voices.flatMap { it.notes }.maxOfOrNull { fonts.measure(tabText(it), TGTextRole.Note).width + 4f } ?: 0f
            val chord = beat.chord?.takeIf { config.chordEnabled }?.let { maxOf(8f+5f*(track.stringTuning.size+1),fonts.measure(it.name,TGTextRole.Chord).width+4) } ?: 0f
            val label = maxOf(beat.text?.let { fonts.measure(it,TGTextRole.Text).width+4 } ?: 0f,
                effectLabels(beat).filterNot { config.mergeLetRing && it == "let ring" }.maxOfOrNull { fonts.measure(it,TGTextRole.Text).width+4 } ?: 0f)
            val width = if (config.optimizeSpacing) maxOf(durationWidth(duration),if (config.showTablature) textWidth else 0f,
                if (config.showStandardNotation) config.noteHeadRadius*2.66f+4 else 0f)
                else maxOf(durationWidth(duration),textWidth,chord,label,if (config.showStandardNotation) config.noteHeadRadius*2.66f+4 else 0f)
            if (!config.optimizeSpacing && config.chordEnabled && beat.chord != null) quarter = maxOf(quarter, chord / duration.quarters.toFloat())
            width + beatEffects[bi]
        }
        // Use actual precise ending for invalid/overfull measures; empty bars reserve their meter.
        val endQuarters = m.beats.maxOfOrNull { b -> b.startQuarters - m.start.toDouble() / 960 +
            (b.voices.filterNot { it.empty }.maxOfOrNull { (it.duration ?: b.duration).quarters } ?: b.duration.quarters) }
        val quarters = ceil(maxOf(m.header.timeSignature.ticks / 960.0, endQuarters ?: 0.0)).toFloat()
        // Compatibility path: v0.1.3 promoted a single local collision to a whole-bar scale.
        // The default forward solver adds clearance only where actual visible ink needs it.
        if (!compact && !config.optimizeSpacing) m.beats.forEachIndexed { i, b ->
            val next = m.beats.getOrNull(i + 1)
            val distance = next?.let { it.startQuarters - b.startQuarters } ?: b.duration.quarters
            if (distance > 0) quarter = maxOf(quarter, (beatWidths[i] - beatEffects[i]) / distance.toFloat())
        }
        val ink = if (config.optimizeSpacing) measureInk(track,m,config,fonts,compact) else TGMeasureInk()
        return Metric(quarter, beatEffects.sum(), first+ink.leading, clef, key, quarters, if (m.header.repeatClose > 0) 20f else 0f, beatWidths, beatEffects, paintClef, paintKey, paintTime, paintTempo, previousKey, ink)
    }

    private fun staffSpacing(track: TGTrack, measures: List<TGMeasure>): TGSpacing {
        if (config.nativeSpacing) return nativeStaffSpacing(track,measures)
        val lanes = TGSpacing(config.style)
        lanes.register(TGTrackLane.Top, config.staffPadding)
        val allBeats = measures.flatMap { it.beats }
        val notes = allBeats.flatMap { it.voices }.flatMap { it.notes }
        if (measures.any { it.header.marker != null }) lanes.register(TGTrackLane.Marker, 15f)
        if (allBeats.any { it.text != null }) lanes.register(TGTrackLane.Text, maxOf(15f, fonts.measure("Mg", TGTextRole.Text).height))
        if (config.showChords && allBeats.any { it.chord != null }) lanes.register(TGTrackLane.Chord, 49f)
        if (measures.any { it.header.repeatAlternative > 0 }) lanes.register(TGTrackLane.RepeatEnding, 20f)
        if (allBeats.any { b -> b.voices.any { (it.duration ?: b.duration).division.enters != 1 && it.index == 0 } }) lanes.register(TGTrackLane.Tuplet1, 10f)
        if (allBeats.any { b -> b.voices.any { (it.duration ?: b.duration).division.enters != 1 && it.index != 0 } }) lanes.register(TGTrackLane.Tuplet2, 10f)
        val effectsHeight = allBeats.maxOfOrNull { b -> effectLabels(b).let { labels ->
            if (labels.isEmpty()) 0f else (labels.size-1)*config.effectSpacing+maxOf(7f,fonts.measure("Mg",TGTextRole.Text).height)
        } } ?: 0f
        lanes.register(TGTrackLane.Effects, effectsHeight)
        if (allBeats.any { it.pickStroke != TGStrokeDirection.None })
            lanes.register(TGTrackLane.PickStroke,maxOf(8f,config.pickStrokeSpacing))
        if (config.showTablature && notes.any { it.effects.bend != null || it.effects.tremoloBar != null }) lanes.register(TGTrackLane.Bend, 16f)
        if (config.showStandardNotation) {
            val positions = measures.flatMap { m -> m.beats.flatMap { it.voices }.flatMap { it.notes }.map { note -> notePosition(track, m, note) * config.staffSpacing / 2 } }
            // Same max/min registration strategy as TGLayoutVertical; reserve stem and ledger space.
            val minY = (positions.minOrNull() ?: 0f) - 28f * config.staffSpacing / 8f
            val maxY = (positions.maxOrNull() ?: (4 * config.staffSpacing)) + 35f * config.staffSpacing / 8f
            lanes.register(TGTrackLane.ScoreUp, maxOf(0f, -minY))
            lanes.register(TGTrackLane.Score, config.staffSpacing * 5)
            lanes.register(TGTrackLane.ScoreDown, (maxY - 4 * config.staffSpacing).coerceAtLeast(0f))
        }
        if (config.showTablature) {
            lanes.register(TGTrackLane.TabSeparator, if (config.showStandardNotation) config.scoreTabSpacing else config.stringSpacing)
            val tabHeight = (track.stringTuning.size - 1) * config.stringSpacing
            lanes.register(TGTrackLane.Tab, tabHeight + config.stringSpacing + 1 + if (config.showStandardNotation) 0f else config.stringSpacing * 2.5f)
        }
        lanes.register(TGTrackLane.Lyric, 10f)
        val bufferTarget = if (config.showStandardNotation) TGTrackLane.ScoreUp else TGTrackLane.Tab
        val buffer = lanes.position(bufferTarget) - lanes.position(TGTrackLane.Buffer)
        if (buffer < 20f) lanes.register(TGTrackLane.Buffer, 20f - buffer)
        val staffTarget = if (config.showStandardNotation) TGTrackLane.Score else TGTrackLane.Tab
        if (lanes.position(staffTarget) < config.staffPadding) lanes.register(TGTrackLane.Top, config.staffPadding - lanes.position(staffTarget))
        return lanes
    }

    private fun placeMeasure(track: TGTrack, trackIndex: Int, index: Int, m: TGMeasure, col: Column, metric: Metric, bounds: Rect, lanes: TGSpacing, compact: Boolean, extra: Float, measurePadding: Float = config.measurePadding, localTrackIndex: Int = 0): TGMeasureLayout {
        val score = if (config.showStandardNotation) bounds.top + lanes.position(TGTrackLane.Score) else null
        val tab = if (config.showTablature) bounds.top + lanes.position(TGTrackLane.Tab) else null
        val area = bounds.left+measurePadding+col.first
        val groups = voiceGroups(track, m)
        val silences = if (config.nativeSpacing) nativeSilences(track,m,groups) else emptyMap()
        val xs = m.beats.mapIndexed { bi,b ->
            // TGBeatImpl.getSpacing: justification changes rhythmic SPACE, never glyph size.
            val relative = (b.startQuarters-m.start.toDouble()/960).toFloat().coerceAtLeast(0f)
            if (config.nativeSpacing) area+nativeJustifiedOffset(col.nativeOffsets[localTrackIndex].getValue(b.start),
                col.width-col.first-2*measurePadding,extra,config.durationWidths.min())
            else area+col.offsets.getValue(b.start)+(if (compact) extra*bi/maxOf(m.beats.size,1) else extra*relative/col.quarters)
        }
        val accidentals = TGAccidentals(m.header.keySignature.value)
        val beats = m.beats.mapIndexed { bi, b ->
            val x = xs[bi]
            val nextX = xs.getOrNull(bi+1) ?: if (config.nativeSpacing) area+nativeJustifiedOffset(col.rhythm,
                col.width-col.first-2*measurePadding,extra,config.durationWidths.min()) else bounds.right-measurePadding-metric.repeatWidth
            val voices = b.voices.filterNot { it.empty }.map { v ->
                val duration = v.duration ?: b.duration
                val g = groups.getValue(bi to v.index)
                val up = g.up
                val notes = v.notes.map { note ->
                    val pitch = track.pitch(note)
                    val position = notePosition(track, m, note)
                    val size = fonts.measure(tabText(note), TGTextRole.Note)
                    val nh = 2 * config.noteHeadRadius
                    val y = score?.plus(position * config.staffSpacing / 2)
                    TGNoteLayout(note, v.index, pitch, position,
                        y?.let { val center = it + config.staffSpacing / 2; Rect(x, center - config.noteHeadRadius, x + nh * 1.33f, center + config.noteHeadRadius) },
                        tab?.let { top -> val ty = top + (note.string - 1) * config.stringSpacing; Rect(x - size.width / 2 - 1.5f, ty - size.height / 2, x + size.width / 2 + 1.5f, ty + size.height / 2) },
                        if (track.percussion || note.effects.dead || note.value == -1) null else accidentals.next(pitch, note.altEnharmonic))
                }
                val stemX = x + if (score != null && up) 2 * config.noteHeadRadius * 1.085f else 0f
                val localStart = if (score != null) {
                    if (up) notes.maxOfOrNull { it.position * config.staffSpacing / 2 + config.staffSpacing / 2 } ?: 2 * config.staffSpacing
                    else notes.minOfOrNull { it.position * config.staffSpacing / 2 + config.staffSpacing / 2 } ?: 2 * config.staffSpacing
                } else (track.stringTuning.size - 1) * config.stringSpacing + config.stringSpacing / 2
                val origin = score ?: tab ?: bounds.top
                val endY = if (score != null) groupStemY(g, xs, bi) else localStart + 2 * config.stringSpacing
                TGVoiceLayout(v,duration,notes,up,stemX,origin+localStart,origin+endY,g.id,sourceTick=b.start,
                    silenceY=silences[bi to v.index]?.let { origin+it.y },hiddenSilence=silences[bi to v.index]?.hidden ?: false)
            }
            val chordBounds = b.chord?.takeIf { config.chordEnabled }?.let { chord ->
                val y = bounds.top+lanes.position(TGTrackLane.Chord)
                if (config.nativeSpacing) {
                    val width = 8f+5f*(chord.frets.size+1)
                    val left = x+nativeRound(4f)-(width-8f)/2-8f
                    Rect(left,y,left+width,y+(if (config.showChords) 42f else 0f)+(if (config.showChordNames) nativeRound(15f) else 0f))
                } else Rect(x-4f,y,x+5f*track.stringTuning.size+8f,y+47f)
            }
            TGBeatLayout(b, Rect(maxOf(area, x - 5f), bounds.top, maxOf(x + 5f, nextX - 5f), bounds.bottom), x, voices, chordBounds,
                endX=nextX,endTick=m.beats.getOrNull(bi+1)?.start ?: m.end,
                stroke=strumLayout(b.stroke.direction,x,score,tab,(track.stringTuning.size-1)*config.stringSpacing,
                    config,if (b.stroke.direction == TGStrokeDirection.None) 12f else strokeOffset(b,config,fonts)),
                pickStroke=pickStrokeLayout(b.pickStroke,x,bounds.top+lanes.position(TGTrackLane.PickStroke)))
        }
        return TGMeasureLayout(trackIndex, index, m, bounds, score, tab, tab?.plus((track.stringTuning.size - 1) * config.stringSpacing),
            metric.paintClef, metric.paintKey, metric.paintTime, metric.paintTempo, metric.previousKey,
            if (config.multiTrack) col.clef else metric.clef, if (config.multiTrack) col.key else metric.key,
            area, beats, bounds.top + lanes.position(TGTrackLane.Effects), bounds.top + lanes.position(TGTrackLane.Bend),
            bounds.top + lanes.position(TGTrackLane.Text), bounds.top + lanes.position(TGTrackLane.Chord), bounds.top + lanes.position(TGTrackLane.RepeatEnding), bounds.top + lanes.position(TGTrackLane.Tuplet1), percussion=track.percussion,headerX=bounds.left+measurePadding,pickStrokeTop=bounds.top+lanes.position(TGTrackLane.PickStroke),compact=compact,
            paintTripletFeel=metric.paintTripletFeel,previousTripletFeel=if (metric.paintTripletFeel) track.measures.getOrNull(index-1)?.header?.tripletFeel ?: 0 else 0)
    }

    private data class Group(val id: Int, val beats: List<Int>, val positions: List<List<Int>>, val up: Boolean)
    private fun voiceGroups(track: TGTrack, m: TGMeasure): Map<Pair<Int, Int>, Group> {
        val result = mutableMapOf<Pair<Int, Int>, Group>()
        var id = 0
        val activeVoices = m.beats.flatMap { it.voices }.filter { !it.empty && (config.nativeSpacing || it.notes.isNotEmpty()) }.map { it.index }.distinct().size
        for (vi in 0..3) {
            val batches = mutableListOf<MutableList<Int>>()
            var previous: Int? = null
            m.beats.forEachIndexed { bi, b ->
                val voice = b.voices.find { it.index == vi && !it.empty } ?: return@forEachIndexed
                val before = previous?.let { m.beats[it] }
                val prevVoice = before?.voices?.find { it.index == vi }
                val division = m.header.timeSignature.divisionTicks
                val canJoin = before != null && prevVoice != null && prevVoice.notes.isNotEmpty() && voice.notes.isNotEmpty() &&
                    (prevVoice.duration ?: before.duration).value >= 8 && (voice.duration ?: b.duration).value >= 8 &&
                    (before.start - m.start) / division == (b.start - m.start) / division &&
                    (config.nativeSpacing || abs(before.start+(prevVoice.duration ?: before.duration).ticks-b.start) <= 1)
                if (!canJoin || batches.isEmpty()) batches += mutableListOf(bi) else batches.last() += bi
                previous = bi
            }
            batches.forEach { indices ->
                val positions = indices.map { bi -> m.beats[bi].voices.first { it.index == vi }.notes.map { notePosition(track, m, it) } }
                val all = positions.flatten()
                val middle = if (all.isEmpty()) 4 else all.min() + (all.max() - all.min()) / 2
                val explicit = indices.map { bi -> m.beats[bi].voices.first { it.index == vi }.stem }.lastOrNull { it != TGStemDirection.Auto }
                val up = when (explicit) { TGStemDirection.Up -> true; TGStemDirection.Down -> false; else -> if (activeVoices > 1) vi == 0 else config.showStandardNotation && middle > 3 }
                val group = Group(id++, indices, positions, up)
                indices.forEach { result[it to vi] = group }
            }
        }
        return result
    }

    /** TGBeatGroup.getY2: constrained ten-unit beam slope; interior extremes flatten the group. */
    private fun groupStemY(g: Group, xs: List<Float>, beatIndex: Int): Float {
        val positions = g.positions.map { p -> if (g.up) p.minOrNull() ?: 4 else p.maxOrNull() ?: 4 }
        val extreme = if (g.up) positions.min() else positions.max()
        val offset = (if (g.up) -28f else 35f) * config.staffSpacing / 8
        if (extreme != positions.first() && extreme != positions.last()) return extreme * config.staffSpacing / 2 + offset
        var y1 = positions.first() * config.staffSpacing / 2 + offset
        var y2 = positions.last() * config.staffSpacing / 2 + offset
        if (abs(y1 - y2) > 10f) {
            if (g.up) { if (y1 < y2) y2 = y1 + 10 else y1 = y2 + 10 }
            else { if (y1 > y2) y2 = y1 - 10 else y1 = y2 - 10 }
        }
        val x1 = xs[g.beats.first()]; val x2 = xs[g.beats.last()]
        return if (x2 == x1) y1 else y1 + (y2 - y1) * (xs[beatIndex] - x1) / (x2 - x1)
    }

    private fun notePosition(track: TGTrack, m: TGMeasure, note: TGNote): Int = if (track.percussion) TGNotation.drumPosition(track.pitch(note)) else TGNotation.position(track.pitch(note), m.clef, m.header.keySignature.value, note.altEnharmonic)
}

internal fun tabText(note: TGNote): String {
    val value = if (note.value == -1 || note.effects.dead) "X" else note.value.toString()
    return if (note.effects.ghost) "($value)" else if (note.effects.harmonic == 1) "<$value>" else value
}

/** TGBeatSpacing order, collapsed by effect category across all notes of a beat. */
internal fun effectLabels(beat: TGBeat): List<String> {
    val effects = beat.voices.flatMap { it.notes }.map { it.effects }
    return buildList {
        if (effects.any { it.accented }) add(">")
        if (effects.any { it.heavyAccented }) add("^")
        if (effects.any { it.harmonic > 1 }) add("A.H.")
        if (effects.any { it.tapping }) add("T")
        if (effects.any { it.slapping }) add("S")
        if (effects.any { it.popping }) add("P")
        if (effects.any { it.palmMute }) add("P.M.")
        if (effects.any { it.letRing }) add("let ring")
        if (effects.any { it.vibrato }) add("~")
        if (effects.any { it.trill != null }) add("tr")
        if (effects.any { it.fadeIn }) add("<")
    }
}

/** TGEffectBend.updateMovements, used by both width calculation and painting. */
internal fun TGBendEffect.movements(): List<Int> {
    if (points.size < 2) return emptyList()
    var start = points[0].second; var current = points[1].second
    return buildList {
        points.drop(2).forEach { (_, next) ->
            if ((next - current) * (current - start) < 0) { add(if (current > start) current else -start); start = current }
            current = next
        }
        if (current > start) add(current) else if (current < start) add(-start)
    }
}
