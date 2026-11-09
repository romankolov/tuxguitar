// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

/**
 * Independent half-open interval skyline. Standard engraving technique also used
 * by alphaTab's Skyline; no alphaTab source is incorporated here. Only layout
 * uses this mutable builder. Published geometry never retains it.
 *
 * Each [x[i],x[i+1]) has height h[i]; the final x is a zero-height sentinel.
 * Queries start with a binary search, updates split at most two boundaries and
 * coalesce equal neighbours. Touching intervals do not reserve each other's ink.
 */
internal class TGSkyline(private val left: Float, private val right: Float) {
    private var xs=FloatArray(8)
    private var hs=FloatArray(8)
    private var count=2
    init {
        require(left.isFinite() && right.isFinite() && left<right)
        xs[0]=left;xs[1]=right
    }
    val height: Float get() = (0 until count-1).maxOf {hs[it]}
    val segmentCount: Int get() = count-1
    fun maximum(from: Float, until: Float): Float {
        require(from.isFinite() && until.isFinite() && from<=until)
        val a=maxOf(left,from);val b=minOf(right,until)
        if(a>=b) return 0f
        var i=floorIndex(count) {xs[it]<=a}.coerceAtLeast(0)
        var result=0f
        while(i<count-1 && xs[i]<b) {result=maxOf(result,hs[i]);i++}
        return result
    }
    fun reserve(from: Float, until: Float, height: Float) {
        require(from.isFinite() && until.isFinite() && from<=until && height.isFinite() && height>=0f)
        val a=maxOf(left,from);val b=minOf(right,until)
        if(a>=b || height==0f) return
        val start=split(a);val end=split(b)
        for(i in start until end) hs[i]=maxOf(hs[i],height)
        var write=1
        for(read in 1 until count-1) if(hs[read]!=hs[write-1]) {
            xs[write]=xs[read];hs[write]=hs[read];write++
        }
        xs[write]=right;hs[write]=0f;count=write+1
    }
    private fun split(x: Float): Int {
        val before=floorIndex(count) {xs[it]<=x}
        if(xs[before]==x) return before
        val i=before+1
        if(count==xs.size) {xs=xs.copyOf(count*2);hs=hs.copyOf(count*2)}
        xs.copyInto(xs,i+1,i,count);hs.copyInto(hs,i+1,i,count)
        xs[i]=x;hs[i]=hs[before];count++
        return i
    }
}
