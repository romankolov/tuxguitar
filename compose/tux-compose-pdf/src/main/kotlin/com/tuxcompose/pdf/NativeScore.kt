// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.pdf

import com.tuxcompose.model.*
import com.tuxcompose.pdf.internal.tuxguitar.graphics.control.TGFactoryImpl
import com.tuxcompose.pdf.internal.tuxguitar.song.models.TGSong as NativeSong
import com.tuxcompose.pdf.internal.tuxguitar.song.models.TGDuration as NativeDuration
import com.tuxcompose.pdf.internal.tuxguitar.song.models.TGNote as NativeNote

/** A decoded-model adapter, not a parser. Never retains/mutates the caller's model. */
internal fun nativeScore(source: TGSong,options: TGPdfOptions,metadata: TGPdfMetadata): NativeSong {
    require(source.tracks.isNotEmpty()) {"There are no tracks to print"}
    require(options.trackIndex==null || options.trackIndex<source.tracks.size) {"Selected track no longer exists"}
    val reference=source.tracks.first().measures
    require(reference.isNotEmpty()) {"There are no measures to print"}
    val last=options.toMeasure ?: reference.size
    require(options.fromMeasure<=reference.size && last<=reference.size) {"Print range is outside the score"}
    require(source.tracks.all {t->t.measures.size==reference.size && t.measures.indices.all {i->
        val a=t.measures[i];val b=reference[i]
        a.number==i+1 && a.start==b.start && a.lineBreak==b.lineBreak && a.header.copy(keySignature=b.header.keySignature)==b.header
    }}) {"Native TuxGuitar printing requires aligned tracks with consecutive measure numbers starting at 1"}
    require(source.tracks.all {t->t.measures.all {m->m.beats.all {b->b.voices.all {it.empty || it.index<2}}}}) {
        "TuxGuitar's native print engine supports two voices; additional voices cannot be silently discarded"
    }
    val f=TGFactoryImpl()
    fun duration(d: TGDuration)=f.newDuration().apply {
        value=d.value;isDotted=d.dotted;isDoubleDotted=d.doubleDotted
        division.enters=d.division.enters;division.times=d.division.times
    }
    fun note(n: TGNote,t: TGTrack): NativeNote {
        val natural=n.value.coerceAtLeast(0)+t.stringTuning[n.string-1]
        require(n.pitch==null || n.pitch==natural) {
            "A per-note pitch override cannot be represented by native TuxGuitar fret/tuning notation"
        }
        return f.newNote().apply {
            value=n.value.coerceAtLeast(0);string=n.string;velocity=n.velocity;isTiedNote=n.tied
            if(n.altEnharmonic) toggleAltEnharmonic()
            val e=n.effects
            effect.apply {
                // Same setter order as the ORIGINAL TGNoteEffect.clone(factory).
                isVibrato=e.vibrato;isDeadNote=e.dead || n.value<0;isSlide=e.slide;isHammer=e.hammer
                isGhostNote=e.ghost;isAccentuatedNote=e.accented;isHeavyAccentuatedNote=e.heavyAccented
                isPalmMute=e.palmMute;isLetRing=e.letRing;isStaccato=e.staccato
                isTapping=e.tapping;isSlapping=e.slapping;isPopping=e.popping;isFadeIn=e.fadeIn
                bend=e.bend?.let {v->f.newEffectBend().apply {v.points.forEach {(x,y)->addPoint(x,y)}}}
                tremoloBar=e.tremoloBar?.let {v->f.newEffectTremoloBar().apply {v.points.forEach {(x,y)->addPoint(x,y)}}}
                harmonic=e.harmonic.takeIf {it>0}?.let {v->f.newEffectHarmonic().apply {type=v}}
                grace=e.grace?.let {v->f.newEffectGrace().apply {
                    fret=v.fret;duration=when(v.duration) {64->1;16->3;else->2};isOnBeat=v.onBeat;isDead=v.dead
                }}
                trill=e.trill?.let {v->f.newEffectTrill().apply {fret=v.fret;duration.value=v.duration}}
                tremoloPicking=e.tremoloPicking.takeIf {it>0}?.let {v->f.newEffectTremoloPicking().apply {duration.value=v}}
            }
            // Setter incompatibilities are native semantics, but dropping caller flags is not.
            require(effect.isVibrato==e.vibrato && effect.isDeadNote==(e.dead || n.value<0) &&
                effect.isSlide==e.slide && effect.isHammer==e.hammer && effect.isGhostNote==e.ghost &&
                effect.isAccentuatedNote==e.accented && effect.isHeavyAccentuatedNote==e.heavyAccented &&
                effect.isPalmMute==e.palmMute && effect.isLetRing==e.letRing && effect.isStaccato==e.staccato &&
                effect.isTapping==e.tapping && effect.isSlapping==e.slapping && effect.isPopping==e.popping &&
                effect.isFadeIn==e.fadeIn && effect.isBend==(e.bend!=null) && effect.isTremoloBar==(e.tremoloBar!=null) &&
                effect.isHarmonic==(e.harmonic>0) && effect.isGrace==(e.grace!=null) && effect.isTrill==(e.trill!=null) &&
                effect.isTremoloPicking==(e.tremoloPicking>0)) {"This note combines effects that native TuxGuitar does not support together"}
        }
    }
    val out=f.newSong().apply {
        name=source.title;artist=source.artist;album=source.album
        author=metadata.author;date=metadata.year;copyright=metadata.copyright
        transcriber=metadata.transcriber;writer=metadata.writer
    }
    reference.forEach {m->out.addMeasureHeader(f.newHeader().apply {
        number=m.number;start=m.start
        timeSignature.numerator=m.header.timeSignature.numerator
        timeSignature.denominator.value=m.header.timeSignature.denominator
        val tempoValue=m.header.tempoMark
        if(tempoValue==null) tempo.quarterValue=m.header.tempo
        else tempo.setValueBase(tempoValue.value,tempoValue.base,tempoValue.dotted)
        isRepeatOpen=m.header.repeatOpen;repeatClose=m.header.repeatClose;repeatAlternative=m.header.repeatAlternative
        tripletFeel=m.header.tripletFeel+1;isLineBreak=m.lineBreak
        marker=m.header.marker?.let {v->f.newMarker().apply {title=v}}
    })}
    source.tracks.forEachIndexed {ti,t->
        out.addChannel(f.newChannel().apply {channelId=ti; if(t.percussion) bank=128.toShort()})
        val track=f.newTrack().apply {number=ti+1;name=t.name;channelId=ti;offset=t.capo}
        t.stringTuning.forEachIndexed {i,pitch->track.strings.add(f.newString().apply {number=i+1;value=pitch})}
        metadata.lyrics[ti]?.let {v->track.lyrics.apply {from=v.fromMeasure;lyrics=v.text}}
        out.addTrack(track)
        t.measures.forEachIndexed {mi,m->
            val measure=f.newMeasure(out.getMeasureHeader(mi)).apply {
                clef=when(m.clef) {TGClef.Bass->2;TGClef.Tenor->3;TGClef.Alto->4;else->1}
                keySignature=m.header.keySignature.value
            }
            track.addMeasure(measure)
            m.beats.forEach {b->
                val beat=f.newBeat().apply {
                    preciseStart=b.preciseStart ?: NativeDuration.toPreciseTime(b.start)
                    stroke.direction=b.stroke.direction.value;stroke.value=b.stroke.value;pickStroke.direction=b.pickStroke.value
                    text=b.text?.let {v->f.newText().apply {value=v}}
                    chord=b.chord?.let {v->f.newChord(v.frets.size).apply {
                        name=v.name;firstFret=v.firstFret;v.frets.forEachIndexed {i,fret->addFretValue(i,fret)}
                    }}
                }
                measure.addBeat(beat)
                val voices=b.voices.ifEmpty {listOf(TGVoice(duration=b.duration))}
                voices.filter {it.index<2}.forEach {v->beat.getVoice(v.index).apply {
                    isEmpty=v.empty;this.duration=duration(v.duration ?: b.duration)
                    direction=when(v.stem) {TGStemDirection.Up->1;TGStemDirection.Down->2;else->0}
                    v.notes.forEach {addNote(note(it,t))}
                }}
            }
        }
    }
    return out
}
