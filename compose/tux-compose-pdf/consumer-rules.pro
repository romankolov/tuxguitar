# All print-engine calls are typed. Keep native resource/model descriptors used by
# the original context's class-name keyed singleton registry when optimizing apps.
-keepnames class com.tuxcompose.pdf.internal.tuxguitar.util.singleton.**

# PDFBox-Android declares its JPEG-2000 codec compileOnly. This exporter accepts
# decoded music only and emits paths/text (no image XObjects); it never decodes JPX.
# Do not ship an unrelated native image codec just to satisfy that optional branch.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
