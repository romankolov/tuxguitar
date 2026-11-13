// SPDX-License-Identifier: LGPL-2.1-or-later
package com.tuxcompose.pdf

import com.tuxcompose.pdf.internal.tuxguitar.ui.resource.*
import java.io.InputStream

internal data class PrintFont(val name: String,val size: Float,val bold: Boolean=false,val italic: Boolean=false)
internal data class PrintColor(val red: Int,val green: Int,val blue: Int)
internal sealed interface PrintSegment {
    data class Move(val x: Float,val y: Float): PrintSegment
    data class Line(val x: Float,val y: Float): PrintSegment
    data class Cubic(val x1: Float,val y1: Float,val x2: Float,val y2: Float,val x: Float,val y: Float): PrintSegment
    data class Circle(val x: Float,val y: Float,val diameter: Float): PrintSegment
}
internal sealed interface PrintOp {
    data class Path(val segments: List<PrintSegment>,val style: Int,val width: Float,val dash: List<Float>,
        val phase: Float,val roundCap: Boolean,val foreground: PrintColor,val background: PrintColor): PrintOp
    data class Text(val value: String,val x: Float,val baseline: Float,val font: PrintFont,val color: PrintColor): PrintOp
}
internal interface PrintMetrics {
    fun width(text: String,font: PrintFont): Float
    /** PDFPainter.getFMAscent uses ascent of the DIGITS, not the font's global ascender. */
    fun digitAscent(font: PrintFont): Float
}
internal class NativeFont(name: String,height: Float,bold: Boolean,italic: Boolean): UIFontModel(name,height,bold,italic),UIFont {
    private var disposed=false
    override fun isDisposed()=disposed
    override fun dispose() {disposed=true}
}
internal class NativeColor(red: Int,green: Int,blue: Int): UIColorModel(red,green,blue),UIColor {
    private var disposed=false
    override fun isDisposed()=disposed
    override fun dispose() {disposed=true}
}
internal class PrintResources: UIResourceFactory {
    override fun createFont(name: String,height: Float,bold: Boolean,italic: Boolean): UIFont=NativeFont(name,height,bold,italic)
    override fun createFont(model: UIFontModel)=createFont(model.name,model.height,model.isBold,model.isItalic)
    override fun createColor(red: Int,green: Int,blue: Int): UIColor=NativeColor(red,green,blue)
    override fun createColor(model: UIColorModel)=createColor(model.red,model.green,model.blue)
    // PDFResourceFactory rejects images. With native buffering off, the engine emits vectors.
    override fun createImage(width: Float,height: Float): UIImage=throw UnsupportedOperationException("Native PDF uses vector painters, not image buffers")
    override fun createImage(input: InputStream): UIImage=throw UnsupportedOperationException("Native PDF uses vector painters, not images")
}

/** UIPainter adapter following PDFPainter's state/baseline/path semantics exactly. */
internal class PrintPainter(private val metrics: PrintMetrics,private val cancelled: ()->Unit,
    private val sink: (PrintOp)->Unit): UIPainter {
    private var disposed=false
    private var style=0
    private val path=mutableListOf<PrintSegment>()
    private var foreground=PrintColor(0,0,0)
    private var background=PrintColor(255,255,255)
    private var font=PrintFont("Helvetica",9f)
    private var width=1f
    private var dash=listOf(1f,0f)
    private var phase=0f
    private var roundCap=false
    fun pageStart() {
        disposed=false;path.clear();style=0
        foreground=PrintColor(0,0,0);background=PrintColor(255,255,255);font=PrintFont("Helvetica",9f)
        width=1f;dash=listOf(1f,0f);phase=0f;roundCap=false
    }
    override fun dispose() {disposed=true}
    override fun isDisposed()=disposed
    override fun initPath(value: Int) {cancelled();style=value;path.clear()}
    override fun initPath()=initPath(UIPainter.PATH_DRAW)
    override fun closePath() {
        cancelled()
        if(style!=0 && path.isNotEmpty()) sink(PrintOp.Path(path.toList(),style,width,dash,phase,roundCap,foreground,background))
        style=0;path.clear() // native closePath is a paint/flush, NOT geometric closure
    }
    override fun moveTo(x: Float,y: Float) {path+=PrintSegment.Move(x,y)}
    override fun lineTo(x: Float,y: Float) {path+=PrintSegment.Line(x,y)}
    override fun cubicTo(x1: Float,y1: Float,x2: Float,y2: Float,x: Float,y: Float) {path+=PrintSegment.Cubic(x1,y1,x2,y2,x,y)}
    override fun addCircle(x: Float,y: Float,width: Float) {path+=PrintSegment.Circle(x,y,width)}
    override fun addRectangle(x: Float,y: Float,width: Float,height: Float) {
        moveTo(x,y);lineTo(x+width,y);lineTo(x+width,y+height);lineTo(x,y+height);lineTo(x,y)
    }
    override fun drawString(value: String,x: Float,y: Float) {cancelled();sink(PrintOp.Text(value,x,y,font,foreground))}
    override fun setFont(value: UIFont) {font=PrintFont(value.name,value.height,value.isBold,value.isItalic)}
    override fun setForeground(value: UIColor) {foreground=PrintColor(value.red,value.green,value.blue)}
    override fun setBackground(value: UIColor) {background=PrintColor(value.red,value.green,value.blue)}
    override fun setLineWidth(value: Float) {width=value}
    override fun setLineStyleSolid() {dash=listOf(1f,0f);phase=0f}
    override fun setLineStyleDot() {roundCap=true;dash=listOf(0f,2f);phase=1f}
    override fun setLineStyleDash() {roundCap=true;dash=listOf(1f,2f);phase=1f}
    override fun setLineStyleDashDot()=setLineStyleDash()
    override fun getFontSize()=font.size
    override fun getFMBaseLine()=0f
    override fun getFMTopLine()=metrics.digitAscent(font)
    override fun getFMMiddleLine()=getFMTopLine()/2f
    override fun getFMHeight()=getFMTopLine()
    override fun getFMWidth(text: String)=metrics.width(text,font)
    override fun setAlpha(alpha: Int) { /* PDFPainter does not implement alpha */ }
    override fun setAntialias(enabled: Boolean) { /* PDFPainter leaves this to the PDF viewer */ }
    override fun drawImage(image: UIImage,x: Float,y: Float) {error("Unexpected native PDF image")}
    override fun drawImage(image: UIImage,sx: Float,sy: Float,sw: Float,sh: Float,x: Float,y: Float,w: Float,h: Float) {error("Unexpected native PDF image")}
}
