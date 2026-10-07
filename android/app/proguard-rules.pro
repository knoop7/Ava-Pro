# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve stack-trace line numbers so VsWakeWord/AEC crashes are diagnosable
# after R8 minifies debug + release builds (matches the enable-R8 / disable-R8
# scripts `开启R8编译.sh` / `关闭R8编译.sh`).
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# Keep data classes used by Gson and others in microwakeword
-keep class com.example.ava.microwakeword.** { *; }

-keep class com.example.ava.openwakeword.** { *; }
-keepclassmembers class com.example.ava.openwakeword.** { *; }
-dontwarn com.example.ava.openwakeword.**
-keep class com.example.ava.vswakeword.** { *; }
-keepclassmembers class com.example.ava.vswakeword.** { *; }
-dontwarn com.example.ava.vswakeword.**

# Keep protobuf generated classes
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class * implements com.google.protobuf.MessageLite { *; }

# Keep microfeatures classes that are called from JNI
-keep class com.example.microfeatures.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Software AEC pipeline: reference bus, processor, and ExoPlayer PCM tee.
# Without these, R8 renames PlaybackReferenceBus -> l4.c etc. and the
# playback-to-mic chain is invisible in release logcat/stack traces.
-keep class com.example.ava.audio.PlaybackReferenceBus { *; }
-keep class com.example.ava.audio.PlaybackReferenceBus$* { *; }
-keep class com.example.ava.audio.PlaybackReferenceTee { *; }
-keep class com.example.ava.audio.SoftwareAecProcessor { *; }
-keep class * implements androidx.media3.exoplayer.audio.TeeAudioProcessor$AudioBufferSink {
    public void flush(int, int, int);
    public void handleBuffer(java.nio.ByteBuffer);
}

# jflac references javax.sound SPI classes that don't exist on Android;
# those desktop-only code paths are never used here.
-dontwarn javax.sound.sampled.**

# SnakeYAML (transitive dependency) references java.beans introspection APIs
# that are not on the Android runtime; those code paths are never used here.
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# Keep FloatingWindowService inner View classes
-keep class com.example.ava.services.FloatingWindowService$* { *; }

# Keep all custom View classes
-keep class * extends android.view.View { *; }

# Keep BroadcastReceiver
-keep class com.example.ava.receivers.** { *; }

# Keep Service classes
-keep class com.example.ava.services.** { *; }

# Keep low-end BLE chip detection method only
-keepclassmembers class com.example.ava.bluetooth.BluetoothPresenceManager {
    public boolean isLowEndBleChip();
    public boolean isBluetoothEnabled();
}
-keepclassmembers class com.example.ava.utils.RootUtils {
    public static boolean isRootAvailable();
    public static void killBluetoothProcessAsync(kotlin.jvm.functions.Function0);
}

# Keep JSON models used by the Mod store and installed mod registry.
# These are parsed by Gson via reflection, so field renaming breaks online store,
# manifest, and registry loading after minification.
-keep class com.example.ava.mods.ModStore { *; }
-keep class com.example.ava.mods.StoreMod { *; }
-keep class com.example.ava.mods.ModManifest { *; }
-keep class com.example.ava.mods.ModConfigItem { *; }
-keep class com.example.ava.mods.ModStatusPanelItem { *; }
-keep class com.example.ava.mods.ModStatusPanelAction { *; }
-keep class com.example.ava.mods.ModEntity { *; }
-keep class com.example.ava.mods.InstalledMod { *; }
-keep class com.example.ava.mods.ModRegistry { *; }

# DexClassLoader mods (e.g. AirPlay) resolve Media3 / MediaSessionCompat by original
# class name against the host ClassLoader. R8 fullMode renames or partially shrinks
# them (Player$Listener -> D2.M; MediaSessionCompat$Token loses ctors) and the mod
# then crashes with NoClassDefFoundError / NoSuchMethodError. Keep full public APIs.
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep class androidx.media.** { *; }
-keep interface androidx.media.** { *; }
# androidx.media ships MediaSessionCompat under the legacy support package name.
-keep class android.support.v4.media.** { *; }
-keep interface android.support.v4.media.** { *; }
-keep class androidx.versionedparcelable.** { *; }
-keep interface androidx.versionedparcelable.** { *; }

# The ble-adv-proxy mod is loaded at runtime as a separate (non-obfuscated) jar and calls
# back into the host by ORIGINAL method name via reflection:
#   BleAdvHostApi.fireHomeassistantEvent(String, Map)  -> forwards esphome.ble_adv.raw_adv
#   BleAdvHostApi.runExclusiveTransmit(Runnable) / enqueueExclusiveTransmit(...) / isExclusiveActive()
#   BleAdvHostApi.pausePresenceForRawAdvertise() / awaitRawAdvertiseSettle()
#   BleAdvHostApi.setPresenceAdvertisingSuppressed(boolean)
# Without keeping these, R8 renames them and the mod's getMethod(...) throws
# NoSuchMethodException (silently caught), so raw_adv events never reach Home Assistant
# (ha-ble-adv "listen" shows None) and the exclusive advertise window is lost.
# Conversation-engine mods call ModConversationEngine.releaseWake / renewWake / isWakeCurrent
# and ConversationEngineHost by original name after claiming a wake.
-keep class com.example.ava.mods.BleAdvHostApi { *; }
-keep class com.example.ava.mods.ModConversationEngine { *; }
-keep class com.example.ava.mods.ConversationEngineHost { *; }
-keep class com.example.ava.mods.ModScreenCapture { *; }
-keep class com.example.ava.mods.ModStateCallback { *; }

# Preserve Gson metadata used by reflected JSON models.
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# ------------------------------------------------------------------
# kotlinx.serialization (DataStore settings). Without these, R8 renames
# @Serializable properties and fleet/backup JSON becomes a/b/c keys.
# Rules adapted from kotlinx.serialization rules/common.pro.
# ------------------------------------------------------------------
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences

# Fleet/Gson still reflects field names when encoding settings snapshots.
# Keep the whole settings package so JSON keys stay wakeWord / clusterAccessToken.
-keep class com.example.ava.settings.** { *; }
-keepclassmembers class com.example.ava.settings.** { *; }

# ------------------------------------------------------------------
# Shizuku user shell (scrcpy push / shell.exec). R8 was removing
# IShellService$Stub and renaming ShellService → v3.a, so
# executeCommand returns -1 and scrcpy lastError=push_failed_-1.
# ------------------------------------------------------------------
-keep class com.example.ava.IShellService { *; }
-keep class com.example.ava.IShellService$* { *; }
-keep class com.example.ava.shizuku.ShellService { *; }
-keepclassmembers class com.example.ava.shizuku.ShellService { *; }
-keep class com.example.ava.utils.ShizukuUtils { *; }
-keep class com.example.ava.utils.RootUtils { *; }

# Gecko builds child service names from GeckoChildProcessServices.class.getName(). R8 used to
# rename only the outer class while manifest-declared $tab*/$gpu/$socket services kept their
# original names, so Gecko looked up a non-existent org.mozilla.gecko.process.a$tab0 and crashed
# with MOZ_CRASH(JNI exception). Keep the complete service family name-stable.
-keep class org.mozilla.gecko.process.GeckoChildProcessServices { *; }
-keep class org.mozilla.gecko.process.GeckoChildProcessServices$* { *; }
