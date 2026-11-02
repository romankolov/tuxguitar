// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.demo

import app.tuxguitar.android.view.tablature.TGSongViewStyles
import app.tuxguitar.graphics.control.*
import app.tuxguitar.song.managers.TGSongManager
import app.tuxguitar.song.models.TGSong as NativeSong
import app.tuxguitar.ui.resource.*
import com.tuxcompose.layout.*
import com.tuxcompose.model.TGSong
import java.io.InputStream
import java.lang.reflect.Proxy

/**
 * A headless ORIGINAL Java layout, not a second implementation of its equations.
 * updateSong, loadStyles, calculateWidth, updateComponents, paintSong, getTempLines,
 * track-lane registration, paintLine and getBeatSpacing all run unmodified.
 * Only paintMeasure's raster work is skipped, AFTER the original paintLine has set
 * positions/widths/track spacing. Fonts/colors are inert resources, never layout mocks.
 */
internal class NativeLayoutReference(input: NativeSong, val config: TGLayoutConfig, physicalWidth: Float,
    val scale: Float = 1f, selectedTrack: Int = 0, completeRests: Boolean = true) {
    val song: NativeSong = input.clone(TGFactoryImpl())
    val manager = TGSongManager(TGFactoryImpl())
    val styles = TGSongViewStyles().apply {
        stringSpacing=config.stringSpacing
        scoreLineSpacing=config.staffSpacing
        firstMeasureSpacing=config.pagePadding
        firstTrackSpacing=config.firstTrackSpacing
        trackSpacing=config.systemSpacing
        measureLeftSpacing=config.measurePadding
        measureRightSpacing=config.measurePadding
        firstNoteSpacing=config.firstNoteSpacing
        clefSpacing=config.clefSpacing
        keySignatureSpacing=config.keySignatureSpacing
        timeSignatureSpacing=config.timeSignatureSpacing
        minTopSpacing=config.staffPadding
        minScoreTabSpacing=config.scoreTabSpacing
        effectSpacing=config.effectSpacing
        pickStrokeSpacing=config.pickStrokeSpacing
        durationWidths=config.durationWidths.toFloatArray()
    }
    private val resources = TGResourceBuffer()
    private val controller = object : TGController {
        override fun getResourceFactory(): UIResourceFactory = HeadlessResources
        override fun getResourceBuffer(): TGResourceBuffer = this@NativeLayoutReference.resources
        override fun getSongManager(): TGSongManager = this@NativeLayoutReference.manager
        override fun getSong(): NativeSong = this@NativeLayoutReference.song
        override fun getStyles(): TGSongViewStyles = this@NativeLayoutReference.styles
        override fun getTrackSelection() = if (config.multiTrack) -1 else this@NativeLayoutReference.song.getTrack(selectedTrack).number
        override fun isRunning(beat: app.tuxguitar.song.models.TGBeat?) = false
        override fun isRunning(measure: app.tuxguitar.song.models.TGMeasure?) = false
        override fun isLoopSHeader(header: app.tuxguitar.song.models.TGMeasureHeader?) = false
        override fun isLoopEHeader(header: app.tuxguitar.song.models.TGMeasureHeader?) = false
    }
    private val flags = (if (config.showStandardNotation) TGLayout.DISPLAY_SCORE else 0) or
        (if (config.showTablature) TGLayout.DISPLAY_TABLATURE else 0) or
        (if (config.compact) TGLayout.DISPLAY_COMPACT else 0) or
        (if (config.multiTrack) TGLayout.DISPLAY_MULTITRACK else 0) or
        (if (config.showChords) TGLayout.DISPLAY_CHORD_DIAGRAM else 0) or
        (if (config.showChordNames) TGLayout.DISPLAY_CHORD_NAME else 0)
    val layout: TGLayout = if (config.orientation == TGLayoutOrientation.Page)
        object : TGLayoutVertical(controller,flags) {
            override fun paintMeasure(measure: TGMeasureImpl, painter: UIPainter?, spacing: Float) { measure.spacing=spacing }
        } else object : TGLayoutHorizontal(controller,flags) {
            override fun paintMeasure(measure: TGMeasureImpl, painter: UIPainter?, spacing: Float) { measure.spacing=spacing }
        }
    val model: TGSong
    val actual: TGSongLayout

    init {
        require(config.showTempo && !config.repeatSystemSignatures)
        require(config.justifySystems || config.orientation == TGLayoutOrientation.Horizontal)
        require(config.pageRightPadding == 10f) // Upstream hard-codes this physical-pixel margin.
        manager.updatePreciseStart(song)
        layout.loadStyles(scale)
        if (completeRests) layout.updateSong() else updateWithoutRestCompletion()
        layout.paint(painter,UIRectangle(0f,0f,physicalWidth,1_000_000f),0f,0f)
        model = TuxGuitarImporter.adapt(song)
        actual = TGLayoutCalculator(config.copy(renderScale=scale)).calculate(model,physicalWidth/scale,selectedTrack)
    }

    /** Also exercise the original width methods on deliberately short/overfull caller data. */
    private fun updateWithoutRestCompletion() {
        fun call(m: TGMeasureImpl, name: String) = TGMeasureImpl::class.java
            .getDeclaredMethod(name,TGLayout::class.java).apply { isAccessible=true }.invoke(m,layout)
        for (i in 0 until song.countMeasureHeaders()) {
            (song.getMeasureHeader(i) as TGMeasureHeaderImpl).update(layout,i)
            for (ti in 0 until song.countTracks()) {
                val m = song.getTrack(ti).getMeasure(i) as TGMeasureImpl
                TGMeasureImpl::class.java.getDeclaredField("divisionLength").apply { isAccessible=true }
                    .setLong(m,TGSongManager.getDivisionLength(m.header))
                TGMeasureImpl::class.java.getDeclaredMethod("resetSpacing").apply { isAccessible=true }.invoke(m)
                call(m,"checkCompactMode")
                call(m,"calculateBeats")
                m.calculateWidth(layout)
            }
            for (ti in 0 until song.countTracks()) {
                val track = song.getTrack(ti) as TGTrackImpl
                track.update(layout)
                (track.getMeasure(i) as TGMeasureImpl).update(layout)
            }
        }
    }

    companion object {
        private val painter = Proxy.newProxyInstance(UIPainter::class.java.classLoader,arrayOf(UIPainter::class.java)) { _,method,_ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as UIPainter
    }
}

private object HeadlessResources : UIResourceFactory {
    override fun createFont(name: String, height: Float, bold: Boolean, italic: Boolean): UIFont =
        createFont(UIFontModel(name,height,bold,italic))
    override fun createFont(model: UIFontModel): UIFont = object : UIFont {
        override fun getName() = model.name
        override fun getHeight() = model.height
        override fun isBold() = model.isBold
        override fun isItalic() = model.isItalic
        override fun isDisposed() = false
        override fun dispose() = Unit
    }
    override fun createColor(red: Int, green: Int, blue: Int): UIColor = object : UIColor {
        override fun getRed() = red
        override fun getGreen() = green
        override fun getBlue() = blue
        override fun isDisposed() = false
        override fun dispose() = Unit
    }
    override fun createColor(model: UIColorModel) = createColor(model.red,model.green,model.blue)
    override fun createImage(width: Float, height: Float): UIImage = error("Reference must not rasterise")
    override fun createImage(stream: InputStream): UIImage = error("Reference must not load images")
}
