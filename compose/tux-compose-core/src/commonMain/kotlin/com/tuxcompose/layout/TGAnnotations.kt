// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tuxcompose.model.*

/** Row-local annotation ink, cached with layout rather than rebuilt on cursor frames. */
enum class TGAnnotationKind { Text, Accent, FadeIn, Vibrato, LetRing }
enum class TGAnnotationBand { Text, Effects }
@Immutable
data class TGAnnotationLayout(
    val kind: TGAnnotationKind,
    val band: TGAnnotationBand,
    val lines: List<String>,
    val labelBounds: Rect,
    val lineHeight: Float,
    val bounds: Rect,
    val clipBounds: Rect,
    val lineFrom: Offset? = null,
    val lineTo: Offset? = null,
    val endCap: Boolean = false,
    val continuesFromPrevious: Boolean = false,
    val continuesToNext: Boolean = false,
    val sourceTick: Long,
    val endTick: Long,
)

internal data class TGLetRingRun(val start: Long, val end: Long, val section: Int = 0)

/** Consecutive marked attacks in a voice; rests/re-attacks/repeat boundaries stop the run.
 * Other voices do not interrupt a sustained voice. Overlapping voice runs share one display span.
 */
internal fun letRingRuns(track: TGTrack): List<TGLetRingRun> {
    val result = mutableListOf<TGLetRingRun>()
    val active = mutableMapOf<Int,TGLetRingRun>()
    var section = 0
    fun close(index: Int, at: Long? = null) {
        active.remove(index)?.let { r ->
            val end = at?.let { minOf(r.end,it) } ?: r.end
            if (end > r.start) result += r.copy(end=end)
        }
    }
    track.measures.forEachIndexed { mi,m ->
        val previous = track.measures.getOrNull(mi-1)
        if (previous != null && (previous.header.repeatClose > 0 || m.header.repeatOpen ||
                previous.header.repeatAlternative != m.header.repeatAlternative)) {
            active.keys.toList().forEach { close(it,m.start) }; section++
        }
        val measureEnd = m.end
        m.beats.forEach { b ->
            val voices = b.voices.filterNot { it.empty }
            if (b.voices.isEmpty()) close(0,b.start)
            voices.forEach { v ->
                val end = minOf(measureEnd,b.start+(v.duration ?: b.duration).ticks)
                val old = active[v.index]
                val marked = v.notes.any { it.effects.letRing }
                val tied = v.notes.isNotEmpty() && v.notes.all { it.tied }
                if (old != null && b.start > old.end) close(v.index)
                val current = active[v.index]
                when {
                    marked -> active[v.index] = if (current == null) TGLetRingRun(b.start,end,section)
                        else current.copy(end=maxOf(current.end,end))
                    tied && current != null -> active[v.index] = current.copy(end=maxOf(current.end,end))
                    else -> close(v.index,b.start)
                }
            }
        }
    }
    active.keys.toList().forEach { close(it) }
    val merged = mutableListOf<TGLetRingRun>()
    result.sortedBy { it.start }.forEach { r ->
        val p = merged.lastOrNull()
        if (p != null && p.section == r.section && r.start <= p.end)
            merged[merged.lastIndex] = p.copy(end=maxOf(p.end,r.end))
        else merged += r
    }
    return merged
}

/** Runs are disjoint and ordered, so row lookup never scans an offscreen song prefix. */
internal class TGLetRingIndex(private val runs: List<TGLetRingRun>) {
    fun intersecting(start: Long, end: Long): List<TGLetRingRun> {
        val first = floorIndex(runs.size) { runs[it].end <= start }+1
        val until = floorIndex(runs.size) { runs[it].start < end }+1
        return if (until > first) runs.subList(first,until) else emptyList()
    }
}

internal data class TGAnnotationPlan(val annotations: List<TGAnnotationLayout>, val textHeight: Float, val effectHeight: Float) {
    fun placed(textTop: Float, effectsTop: Float): List<TGAnnotationLayout> = annotations.map { a ->
        val dy = if (a.band == TGAnnotationBand.Text) textTop else effectsTop
        val shift = Offset(0f,dy)
        a.copy(labelBounds=a.labelBounds.translate(shift),bounds=a.bounds.translate(shift),clipBounds=a.clipBounds.translate(shift),
            lineFrom=a.lineFrom?.plus(shift),lineTo=a.lineTo?.plus(shift))
    }
}

/**
 * Native rhythmic X positions never move. On rows containing let-ring spans, measured captions
 * are interval-packed into separate vertical lanes. Long captions wrap instead of covering a
 * later effect; span dashes occupy their full interval, so text cannot land in the middle.
 */
internal fun planAnnotations(measures: List<TGMeasureLayout>, runs: List<TGLetRingRun>,
    left: Float, right: Float, config: TGLayoutConfig, fonts: TGFontMetrics): TGAnnotationPlan? {
    if (!config.mergeLetRing || runs.isEmpty() || measures.isEmpty()) return null
    val clipLeft = left+1f
    val clipRight = maxOf(clipLeft+.01f,right-1f)
    val available = clipRight-clipLeft
    val start = measures.first().startTick
    val end = measures.last().endTick
    data class Item(val kind: TGAnnotationKind, val band: TGAnnotationBand, val text: String,
        val x: Float, val source: Long, val end: Long, val spanEnd: Float? = null,
        val incoming: Boolean = false, val outgoing: Boolean = false)
    val items = mutableListOf<Item>()
    fun position(tick: Long, ending: Boolean): Float {
        val mi = floorIndex(measures.size) { if (ending) measures[it].startTick < tick else measures[it].startTick <= tick }
        val m = measures[mi.coerceAtLeast(0)]
        val bi = floorIndex(m.beats.size) { if (ending) m.beats[it].startTick < tick else m.beats[it].startTick <= tick }
        val b = m.beats.getOrNull(bi.coerceAtLeast(0)) ?: return m.beatAreaLeft
        val f = if (b.endTick > b.startTick) ((tick-b.startTick).toDouble()/(b.endTick-b.startTick)).toFloat().coerceIn(0f,1f) else 0f
        return b.x+(b.endX-b.x)*f
    }
    runs.forEach { r ->
        val incoming = r.start < start
        val outgoing = r.end > end
        val x = if (incoming) measures.first().beatAreaLeft else position(r.start,false)
        val lastX = if (outgoing) measures.last().beats.lastOrNull()?.endX ?: right else position(r.end,true)
        items += Item(TGAnnotationKind.LetRing,TGAnnotationBand.Effects,if (incoming) "(let ring)" else "let ring",
            x-1f,r.start,r.end,minOf(clipRight-1f,lastX-2f),incoming,outgoing)
    }
    measures.forEach { m -> m.beats.filterNot { it.isContinuation }.forEach { b ->
        b.beat.text?.takeIf { it.isNotBlank() }?.let {
            items += Item(TGAnnotationKind.Text,TGAnnotationBand.Text,it,b.x,b.startTick,b.endTick)
        }
        effectLabels(b.beat).filterNot { it == "let ring" }.forEach { label ->
            val kind = when (label) { ">" -> TGAnnotationKind.Accent; "<" -> TGAnnotationKind.FadeIn; "~" -> TGAnnotationKind.Vibrato; else -> TGAnnotationKind.Text }
            items += Item(kind,TGAnnotationBand.Effects,label,b.x-1f,b.startTick,b.endTick)
        }
    } }
    val result = mutableListOf<TGAnnotationLayout>()
    val heights = mutableMapOf<TGAnnotationBand,Float>()
    TGAnnotationBand.entries.forEach { band ->
        data class Pending(val item: Item, val lines: List<String>, val x: Float, val width: Float,
            val height: Float, val lineHeight: Float, val right: Float, val lane: Int, val below: Boolean)
        val prepared = items.filter { it.band == band }.map { item ->
            val vector = item.kind in listOf(TGAnnotationKind.Accent,TGAnnotationKind.FadeIn,TGAnnotationKind.Vibrato)
            val lines = if (vector) emptyList() else wrapAnnotationText(item.text,available,fonts)
            val lh = if (vector) 8f else lines.maxOfOrNull { fonts.measure(it,TGTextRole.Text).height }?.coerceAtLeast(1f) ?: 1f
            val width = if (vector) (if (item.kind == TGAnnotationKind.Vibrato) 21f else 8f)
                else lines.maxOfOrNull { fonts.measure(it,TGTextRole.Text).width } ?: 0f
            val visibleWidth = minOf(width,available)
            val x = item.x.coerceIn(clipLeft,maxOf(clipLeft,clipRight-visibleWidth))
            val spanEnd = item.spanEnd?.coerceIn(clipLeft,clipRight)
            val below = spanEnd != null && spanEnd < x+visibleWidth+5f
            val height = lh*maxOf(1,lines.size)+if (spanEnd != null) (if (below) 9f else 4f) else 0f
            Pending(item.copy(spanEnd=spanEnd),lines,x,visibleWidth,height,lh,maxOf(x+visibleWidth,spanEnd ?: x),0,below)
        }.sortedWith(compareBy<Pending> { it.x }.thenBy { if (it.item.kind == TGAnnotationKind.LetRing) 0 else 1 })
        val ends = mutableListOf<Float>()
        val sizes = mutableListOf<Float>()
        val packed = prepared.map { p ->
            val i = ends.indexOfFirst { it+2f <= p.x }.takeIf { it >= 0 } ?: ends.size.also {
                ends += Float.NEGATIVE_INFINITY; sizes += 0f
            }
            ends[i] = p.right
            sizes[i] = maxOf(sizes[i],p.height+2f,if (band == TGAnnotationBand.Effects) config.effectSpacing else 0f)
            p.copy(lane=i)
        }
        val tops = FloatArray(sizes.size)
        var h = 0f
        sizes.forEachIndexed { i,size -> tops[i]=h; h+=size }
        heights[band] = h
        packed.forEach { p ->
            val y = tops[p.lane]+1f
            val label = Rect(p.x,y,p.x+p.width,y+p.lineHeight*maxOf(1,p.lines.size))
            val lineY = if (p.below) label.bottom+4f else label.bottom-p.lineHeight/2f
            val lineEnd = p.item.spanEnd
            // A short final fragment still has a true end cap: put its line BELOW the
            // caption rather than dropping the end or drawing through the words.
            val lineStart = if (p.below && lineEnd != null) p.item.x.coerceIn(clipLeft,lineEnd) else label.right+3f
            val drawLine = lineEnd != null
            result += TGAnnotationLayout(p.item.kind,band,p.lines,label,p.lineHeight,
                Rect(p.x,tops[p.lane],p.right+if (lineEnd != null) .5f else 0f,tops[p.lane]+sizes[p.lane]),Rect(clipLeft,0f,clipRight,h),
                if (drawLine) Offset(lineStart,lineY) else null,if (drawLine) Offset(lineEnd!!,lineY) else null,
                drawLine && !p.item.outgoing,p.item.incoming,p.item.outgoing,p.item.source,p.item.end)
        }
    }
    return TGAnnotationPlan(result,heights[TGAnnotationBand.Text] ?: 0f,heights[TGAnnotationBand.Effects] ?: 0f)
}

/** Word wrap, then Unicode-code-point emergency wrap; never truncate an entire caption. */
internal fun wrapAnnotationText(value: String, width: Float, fonts: TGFontMetrics): List<String> {
    val lines = mutableListOf<String>()
    value.replace("\r\n","\n").replace('\r','\n').split('\n').forEach { paragraph ->
        var line = ""
        paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
            val proposed = if (line.isEmpty()) word else "$line $word"
            if (fonts.measure(proposed,TGTextRole.Text).width <= width) line = proposed
            else {
                if (line.isNotEmpty()) { lines += line; line="" }
                if (fonts.measure(word,TGTextRole.Text).width <= width) line=word
                else {
                    var i = 0
                    while (i < word.length) {
                        val n = if (word[i].code in 0xD800..0xDBFF && i+1 < word.length && word[i+1].code in 0xDC00..0xDFFF) 2 else 1
                        val c = word.substring(i,i+n)
                        if (line.isNotEmpty() && fonts.measure(line+c,TGTextRole.Text).width > width) { lines += line; line="" }
                        line+=c; i+=n
                    }
                }
            }
        }
        if (line.isNotEmpty()) lines += line else if (paragraph.isBlank()) lines += ""
    }
    return lines.ifEmpty { listOf("") }
}

/** Balanced interval index also finds spans whose source label is horizontally offscreen. */
internal class TGAnnotationIndex(annotations: List<TGAnnotationLayout>) {
    private val entries = annotations.sortedBy { it.bounds.left }
    private val maxRight = FloatArray(entries.size)
    init {
        fun build(from: Int, until: Int): Float {
            if (from >= until) return Float.NEGATIVE_INFINITY
            val mid = (from+until) ushr 1
            return maxOf(entries[mid].bounds.right,build(from,mid),build(mid+1,until)).also { maxRight[mid]=it }
        }
        build(0,entries.size)
    }
    fun visible(viewport: Rect): Sequence<TGAnnotationLayout> = sequence {
        suspend fun SequenceScope<TGAnnotationLayout>.visit(from: Int, until: Int) {
            if (from >= until) return
            val mid = (from+until) ushr 1
            if (maxRight[mid] < viewport.left) return
            visit(from,mid)
            val a = entries[mid]
            if (a.bounds.left > viewport.right) return
            if (a.bounds.overlaps(viewport)) yield(a)
            visit(mid+1,until)
        }
        visit(0,entries.size)
    }
}
