from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")

# 1) Register Just+ and its two source library dependencies in the LAMPA build.
settings = read("settings.gradle")
if "project(':justplus')" not in settings:
    settings += """
include ':justplus'
project(':justplus').projectDir = file('justplus/app')
include ':justplus-doubletapplayerview'
project(':justplus-doubletapplayerview').projectDir = file('justplus/doubletapplayerview')
include ':justplus-filechooser'
project(':justplus-filechooser').projectDir = file('justplus/android-file-chooser')
"""
# Just+ uses a dependency that may be served through JitPack.
if "https://jitpack.io" not in settings:
    settings = settings.replace(
        "mavenCentral()",
        "mavenCentral()\n        maven { url 'https://jitpack.io' }",
        1,
    )
write("settings.gradle", settings)

# 2) The embedded Media3 player requires API 23+, and its current libraries compile against API 36.
app_gradle = read("app/build.gradle")
app_gradle = re.sub(r"compileSdk\s+34", "compileSdk 36", app_gradle, count=1)
app_gradle = re.sub(r"minSdkVersion\s+16", "minSdkVersion 23", app_gradle, count=1)
app_gradle = app_gradle.replace("sourceCompatibility JavaVersion.VERSION_1_8", "sourceCompatibility JavaVersion.VERSION_17")
app_gradle = app_gradle.replace("targetCompatibility JavaVersion.VERSION_1_8", "targetCompatibility JavaVersion.VERSION_17")
app_gradle = app_gradle.replace('jvmTarget = "1.8"', 'jvmTarget = "17"')
if "implementation project(':justplus')" not in app_gradle:
    marker = "dependencies {"
    pos = app_gradle.index(marker) + len(marker)
    app_gradle = app_gradle[:pos] + "\n    implementation project(':justplus')" + app_gradle[pos:]
write("app/build.gradle", app_gradle)

props = read("gradle.properties")
props = props.replace("android.nonTransitiveRClass=true", "android.nonTransitiveRClass=false")
props = re.sub(r"android\.suppressUnsupportedCompileSdk=.*", "android.suppressUnsupportedCompileSdk=36", props)
write("gradle.properties", props)

# 3) Turn the Just+ application module into a library module compatible with LAMPA's root build.
justplus_gradle = r'''apply plugin: 'com.android.library'

android {
    namespace 'com.brouken.player'
    compileSdk 36

    defaultConfig {
        minSdkVersion 23

        buildConfigField "String", "SENTRY_DSN", "\"\""
        buildConfigField "boolean", "ENABLE_CRASH_REPORTING", "false"
        buildConfigField "boolean", "ENABLE_UPDATE", "false"
        buildConfigField "String", "APPLICATION_ID", "\"top.rootu.lampa\""
        buildConfigField "String", "VERSION_NAME", "\"embedded\""
        buildConfigField "int", "VERSION_CODE", "1"
        buildConfigField "String", "FLAVOR", "\"embedded\""
        buildConfigField "String", "FLAVOR_distribution", "\"universal\""
    }

    buildTypes {
        release {
            minifyEnabled false
        }
        debug {
            minifyEnabled false
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError false
        checkReleaseBuilds false
    }
}

dependencies {
    def media3_version = '1.11.0-beta01'
    def androidxCoreVersion = '1.8.0'

    implementation "androidx.media3:media3-session:$media3_version"
    implementation "androidx.media3:media3-datasource:$media3_version"
    implementation "androidx.media3:media3-decoder:$media3_version"
    implementation "androidx.media3:media3-common:$media3_version"
    implementation "androidx.media3:media3-container:$media3_version"
    implementation "androidx.media3:media3-extractor:$media3_version"

    implementation("androidx.media3:media3-exoplayer-dash:$media3_version") {
        exclude group: "androidx.media3", module: "media3-exoplayer"
    }
    implementation("androidx.media3:media3-exoplayer-hls:$media3_version") {
        exclude group: "androidx.media3", module: "media3-exoplayer"
    }
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3_version") {
        exclude group: "androidx.media3", module: "media3-exoplayer"
    }
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3_version") {
        exclude group: "androidx.media3", module: "media3-exoplayer"
    }

    implementation("com.suyashbelekar:exoplayerhdrutils:0.3.0") {
        exclude group: "androidx.media3", module: "media3-exoplayer"
        exclude group: "androidx.core", module: "core-ktx"
    }

    implementation 'androidx.recyclerview:recyclerview:1.4.0'
    implementation 'com.google.android.material:material:1.12.0'
    implementation 'androidx.coordinatorlayout:coordinatorlayout:1.3.0'
    implementation "androidx.core:core:$androidxCoreVersion"
    implementation 'androidx.appcompat:appcompat:1.7.1'
    implementation 'androidx.preference:preference:1.2.1'
    implementation 'com.squareup.okhttp3:okhttp:4.10.0'
    implementation 'com.sigpwned:chardet4j:78.1.0'
    implementation 'com.github.bumptech.glide:glide:4.16.0'
    implementation 'com.google.zxing:core:3.5.3'
    implementation 'io.sentry:sentry-android:8.16.0'

    implementation project(':justplus-doubletapplayerview')
    implementation project(':justplus-filechooser')
    implementation fileTree(dir: "libs", include: ["lib-*.aar"])
}
'''
write("justplus/app/build.gradle", justplus_gradle)

doubletap_gradle = r'''apply plugin: 'com.android.library'

android {
    namespace 'com.github.vkay94.dtpv'
    compileSdk 36

    defaultConfig {
        minSdkVersion 23
        vectorDrawables.useSupportLibrary = true
        consumerProguardFiles 'consumer-rules.pro'
    }

    buildTypes {
        release { minifyEnabled false }
        debug { minifyEnabled false }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError false
        checkReleaseBuilds false
    }
}
'''
write("justplus/doubletapplayerview/build.gradle", doubletap_gradle)

filechooser_gradle = r'''apply plugin: 'com.android.library'

android {
    namespace 'com.obsez.android.lib.filechooser'
    compileSdk 36
    resourcePrefix "obsez_fc__"

    defaultConfig {
        minSdkVersion 23
        targetSdkVersion 28
    }

    buildTypes {
        release { minifyEnabled false }
        debug { minifyEnabled false }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError false
        checkReleaseBuilds false
    }
}

dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    implementation 'androidx.appcompat:appcompat:1.7.1'
}
'''
write("justplus/android-file-chooser/build.gradle", filechooser_gradle)

# 4) Merge only the pieces of the Just+ manifest required by an in-process player.
manifest = r'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-feature android:name="android.software.leanback" android:required="false" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />

    <uses-permission android:name="android.permission.WRITE_SETTINGS" tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.INTERNET" />

    <queries>
        <intent>
            <action android:name="android.intent.action.OPEN_DOCUMENT" />
            <data android:mimeType="*/*" />
        </intent>
        <intent>
            <action android:name="android.intent.action.OPEN_DOCUMENT_TREE" />
        </intent>
        <intent>
            <action android:name="android.settings.PICTURE_IN_PICTURE_SETTINGS" />
        </intent>
        <intent>
            <action android:name="android.intent.action.SEND" />
            <data android:mimeType="text/plain" />
        </intent>
    </queries>

    <application>
        <meta-data
            android:name="io.sentry.auto-init"
            android:value="false"
            tools:replace="android:value" />

        <activity
            android:name="com.brouken.player.PlayerActivity"
            android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout|smallestScreenSize|uiMode|touchscreen"
            android:exported="false"
            android:launchMode="standard"
            android:supportsPictureInPicture="true"
            android:theme="@style/Theme.Player" />

        <activity
            android:name="com.brouken.player.ErrorActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:taskAffinity=""
            android:theme="@style/Theme.Error" />

        <activity
            android:name="com.brouken.player.MediaStoreChooserActivity"
            android:exported="false"
            android:configChanges="orientation"
            android:theme="@style/Transparent" />

        <activity
            android:name="com.brouken.player.SettingsActivity"
            android:exported="false"
            android:label="@string/pref_title"
            android:parentActivityName="com.brouken.player.PlayerActivity"
            android:screenOrientation="locked"
            android:theme="@style/Theme.Settings" />

    </application>
</manifest>
'''
write("justplus/app/src/main/AndroidManifest.xml", manifest)

# 5) For normal video playback, route LAMPA's existing Just+ intent contract to the
#    embedded PlayerActivity. Explicit launchPlayer overrides remain available for external players.
main_activity_path = "app/src/main/java/top/rootu/lampa/MainActivity.kt"
main_activity = read(main_activity_path)
needle = """            state?.let {
                createBaseIntent(it)?.let {
                    // Get available players
"""
replacement = """            state?.let {
                createBaseIntent(it)?.let {
                    if (!isIPTV && !isLIVE && launchPlayer.isBlank()) {
                        configureJustPlusPlayerIntent(
                            it,
                            packageName,
                            state,
                            videoTitle,
                            getPlaybackPosition(state),
                            headers
                        )
                        it.setClassName(packageName, "com.brouken.player.PlayerActivity")
                        launchPlayer(it)
                        return@let
                    }

                    // Get available players
"""
if needle not in main_activity:
    raise RuntimeError("MainActivity runPlayer insertion point not found")
main_activity = main_activity.replace(needle, replacement, 1)
write(main_activity_path, main_activity)

print("Prepared LAMPA + embedded Just+ Player build")