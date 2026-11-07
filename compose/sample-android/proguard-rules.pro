# JNI names are retained by demo-synth's consumer rules. No blanket keep-all.
# The importer/audio/Compose code is optimized by the normal Android release rules.

# The Android SavedState default factory invokes this constructor reflectively.
-keepclassmembers class com.tuxcompose.sample.StudioSession {
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}
