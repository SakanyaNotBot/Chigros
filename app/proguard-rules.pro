# ============================================================
# ProGuard / R8 Rules for com.wuying.phigros
# ============================================================

# ---------- Generic attributes ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ---------- Android R class (resource IDs for findViewById, etc.) ----------
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ---------- Android components (registered in AndroidManifest) ----------
# Keep Activities, Application, and their inherited framework methods
-keep public class com.wuying.phigros.PhigrosApp extends android.app.Application { *; }
-keep public class com.wuying.phigros.ui.ChartSelectActivity extends androidx.appcompat.app.AppCompatActivity { *; }
-keep public class com.wuying.phigros.ui.CalibrationActivity extends androidx.appcompat.app.AppCompatActivity { *; }
-keep public class com.wuying.phigros.ui.PlayActivity extends androidx.appcompat.app.AppCompatActivity { *; }
-keep public class com.wuying.phigros.ui.ResultActivity extends androidx.appcompat.app.AppCompatActivity { *; }
-keep public class com.wuying.phigros.ui.SelectAudioFragment extends androidx.fragment.app.Fragment { *; }
-keep public class com.wuying.phigros.ui.SelectChartFragment extends androidx.fragment.app.Fragment { *; }
-keep public class com.wuying.phigros.ui.SelectVideoFragment extends androidx.fragment.app.Fragment { *; }
-keep public class com.wuying.phigros.ui.SelectPagerAdapter { *; }

# ---------- Serializable (Intent extras, Bundle) ----------
# Keep PlayResult fully — class name + fields must not be obfuscated for deserialization
-keep class com.wuying.phigros.game.PlayResult implements java.io.Serializable { *; }
-keepclassmembers class com.wuying.phigros.game.PlayResult {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---------- JNI: NativeAudioEngine ----------
-keep,allowobfuscation class com.wuying.phigros.audio.NativeAudioEngine {
    <methods>;
}

# ---------- Jackson: data classes deserialized from JSON ----------
# Jackson uses reflection to instantiate these and set fields, so we
# must keep both the no-arg constructor and the fields.
-keep class com.wuying.phigros.game.Chart {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.JudgeLine {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.JudgeLine$EventLayer {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.JudgeLine$NoteControlPoint {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.Note {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.SpeedEvent {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.LineEvent {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.MoveEvent {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.ColorEvent {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.TextEvent {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.ResPackInfo {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.SkinConfig {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.ui.SkinManager {
    public <methods>;
}
-keep class com.wuying.phigros.ui.SkinManager$SkinEntry {
    <fields>;
}
-keep class com.wuying.phigros.game.PrprEffect {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.PrprEffect$ConstFloat {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.PrprEffect$ConstVec {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.PrprEffect$FloatEvents {
    <init>();
    <fields>;
}

# Jackson custom deserializer
-keep class com.wuying.phigros.game.ChartLoader$LenientBooleanDeserializer { *; }

# Enum used by ChartLoader
-keepclassmembers enum com.wuying.phigros.game.ChartLoader$ChartFormat {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- GameRenderer (GLSurfaceView.Renderer + inner classes) ----------
-keep class com.wuying.phigros.game.GameRenderer {
    public <methods>;
}
# Keep Callback interface (PlayActivity implements it)
-keep interface com.wuying.phigros.game.GameRenderer$Callback { *; }
# Keep inner classes used for effect rendering and state tracking
-keep class com.wuying.phigros.game.GameRenderer$Texture { *; }
-keep class com.wuying.phigros.game.GameRenderer$FboTex { *; }
-keep class com.wuying.phigros.game.GameRenderer$PrprShaderProgram { *; }
-keep class com.wuying.phigros.game.GameRenderer$TouchState { *; }
-keep class com.wuying.phigros.game.GameRenderer$FlickTracker { *; }
-keep class com.wuying.phigros.game.GameRenderer$HitEffect { *; }
-keep class com.wuying.phigros.game.GameRenderer$BadEffect { *; }
-keep class com.wuying.phigros.game.GameRenderer$HoldUv { *; }
-keep class com.wuying.phigros.game.HitOffsetIndicator { *; }
-keep class com.wuying.phigros.game.HitOffsetIndicator$Mark { *; }

# ---------- GameGLSurfaceView (GLSurfaceView subclass) ----------
-keep class com.wuying.phigros.game.GameGLSurfaceView extends android.opengl.GLSurfaceView { *; }
-keep class com.wuying.phigros.game.GameGLSurfaceView$MsaaConfigChooser { *; }

# ---------- RenderEffect / Shader (used by ResultActivity blur on Android 12+) ----------
-keep class android.graphics.RenderEffect { *; }
-keep class android.graphics.Shader { *; }
-keep class android.graphics.Shader$TileMode { *; }

# ---------- Blur FBO / OpenGL ----------
# Keep GLES20 calls — framework class, but ensure no warnings
-dontwarn android.opengl.GLES20
-dontwarn android.opengl.GLES30
-dontwarn android.opengl.EGL14
-dontwarn android.opengl.GLSurfaceView

# ---------- Utility classes (file I/O, chart parsing, audio decoding) ----------
-keep class com.wuying.phigros.util.** { *; }
-keep class com.wuying.phigros.audio.** { *; }

# ---------- Chart parsers (RPE/PEC) ----------
-keep class com.wuying.phigros.game.RpeChartParser { *; }
-keep class com.wuying.phigros.game.PecChartParser { *; }
-keep class com.wuying.phigros.game.ChartLoader { *; }
-keep class com.wuying.phigros.game.Easing { *; }
-keep class com.wuying.phigros.game.EventUtils { *; }
-keep class com.wuying.phigros.game.EventCursor { *; }
-keep class com.wuying.phigros.game.GameConstants { *; }
-keep class com.wuying.phigros.game.ClickEffectItem { *; }
-keep class com.wuying.phigros.game.BpmTimeline { *; }
-keep class com.wuying.phigros.game.MathUtils { *; }

# ---------- AndroidX / Material ----------
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# ---------- Native library methods ----------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- View constructors (XML layout inflation uses reflection) ----------
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

# ---------- Keep enum values ----------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- Replay data (Jackson serialization) ----------
-keep class com.wuying.phigros.game.ReplayData {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.ReplayData$ReplayMeta {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.game.ReplayData$ReplayEntry {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.ui.ReplayManager {
    public <methods>;
}
-keep class com.wuying.phigros.ui.ReplayManager$ReplayInfo {
    <fields>;
}
-keep class com.wuying.phigros.ui.ReplayManager$InfoJson {
    <init>();
    <fields>;
}
-keep class com.wuying.phigros.ui.SelectReplayFragment extends androidx.fragment.app.Fragment { *; }

# ---------- Jackson library rules ----------
-keep class com.fasterxml.jackson.core.** { *; }
-keep interface com.fasterxml.jackson.core.** { *; }
-dontwarn com.fasterxml.jackson.core.**
-keep class com.fasterxml.jackson.databind.** { *; }
-keep interface com.fasterxml.jackson.databind.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-keep class com.fasterxml.jackson.annotation.** { *; }
-dontwarn com.fasterxml.jackson.annotation.**
# Jackson uses java.beans classes not available on Android
-dontwarn java.beans.**

# ---------- Oboe native audio library ----------
-dontwarn com.google.oboe.**
-keep class com.google.oboe.** { *; }

# ---------- AssetManager / BitmapFactory ----------
# Framework classes used for image loading in ResultActivity
-keep class android.content.res.AssetManager { *; }
-keep class android.graphics.BitmapFactory { *; }
-keep class android.graphics.Bitmap { *; }

# ---------- Debug ----------
# Remove all log calls in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
