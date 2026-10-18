plugins { id("com.android.application") }

android {
    namespace = "com.example.dsedge"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.example.dsedge"   // same id/permission as the first bridge, so DroidDeck's client needs no change
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 2
        versionName = "0.2-sdl3"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-26",
                    "-DANDROID_STL=c++_static",
                    "-DSDL_ANDROID_JAR=OFF",
                    "-DSDL_TESTS=OFF",
                    "-DSDL_EXAMPLES=OFF",
                )
            }
        }
    }
    externalNativeBuild { cmake { path = file("jni/CMakeLists.txt") } }
    // Fixed key (throwaway, test-only) so each new build installs over the previous one.
    signingConfigs {
        create("bridge") {
            storeFile = file("bridge-debug.keystore")
            storePassword = "android"
            keyAlias = "bridge"
            keyPassword = "android"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("bridge") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}
