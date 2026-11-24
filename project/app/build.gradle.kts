import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.hilt.android)
  // Kotlin is provided by AGP's built-in Kotlin support; the legacy-kapt plugin
  // drives data binding's annotation processing (everything else is on KSP).
  alias(libs.plugins.android.legacy.kapt)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlin.plugin.serialization)
}

apply<EspressoMetadataEmbeddingPlugin>()

val gmsImplementation: Configuration by configurations.creating

val versionNameValue = "0.1.50" // 23 Nov 2026: photos & video from the field (📷: thumb at once, bigger copy when basecamp asks, original on Wi-Fi); (0.1.49, 22 Nov 2026 (later): the banner in "Share everywhere I go" is shorter, "Sharing location everywhere"; (0.1.48, 22 Nov 2026: other people's dots as on the live map (FP078), ghosts on a circle's edge gone + walking-only lines (FP079), three-state ticks (FP083), "Share everywhere I go"; (0.1.47, 20 Nov 2026: POI names show whenever the pins do; (0.1.46, 19 Nov 2026 (later): the simple screen: no card, two-way symbol in the banner, "…" button with layer buttons (tracklogs, grids, labels, hillshade, slope, contours, POI) and rotate, power, compass, searches; (0.1.45, 19 Nov 2026: search areas only (FP055): grey mask outside the circles, the last area on opening, "Waiting to arrive at your first search area"; (0.1.44, 18 Nov 2026 (later): the clean view keeps other phones' tracklogs; (0.1.43, 18 Nov 2026: phones-in-circles drop-down + every live-map search + clean view, on the phones the server names (status `all`); (0.1.42, 16 Nov 2026: compass heading pill (FP019), arrow at the middle of the whole screen (FP033), check-in when the offline map finishes or stops (FP034); (0.1.41, 15 Nov 2026 (later): checks in without a fix (the hello goes after 15 s), stale fix greyed; (0.1.40, 15 Nov 2026: MGA position pill, ticks app·ring·map, grey cells past U-turns, full-screen Always step (0.1.39, 14 Nov 2026: screen round 2 (power button on the card, ticks, position pill, sideways panels, rotate button) (0.1.38: compact screen + power button)))))
// Second build of the same version (21 Oct 2026: diagnostics in every post); the first 0.1.7 code
// was never uploaded
val versionBuild = 0 // 2 Nov 2026

fun generateVersionCode(versionName: String): Int {
  val parts = versionName.split(".")
  val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
  val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
  val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
  return 400000000 + major * 10000000 + minor * 100000 + patch * 1000 + versionBuild
}

val generatedVersionCode = generateVersionCode(versionNameValue)
val envVersionCode = System.getenv("VERSION_CODE")?.toInt()
val packageVersionCode: Int =
    if (envVersionCode != null && envVersionCode > generatedVersionCode) {
      envVersionCode
    } else {
      generatedVersionCode
    }
val manuallySetVersion: Boolean = System.getenv("VERSION_CODE") != null
val enablePlayPublishing: Boolean = !System.getenv("ANDROID_PUBLISHER_CREDENTIALS").isNullOrBlank()

android {
  compileSdk = 36
  namespace = "org.owntracks.android"

  defaultConfig {
    applicationId = "au.org.follo.tracks"
    minSdk = 24
    targetSdk = 36

    versionCode = packageVersionCode
    versionName = versionNameValue

    val localeCount = fileTree("src/main/res/").matching { include("**/strings.xml") }.files.size

    buildConfigField(
        "int",
        "TRANSLATION_COUNT",
        localeCount.toString(),
    )

    // FOLLO push "check in" (29 Oct 2026): Firebase is set up in code (no google-services plugin)
    // from
    // app/follo_firebase.properties; empty values = no Firebase, no push token, everything else as
    // before.
    val firebaseProps =
        Properties().also { props ->
          file("follo_firebase.properties")
              .takeIf { it.exists() }
              ?.inputStream()
              ?.use { props.load(it) }
        }
    buildConfigField(
        "String",
        "FIREBASE_APP_ID",
        "\"${firebaseProps.getProperty("firebase.appId", "").trim()}\"",
    )
    buildConfigField(
        "String",
        "FIREBASE_API_KEY",
        "\"${firebaseProps.getProperty("firebase.apiKey", "").trim()}\"",
    )
    buildConfigField("String", "FIREBASE_PROJECT_ID", "\"follo-tracks-play\"")
    // 1 Nov 2026: the heading shows "FOLLO Tracks <version> (<build>)" so base camp can read a phone's build
    // off its screen (Alex); versionBuild is not recoverable from versionName.
    buildConfigField("int", "FOLLO_BUILD", versionBuild.toString())
    buildConfigField("String", "FIREBASE_SENDER_ID", "\"1035664082070\"")

    testInstrumentationRunner = "org.owntracks.android.testutils.hilt.CustomTestRunner"

    testInstrumentationRunnerArguments.putAll(
        mapOf(
            "clearPackageData" to "false",
            "coverage" to "true",
            "disableAnalytics" to "true",
            "useTestStorageService" to "false",
        ),
    )
    javaCompileOptions {
      annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" }
    }
  }

  androidResources { generateLocaleConfig = true }

  // FOLLO: the upload key lives outside the repo (~/.config/follo on the build PC, a secret in CI).
  // FOLLO_KEYSTORE = path to the .jks, FOLLO_KEYSTORE_PASS = its store + key password, alias
  // "upload".
  if (
      !System.getenv("FOLLO_KEYSTORE").isNullOrBlank() &&
          !System.getenv("FOLLO_KEYSTORE_PASS").isNullOrBlank()
  ) {
    signingConfigs {
      register("release") {
        keyAlias = "upload"
        keyPassword = System.getenv("FOLLO_KEYSTORE_PASS")
        storeFile = file(System.getenv("FOLLO_KEYSTORE")!!)
        storePassword = System.getenv("FOLLO_KEYSTORE_PASS")
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
        enableV4Signing = true
      }
    }
  }

  buildTypes {
    named("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles.addAll(
          listOf(
              getDefaultProguardFile("proguard-android-optimize.txt"),
              file("proguard-rules.pro"),
          ),
      )
      signingConfig = signingConfigs.findByName("release")
    }

    named("debug") {
      isMinifyEnabled = false
      isShrinkResources = false
      isPseudoLocalesEnabled = true
      proguardFiles.addAll(
          listOf(
              getDefaultProguardFile("proguard-android-optimize.txt"),
              file("proguard-rules.pro"),
          ),
      )
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
    }
  }

  buildFeatures {
    buildConfig = true
    dataBinding = true
    viewBinding = true
    resValues = true
  }

  dataBinding { addKtx = true }

  packaging {
    resources.excludes.add("META-INF/*")
    jniLibs.useLegacyPackaging = false
  }

  lint {
    baseline = file("../../lint/lint-baseline.xml")
    lintConfig = file("../../lint/lint.xml")
    checkAllWarnings = true
    warningsAsErrors = false
    abortOnError = false
    disable.addAll(
        setOf(
            "TypographyFractions",
            "TypographyQuotes",
            "Typos",
        ),
    )
  }
  testOptions {
    execution = "ANDROIDX_TEST_ORCHESTRATOR"
    animationsDisabled = true
    unitTests { isIncludeAndroidResources = true }
    managedDevices {
      val pixel2api27 =
          localDevices.create("pixel2api27") {
            device = "Pixel 2"
            apiLevel = 27
            systemImageSource = "aosp"
          }
      val pixel8api34 =
          localDevices.create("pixel8api34") {
            device = "Pixel 8"
            apiLevel = 34
            systemImageSource = "google-atd"
          }
      groups {
        create("phoneAtd") {
          targetDevices.add(pixel2api27)
          targetDevices.add(pixel8api34)
        }
      }
    }
  }

  tasks.withType<Test> {
    testLogging {
      events(
          TestLogEvent.SKIPPED,
          TestLogEvent.FAILED,
          TestLogEvent.STANDARD_OUT,
          TestLogEvent.STANDARD_ERROR,
      )
      setExceptionFormat("full")
      showStandardStreams = true

      showCauses = true
      showExceptions = true
      showStackTraces = true
    }
    reports.junitXml.required.set(true)
    reports.html.required.set(true)
    outputs.upToDateWhen { false }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    isCoreLibraryDesugaringEnabled = true
  }

  flavorDimensions.add("locationProvider")
  productFlavors {
    create("gms") {
      dimension = "locationProvider"
      dependencies { gmsImplementation(libs.play.services.location) }
    }
  }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

tasks.withType<Test> {
  systemProperties["junit.jupiter.execution.parallel.enabled"] = false
  systemProperties["junit.jupiter.execution.parallel.mode.default"] = "same_thread"
  systemProperties["junit.jupiter.execution.parallel.mode.classes.default"] = "concurrent"
  maxParallelForks = 1
}

tasks.withType<JavaCompile>().configureEach { options.isFork = true }

tasks.matching { it.name.endsWith("AndroidTest") }.configureEach { outputs.upToDateWhen { false } }

dependencies {
  implementation(libs.bundles.kotlin)
  implementation(libs.bundles.androidx)
  implementation(libs.androidx.test.espresso.idling)

  implementation(libs.google.material)

  // Explicit dependency on conscrypt to give up-to-date TLS support on all devices
  implementation(libs.conscrypt)

  // Mapping
  implementation(libs.osmdroid)

  // Connectivity
  implementation(libs.okhttp)
  // Photos & video from the field (23 Nov 2026): AVIF thumbs/previews (avif-coder: Apache-2.0 / BSD-3, no GPL parts),
  // H.264 video previews (Media3 Transformer, Apache-2.0)
  implementation(libs.avif.coder)
  implementation(libs.media3.transformer)
  implementation(libs.media3.effect)
  implementation(libs.media3.common)
  // The app's own video screen (24 Nov 2026): CameraX records the ORIGINAL at 1080p H.264 (Apache-2.0)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.video)
  implementation(libs.camerax.view)

  // FOLLO push "check in" (29 Oct 2026): Firebase Cloud Messaging, initialised in code
  // (App.onCreate)
  implementation(libs.firebase.messaging)

  // Utility libraries
  implementation(libs.bundles.hilt)
  implementation(libs.square.tape2)
  implementation(libs.timber)
  implementation(libs.bundles.androidx.room)
  implementation(libs.bundles.objectbox.migration)
  implementation(libs.kotlin.datetime)
  implementation(libs.kotlinx.serialization.json)

  // Widget libraries
  implementation(libs.widgets.materialize) { artifact { type = "aar" } }

  // Preprocessors
  ksp(libs.bundles.ksp.hilt)
  ksp(libs.androidx.room.compiler)

  kspTest(libs.bundles.ksp.hilt)

  testImplementation(libs.mockito.kotlin)
  testImplementation(libs.androidx.core.testing)
  testImplementation(libs.kotlin.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)

  androidTestImplementation(libs.bundles.androidx.test)

  // Hilt Android Testing
  androidTestImplementation(libs.hilt.android.testing)
  kspAndroidTest(libs.hilt.compiler)

  androidTestImplementation(libs.barista) { exclude("org.jetbrains.kotlin") }
  androidTestImplementation(libs.okhttp.mockwebserver)
  androidTestImplementation(libs.square.leakcanary)

  androidTestUtil(libs.bundles.androidx.test.util)

  coreLibraryDesugaring(libs.desugar)
}
