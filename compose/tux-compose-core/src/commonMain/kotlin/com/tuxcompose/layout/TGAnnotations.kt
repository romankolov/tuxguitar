// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tuxcompose.model.*
import kotlinx.collections.immutable.persistentListOf

/** Row-local annotation ink, cached with layout rather than rebuilt on cursor frames. */
enum class TGAnnotationKind { Text, Accent, FadeIn, Vibrato, LetRing, HeavyAccent }
enum class TGAnnotationBand { Text, Effects, Marker, ChordNames }
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
    val dashSize: Float = 3f,
    val dashGap: Float = 2f,
    val lineThickness: Float = .8f,
    val capSize: Float = 6f,
    /** EffectBand is scoped to a voice; overlapping voices are never merged. */
    val voiceIndex: Int = 0,
)

/** Only visit visible wrapped lines, even when one imported caption is thousands of lines. */
internal fun TGAnnotationLayout.visibleLines(viewport: Rect): IntRange {
    if (lines.isEmpty() || lineHeight<=0f || !labelBounds.overlaps(viewport)) return IntRange.EMPTY
    val first=kotlin.math.floor((viewport.top-labelBounds.top)/lineHeight).toInt().coerceIn(0,lines.size)
    val end=kotlin.math.ceil((viewport.bottom-labelBounds.top)/lineHeight).toInt().coerceIn(first,lines.size)
    return first until end
}

internal data class TGLetRingRun(val start: Long, val end: Long, val last: Long=start, val voice: Int=0)

/**
 * EffectBand GroupedOnBeat + LetRingEffectInfo: link consecutive flagged beats of
 * ONE voice. canExpand is true, including across bar/repeat/ending boundaries.
 * No duration-based voice union, invented tie inheritance or repeat-boundary split.
 * Callers feed decoded flags, just as shouldCreateGlyph reads beat.isLetRing.
 */
internal fun letRingRuns(track: TGTrack): List<TGLetRingRun> {
    val result=mutableListOf<TGLetRingRun>()
    val active=mutableMapOf<Int,TGLetRingRun>()
    fun close(voice: Int, at: Long?=null) { active.remove(voice)?.let {result+=if(at==null) it else it.copy(end=minOf(it.end,at))} }
    track.measures.forEach {m->m.beats.forEach {b->
        if(b.voices.isEmpty()) close(0,b.start)
        b.voices.filterNot {it.empty}.forEach {v->
            if(v.notes.any {it.effects.letRing}) {
                val end=minOf(m.end,b.start+(v.duration ?: b.duration).ticks)
                val old=active[v.index]
                active[v.index]=old?.copy(end=end,last=b.start) ?: TGLetRingRun(b.start,end,b.start,v.index)
            } else close(v.index,b.start)
        }
    }}
    active.keys.toList().forEach {close(it)}
    return result.sortedWith(compareBy<TGLetRingRun> {it.start}.thenBy {it.voice})
}

/** Disjoint within each voice, NOT across voices. Never scan an offscreen prefix. */
internal class TGLetRingIndex(runs: List<TGLetRingRun>) {
    private val voices=runs.groupBy {it.voice}.values.map {it.sortedBy {r->r.start}}
    fun intersecting(start: Long, end: Long): List<TGLetRingRun> = voices.flatMap {runs->
        val first=floorIndex(runs.size) {runs[it].end<=start}+1
        val until=floorIndex(runs.size) {runs[it].start<end}+1
        if(until>first) runs.subList(first,until) else emptyList()
    }.sortedWith(compareBy<TGLetRingRun> {it.start}.thenBy {it.voice})
}

internal data class TGAnnotationPlan(val annotations: List<TGAnnotationLayout>, val textHeight: Float, val effectHeight: Float, val markerHeight: Float = 0f, val chordHeight: Float = 0f) {
    fun placed(textTop: Float, effectsTop: Float, markerTop: Float = 0f, chordTop: Float = 0f, effectLaneHeight: Float=effectHeight): List<TGAnnotationLayout> = annotations.map { a ->
        val dy = when (a.band) { TGAnnotationBand.Text -> textTop; TGAnnotationBand.Effects -> effectsTop+effectLaneHeight-effectHeight; TGAnnotationBand.Marker -> markerTop; TGAnnotationBand.ChordNames -> chordTop }
        val shift = Offset(0f,dy)
        a.copy(labelBounds=a.labelBounds.translate(shift),bounds=a.bounds.translate(shift),clipBounds=a.clipBounds.translate(shift),
            lineFrom=a.lineFrom?.plus(shift),lineTo=a.lineTo?.plus(shift))
    }
}

/**
 * Native track bands and native marker/text/chord X anchors. Effect-band priority
 * and grouped-line geometry use the alphaTab ports in TGAlphaEffectLayout.kt.
 * Only prose/chord captions may word-wrap for the fixed-width parent; an atomic
 * ranged label is NEVER wrapped, clamped to a bar edge or moved off its note.
 */
internal fun planAnnotations(measures: List<TGMeasureLayout>, runs: List<TGLetRingRun>,
    left: Float, right: Float, config: TGLayoutConfig, fonts: TGFontMetrics): TGAnnotationPlan? {
    if (measures.isEmpty() || (!config.avoidAnnotationCollisions && (!config.mergeLetRing || runs.isEmpty()))) return null
    val clipLeft=left
    val clipRight=maxOf(clipLeft+.01f,right)
    val available=clipRight-clipLeft
    val start=measures.first().startTick
    val end=measures.last().endTick
    data class Item(val kind: TGAnnotationKind, val band: TGAnnotationBand, val text: String,
        val x: Float, val source: Long, val end: Long, val renderer: Int,
        val effectId: String=text, val voice: Int=0, val category: Int=0, val order: Int=0,
        val spanEnd: Float?=null, val incoming: Boolean=false, val outgoing: Boolean=false)
    val items=mutableListOf<Item>()
    val visibleBeats=measures.flatMap {m->m.beats.map {m to it}}
    val byVoice=visibleBeats.flatMap {pair->pair.second.voices.map {it.voice.index to pair}}
        .groupBy({it.first},{it.second})
    runs.forEach {r->
        val lane=byVoice[r.voice].orEmpty()
        val from=floorIndex(lane.size) {itIndex->lane[itIndex].second.endTick<=r.start}+1
        val until=floorIndex(lane.size) {itIndex->lane[itIndex].second.startTick<r.end && lane[itIndex].second.voices.first {it.voice.index==r.voice}.sourceTick<=r.last}+1
        val beats=if(until>from) lane.subList(from,until) else emptyList()
        if(beats.isNotEmpty()) {
            val (m,first)=beats.first();val last=beats.last().second
            items+=Item(TGAnnotationKind.LetRing,TGAnnotationBand.Effects,"let ring",first.x,r.start,r.end,m.measureIndex,
                effectId="EffectLetRing",voice=r.voice,category=1,order=15,
                spanEnd=if(beats.size>1) last.x else null,incoming=r.start<start,outgoing=r.end>end)
        }
    }
    if(config.avoidAnnotationCollisions) measures.filter {it.isFirstFragment}.forEach {m->
        m.measure.header.marker?.takeIf {it.isNotBlank()}?.let {
            // TGMeasureImpl.paintMarker: bar X + left spacing + first-note spacing.
            items+=Item(TGAnnotationKind.Text,TGAnnotationBand.Marker,it,m.beatAreaLeft,m.startTick,m.endTick,m.measureIndex)
        }
    }
    measures.forEach {m->m.beats.filterNot {it.isContinuation}.forEach {b->
        if(config.avoidAnnotationCollisions && config.showChordNames) b.beat.chord?.let {chord->
            val width=fonts.measure(chord.name,TGTextRole.Chord).width
            val x=if(config.nativeSpacing) b.x+kotlin.math.floor(4f*config.renderScale+.5f)/config.renderScale-width/2f else b.x-width/2f
            items+=Item(TGAnnotationKind.Text,TGAnnotationBand.ChordNames,chord.name,x,b.startTick,b.endTick,m.measureIndex)
        }
        b.beat.text?.takeIf {it.isNotBlank()}?.let {
            items+=Item(TGAnnotationKind.Text,TGAnnotationBand.Text,it,b.x,b.startTick,b.endTick,m.measureIndex)
        }
        b.voices.forEach {v->
            effectLabels(b.beat.copy(voices=persistentListOf(v.voice))).forEach effect@{label->
                if(label=="let ring" && config.mergeLetRing) return@effect
                if(config.showStandardNotation && label in listOf("N.H","A.H","T.H","P.H","S.H")) return@effect
                val kind=when(label) {">"->TGAnnotationKind.Accent;"<"->TGAnnotationKind.FadeIn;"~"->TGAnnotationKind.Vibrato;"^"->TGAnnotationKind.HeavyAccent;else->TGAnnotationKind.Text}
                val value=if(label=="let ring") "L.R" else label
                items+=Item(kind,TGAnnotationBand.Effects,value,b.x,b.startTick,b.endTick,m.measureIndex,
                    voice=v.voice.index,category=if(label in listOf("~","tr")) 1 else 0,
                    // Stable visual stack, not an index in this beat's sparse effects.
                    // Environment.defaultRenderers' TAB profile (Mixed owns these on TAB).
                    order=when(label) {"tr"->2;"~"->6;"T","S","P"->7;"<"->8;"N.H"->9;"A.H"->10;"P.H"->11;"T.H"->12;"S.H"->13;"L.R"->15;"P.M"->17;else->0})
            }
        }
    }}
    if(items.isEmpty()) return null
    val result=mutableListOf<TGAnnotationLayout>()
    val heights=mutableMapOf<TGAnnotationBand,Float>()
    TGAnnotationBand.entries.forEach {band->
        data class Pending(val item: Item,val lines: List<String>,val label: Rect,val bounds: Rect,
            val lineHeight: Float,val ranged: TGAlphaRangedGeometry?)
        val role=if(band==TGAnnotationBand.ChordNames) TGTextRole.Chord else TGTextRole.Text
        val prepared=items.filter {it.band==band}.map {item->
            val vector=item.kind in listOf(TGAnnotationKind.Accent,TGAnnotationKind.FadeIn,TGAnnotationKind.Vibrato,TGAnnotationKind.HeavyAccent)
            val ranged=if(item.kind==TGAnnotationKind.LetRing)
                alphaRangedGeometry(item.x,0f,fonts.measure(item.text,role),item.spanEnd,config.staffSpacing) else null
            val atomic=band==TGAnnotationBand.Effects
            val widthAtAnchor=(clipRight-item.x).coerceIn(.01f,available)
            val lines=when {vector->emptyList();atomic->listOf(item.text);else->wrapAnnotationText(item.text,widthAtAnchor,fonts,role)}
            val lh=if(vector) 8f else lines.maxOfOrNull {fonts.measure(it,role).height}?.coerceAtLeast(1f) ?: 1f
            val width=if(vector) (if(item.kind==TGAnnotationKind.Vibrato) 21f else 8f) else lines.maxOfOrNull {fonts.measure(it,role).width} ?: 0f
            val label=ranged?.label ?: Rect(item.x,0f,item.x+width,lh*maxOf(1,lines.size))
            Pending(item,lines,label,ranged?.bounds ?: label,lh,ranged)
        }
        if(prepared.isEmpty()) {heights[band]=0f;return@forEach}
        val tops=FloatArray(prepared.size)
        val base=if(band==TGAnnotationBand.ChordNames && config.showChords) 42f else 0f
        val height: Float
        if(band==TGAnnotationBand.Effects) {
            // Actual EffectBand scope: (voice, effect, bar). Same-band glyphs align;
            // a linked ranged chain already publishes its full cross-bar extent.
            val groups=prepared.indices.groupBy {i->val p=prepared[i];Triple(p.item.effectId,p.item.voice,p.item.renderer)}.values.toList()
            val bands=groups.map {indices->
                val p=prepared[indices.first()]
                val ranges=if(p.item.category==0) indices.map {i->prepared[i].bounds.let {it.left to it.right}}
                    else listOf(indices.minOf {prepared[it].bounds.left} to indices.maxOf {prepared[it].bounds.right})
                TGAlphaEffectBand(p.item.effectId,p.item.category,p.item.order,p.item.voice,p.item.renderer,
                    indices.maxOf {prepared[it].bounds.height},ranges)
            }
            val placement=alphaPlaceEffectBands(bands,clipLeft,clipRight)
            groups.forEachIndexed {bi,indices->indices.forEach {tops[it]=placement.tops[bi]-prepared[it].bounds.top}}
            height=placement.height
        } else {
            // Keep the existing fixed-parent emergency prose wrapping in native lanes.
            // Crucially this NEVER changes an upstream horizontal anchor.
            val skyline=TGSkyline(clipLeft,clipRight+.01f)
            prepared.indices.sortedBy {prepared[it].label.left}.forEach {i->
                val p=prepared[i]
                val a=p.bounds.left.coerceIn(clipLeft,clipRight)
                val b=maxOf(a,minOf(clipRight,p.bounds.right+2f))
                val top=skyline.maximum(a,b)
                skyline.reserve(a,b,top+p.bounds.height+2f)
                tops[i]=base+top+1f-p.bounds.top
            }
            height=base+skyline.height
        }
        heights[band]=height
        prepared.forEachIndexed {i,p->
            val shift=Offset(0f,tops[i]);val ranged=p.ranged
            result+=TGAnnotationLayout(p.item.kind,band,p.lines,p.label.translate(shift),p.lineHeight,p.bounds.translate(shift),
                Rect(clipLeft,0f,clipRight,height),ranged?.from?.plus(shift),ranged?.to?.plus(shift),ranged?.cap!=null,
                p.item.incoming,p.item.outgoing,p.item.source,p.item.end,
                ranged?.dashSize ?: 3f,ranged?.dashGap ?: 2f,ranged?.thickness ?: .8f,ranged?.dashSize ?: 6f,p.item.voice)
        }
    }
    return TGAnnotationPlan(result,heights[TGAnnotationBand.Text] ?: 0f,heights[TGAnnotationBand.Effects] ?: 0f,
        heights[TGAnnotationBand.Marker] ?: 0f,heights[TGAnnotationBand.ChordNames] ?: 0f)
}

/** Word wrap, then Unicode-code-point emergency wrap; never truncate an entire caption. */
internal fun wrapAnnotationText(value: String, width: Float, fonts: TGFontMetrics, role: TGTextRole = TGTextRole.Text): List<String> {
    val lines = mutableListOf<String>()
    value.replace("\r\n","\n").replace('\r','\n').split('\n').forEach { paragraph ->
        var line = ""
        paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
            val proposed = if (line.isEmpty()) word else "$line $word"
            if (fonts.measure(proposed,role).width <= width) line = proposed
            else {
                if (line.isNotEmpty()) { lines += line; line="" }
                if (fonts.measure(word,role).width <= width) line=word
                else {
                    var i = 0
                    while (i < word.length) {
                        val n = if (word[i].code in 0xD800..0xDBFF && i+1 < word.length && word[i+1].code in 0xDC00..0xDFFF) 2 else 1
                        val c = word.substring(i,i+n)
                        if (line.isNotEmpty() && fonts.measure(line+c,role).width > width) { lines += line; line="" }
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
