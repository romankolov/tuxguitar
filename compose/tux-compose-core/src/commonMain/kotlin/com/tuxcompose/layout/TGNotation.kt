// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.layout

import com.tuxcompose.model.*

/** TGMusicKeyUtils and TGNotation spelling/clef math, including E#, B#, Cb and Fb. */
object TGNotation {
    private val sharp = intArrayOf(0,0,1,1,2,3,3,4,4,5,5,6)
    private val sharp6 = intArrayOf(0,0,1,1,2,2,3,4,4,5,5,6)
    private val sharp7 = intArrayOf(6,0,1,1,2,2,3,4,4,5,5,6)
    private val flat = intArrayOf(0,1,1,2,2,3,4,4,5,5,6,6)
    private val flat6 = intArrayOf(0,1,1,2,2,3,4,4,5,5,6,0)
    private val flat7 = intArrayOf(0,1,1,2,3,3,4,4,5,5,6,0)
    private val sharps = intArrayOf(3,0,4,1,5,2,6)
    private val tables = arrayOf(sharp, sharp6, sharp7, flat, flat6, flat7)
    private val tableIndex = intArrayOf(0,0,0,0,0,0,1,2,3,3,3,3,3,4,5)
    fun noteIndex(pitch: Int, key: Int, alt: Boolean = false): Int {
        val normal = tables[tableIndex[key]][pitch % 12]
        if (!alt) return normal
        val alternate = tables[2][pitch % 12]
        return if (alternate != normal) alternate else tables[5][pitch % 12]
    }
    fun octave(pitch: Int, key: Int, alt: Boolean = false): Int {
        fun regular(k: Int): Int = pitch / 12 - 1 + if (k == 7 && pitch % 12 == 0) -1 else if (k >= 13 && pitch % 12 == 11) 1 else 0
        val normal = regular(key)
        return if (!alt) normal else regular(7).let { if (it != normal) it else regular(14) }
    }
    fun alteration(pitch: Int, key: Int): Int {
        val index = noteIndex(pitch, key)
        return if ((7 + noteIndex(pitch, 8) - index) % 7 == 1) 1 else if ((7 + noteIndex(pitch, 0) - index) % 7 == 6) -1 else 0
    }
    fun keyAlteration(index: Int, key: Int): Int = when {
        key in 1..7 && index in sharps.take(key) -> 1
        key in 8..14 && index in sharps.reversed().take(key - 7) -> -1
        else -> 0
    }
    fun position(pitch: Int, clef: TGClef, key: Int, alt: Boolean = false): Int =
        clef.positionOffset - noteIndex(pitch, key, alt) - 7 * octave(pitch, key, alt)

    // Default map from TGDrumMap; unknown percussion remains at the upstream default (-1).
    fun drumPosition(pitch: Int): Int = when (pitch) {
        35 -> 7; 36 -> 6; 37,38,40,77 -> 2; 42,46 -> -2; 44 -> 8; 49,80,81 -> -3
        57 -> -4; 55,52 -> -5; 51,53,50 -> -1; 59,47,76 -> 1; 41 -> 5; 43 -> 4
        45,54,31 -> 3; 48,56 -> 0; else -> -1
    }
}

/** Accidental memory is local to one measure and octave; never leaks across layout passes. */
internal class TGAccidentals(private val key: Int) {
    private val current = mutableMapOf<Pair<Int, Int>, Int>()
    fun next(pitch: Int, alt: Boolean, force: Boolean=false): TGAccidental? {
        val index = TGNotation.noteIndex(pitch, key, alt)
        val octave = TGNotation.octave(pitch, key, alt)
        val spellingKey = if (!alt) key else if (TGNotation.noteIndex(pitch, 7) != TGNotation.noteIndex(pitch, key)) 7 else 14
        val value = TGNotation.alteration(pitch, spellingKey)
        val id = octave to index
        val before = current[id] ?: TGNotation.keyAlteration(index, key)
        current[id] = value
        return if (value == before && !force) null else when (value) { -1 -> TGAccidental.Flat; 1 -> TGAccidental.Sharp; else -> TGAccidental.Natural }
    }
    /** 2 is an ambiguous simultaneous spelling, never a normal alteration. */
    fun invalidate(pitch: Int,alt: Boolean) {
        current[TGNotation.octave(pitch,key,alt) to TGNotation.noteIndex(pitch,key,alt)]=2
    }
}
enum class TGAccidental { Sharp, Flat, Natural }
