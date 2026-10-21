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

val versionNameValue = "0.1.9"
// Second build of the same version (21 Oct 2026: diagnostics in every post); the first 0.1.7 code was never uploaded
val versionBuild = 1

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
  // FOLLO_KEYSTORE = path to the .jks, FOLLO_KEYSTORE_PASS = its store + key password, alias "upload".
  if (!System.getenv("FOLLO_KEYSTORE").isNullOrBlank() && !System.getenv("FOLLO_KEYSTORE_PASS").isNullOrBlank()) {
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
      dependencies {
        gmsImplementation(libs.play.services.location)
      }
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
