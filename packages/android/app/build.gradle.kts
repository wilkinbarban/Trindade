// Imported rather than qualified as java.util.Properties / java.net.URI: inside a Kotlin build script the
// name `java` resolves to the Java plugin's project extension, not to the package, so the qualified form
// does not compile here.
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // The serialization compiler plugin, which D1 was missing. kotlinx-serialization-json alone is the
    // runtime: without this plugin a @Serializable class compiles and then fails at the first encode
    // or decode, because no serializer was ever generated for it. The contract types are all
    // @Serializable, so this is what makes them usable rather than merely present.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// -------------------------------------------------------------------------------------------------
// What this build declares about itself
//
// The values below are resolved at configuration time from three sources, in the order a caller would
// expect to override them: a Gradle property (-P), then the environment, then a gitignored
// keystore.properties beside the module. The names are the same in all three places, so a value set
// anywhere is the same knob, and the release signing values are only ever the operator's -- nothing in
// this file holds a password.
// -------------------------------------------------------------------------------------------------

// keystore.properties is the local, non-CI source for the signing values. Both plausible locations are
// read rather than one being guessed: the module directory and the Gradle root beside it. A file that
// is silently ignored is the exact failure this slice exists to remove, and picking the wrong one of two
// reasonable locations produces precisely that.
val keystorePropertiesFile = listOf(
    project.file("keystore.properties"),
    rootProject.file("keystore.properties"),
).firstOrNull { it.isFile }
val keystoreProperties = Properties().apply {
    keystorePropertiesFile?.inputStream()?.use { load(it) }
}

fun declaredValue(propertyName: String, environmentName: String, keystoreKey: String? = null): String? =
    (project.findProperty(propertyName) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: keystoreKey?.let { keystoreProperties.getProperty(it) }?.takeIf { it.isNotBlank() }

// The version is declared by whoever builds rather than hand-edited here: the tag workflow computes it
// from the tag (see .github/workflows/android-release.yml and docs/release-android.md). Debug keeps a
// placeholder so a local build needs no properties at all; a release does not, and the guard further
// down is what enforces that.
val declaredVersionName = declaredValue("versionName", "TRINDADE_VERSION_NAME")
val declaredVersionCodeText = declaredValue("versionCode", "TRINDADE_VERSION_CODE")
val declaredVersionCode = declaredVersionCodeText?.toIntOrNull()

// Computed once, and used for both defaultConfig and the BuildConfig fields below. Two computations of
// the same value is how "installed 0.3.0, displays 0.2.0" happens.
val effectiveVersionName = declaredVersionName ?: "0.1.0"
val effectiveVersionCode = declaredVersionCode ?: 1

// Where this app is published, so the Perfil screen can offer the way to the download without an address
// typed into its source.
//
// This one has a default while apiBaseUrl deliberately does not, and the difference is what each value is
// about rather than a relaxation of the rule: a base URL is a fact about a deployment that must never be
// guessed, while where this project publishes its APK is a fact about the project. The day the APK moves --
// this deployment's own nginx, say -- one property changes and no screen is edited.
//
// The default is the releases *page* rather than a versioned asset URL, and that is deliberate: GitHub
// redirects /releases/latest to the newest release, so the constant stays true release after release. A URL
// naming trindade-0.2.0.apk would pin the app to one release and go stale the day the next APK is
// published, sending the operator to a download link that never receives anything new.
//
// The same address is written down a second time in README.md, in the sentence that tells the crew where
// this APK is published, because a README and a Gradle default cannot be one value here. The duplication is
// therefore deliberate and each copy names the other: if the page moves, this line and that one change
// together. Nothing reads the README from here, and no mechanism is invented to make the two agree.
val defaultReleasesUrl = "https://github.com/wilkinbarban/Trindade/releases/latest"

// Read at script level as well as inside defaultConfig, because the release guard below runs outside the
// android block and cannot see a value that only exists in there. One read, so the field the app receives
// and the string the guard checks are the same value rather than two that agree today.
val declaredReleasesUrl = (project.findProperty("releasesUrl") as String?) ?: defaultReleasesUrl

// The release signing values, as four separate knobs. Nothing here is required to configure a build:
// an absent keystore means an unsigned release APK, which is what the CI lane builds on purpose.
val releaseKeystorePath = declaredValue("trindadeKeystorePath", "TRINDADE_KEYSTORE_PATH", "trindadeKeystorePath")
val releaseKeystorePassword =
    declaredValue("trindadeKeystorePassword", "TRINDADE_KEYSTORE_PASSWORD", "trindadeKeystorePassword")
val releaseKeyAlias = declaredValue("trindadeKeyAlias", "TRINDADE_KEY_ALIAS", "trindadeKeyAlias")
// One password is the normal case: a PKCS12 store built with keytool uses the same password for the store
// and for the key inside it, so the key password only has to be given for the other kind of store.
val releaseKeyPassword = declaredValue("trindadeKeyPassword", "TRINDADE_KEY_PASSWORD", "trindadeKeyPassword")
    ?: releaseKeystorePassword

// The path is resolved through project.file, so both an absolute path outside the repository (which is
// where the keystore lives) and a path relative to this module work.
val releaseKeystoreFile = releaseKeystorePath?.let { project.file(it) }?.takeIf { it.isFile }
val missingSigningSettings = buildList {
    if (releaseKeystorePath == null) add("trindadeKeystorePath")
    if (releaseKeystorePassword == null) add("trindadeKeystorePassword")
    if (releaseKeyAlias == null) add("trindadeKeyAlias")
    if (releaseKeystorePath != null && releaseKeystoreFile == null) {
        add("a readable keystore at '$releaseKeystorePath'")
    }
}
val releaseSigningConfigured = missingSigningSettings.isEmpty()
// Any one of the three settings being present counts as an attempt. That distinction is what lets the
// guard tell "this project has no keystore, build unsigned" apart from "someone tried to sign this build
// and got it half right", which produce the same artifact and must not produce the same silence.
val releaseSigningStarted = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias).any { it != null }

// Opt-in, and only the release workflow opts in. The CI lane deliberately has no keystore and builds an
// unsigned release, which is how that lane proves the release path still builds at all.
val requireSigned = (project.findProperty("requireSigned") as String?)?.equals("true", ignoreCase = true) == true

// -------------------------------------------------------------------------------------------------
// The one URL rule every declared URL shares
//
// Three fields below are a URL this app speaks to or a link it hands somebody: apiBaseUrl and
// githubApiBaseUrl become Retrofit base URLs, and releasesUrl becomes the address of a button the crew is
// told to tap. All three fail the same way when they are wrong -- the string compiles, the APK installs,
// and the failure surfaces at runtime inside a library that never names the property -- so all three are
// checked by one function.
//
// That it is one function rather than three checks is the point, and it is not tidiness. The apiBaseUrl
// guard was a prefix test that accepted "https://", the githubApiBaseUrl guard was written beside it by
// copying that test, and releasesUrl was the one that had already been corrected. A rule with three copies
// is a rule that is two edits away from going wrong again; this is the one place it lives, and a fourth
// URL field is a call to this function rather than a fourth copy.
// -------------------------------------------------------------------------------------------------

/**
 * Fails the build unless [value] can be used as the URL [fieldName] declares, naming the field and what
 * it needs.
 *
 * The rule is the one the releasesUrl guard grew into: parse the value as a URI, require an absolute URL
 * whose scheme is one of [allowedSchemes] and whose host is not blank, and refuse the characters that
 * cannot appear inside the Java string literal the BuildConfig field is generated from. A prefix test
 * cannot do any of that -- "https://" passes a `startsWith("https://")` check and carries no host, and
 * the Retrofit built from it throws on the first request -- which is exactly the shape of the bug this
 * replaces.
 *
 * [sentenceLead] is how the failure opens, because the two voices here differ: a defaultConfig guard
 * speaks about the field ("apiBaseUrl must be ...") while a release guard speaks in the build's release
 * voice ("A release build requires its apiBaseUrl to be ..."). [reason] is the per-field sentence that
 * follows. The per-field extras stay with the field rather than here: the trailing slash the two base
 * URLs need is a second require beside each call, and releasesUrl has none.
 */
fun requireUsableUrl(
    fieldName: String,
    value: String,
    allowedSchemes: Set<String>,
    reason: String,
    sentenceLead: String = "$fieldName must be",
) {
    // Checked before the URL is parsed, so a value carrying one of these characters is refused for that
    // reason rather than for whatever the parser makes of the same string.
    require(value.none { it == '"' || it == '\\' || it == '\n' || it == '\r' }) {
        "$fieldName must not contain a double quote, a backslash or a line break, but got '$value'. " +
            "This value is written into the generated BuildConfig as a Java string literal, and none of " +
            "those characters can appear inside one, so the build would stop with a compile error about " +
            "a file this property is not named in."
    }

    val uri = runCatching { URI(value) }.getOrNull()
    // Lowercased for the sentence below, and compared case-insensitively, so a value is judged the same way
    // whatever case either side happens to be written in.
    val schemes = allowedSchemes.map { it.lowercase() }
    val declaredScheme = uri?.scheme
    // The guard has three requirements and no others: the value parsed as a URI, its scheme is one of the
    // allowed ones, and it carries a host. The first conjunct is load-bearing rather than symmetrical -- it
    // is what lets `uri.host` be read at all below -- so it stays.
    //
    // Absoluteness is deliberately not tested here, and not because it does not matter: the scheme test is
    // already that test. `URI.isAbsolute` *is* "this URI has a scheme component" -- that is its definition in
    // java.net.URI -- so a URI whose scheme matches one of the allowed ones necessarily has a scheme, and an
    // `isAbsolute` conjunct beside the comparison could never change the outcome. Left in place it would read
    // as an independent requirement to satisfy, in the one rule that exists to stop having copies of itself.
    //
    // There is no separate `scheme != null` either, for the same reason one step further along: a null scheme
    // matches none of the allowed ones. The comparison does not rest on the reader knowing that either, since
    // `String.equals` takes a nullable argument -- a null scheme is compared and answers false rather than
    // thrown at.
    require(
        uri != null &&
            schemes.any { it.equals(declaredScheme, ignoreCase = true) } &&
            !uri.host.isNullOrBlank(),
    ) {
        "$sentenceLead an absolute ${schemes.joinToString("/")} URL with a host, but got '$value'. $reason"
    }
}

android {
    namespace = "com.trindade.app"
    // The pinned androidx/OkHttp versions (Compose 1.12.x, OkHttp 5.5.0) require API 37;
    // compileSdk is build-time only and stays independent of the targetSdk product floor.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.trindade.app"
        // Android 8.0 is a deliberate floor: field devices still on Oreo remain common.
        minSdk = 26
        targetSdk = 35
        versionCode = effectiveVersionCode
        versionName = effectiveVersionName

        // The base URL is configuration, never a constant: the same build must be able to point at a
        // deployment that does not exist yet. The default targets the emulator host loopback, which
        // is the one cleartext host the debug network security config exempts.
        //
        // The value is validated rather than accepted, because every failure mode here is silent:
        // a URL without a scheme or without its trailing slash compiles, installs, and then fails
        // at runtime inside Retrofit with an error that does not name the cause. Checking it here
        // turns that into a configuration failure.
        val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: "http://10.0.2.2:3000/"
        // http is allowed here and nowhere else: the default is the emulator host loopback, which is the
        // one cleartext host the debug network security config exempts. The release guard below calls the
        // same helper with https alone, so that exemption cannot leave src/debug/.
        requireUsableUrl(
            fieldName = "apiBaseUrl",
            value = apiBaseUrl,
            allowedSchemes = setOf("http", "https"),
            reason = "It is the base every request is resolved against, so an address without a host " +
                "would fail at the first call rather than here.",
        )
        require(apiBaseUrl.endsWith("/")) {
            "apiBaseUrl must end with '/', because Retrofit resolves every endpoint relative to it. " +
                "Got '$apiBaseUrl'."
        }
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")

        // Where the update check asks which release is newest. GitHub rather than this project's own
        // backend, because the fact being asked for -- which release is newest -- lives there: a copy of
        // it in the API would be a cache of something that backend does not own, stale on its own
        // schedule and needing a deploy per release. The app already depends on GitHub for the download
        // itself, so this adds no new dependency to the product.
        //
        // It has a default while apiBaseUrl deliberately does not, and the difference is what each value
        // is about: a base URL for this project's deployment must never be guessed, while GitHub's address
        // is a fact about the outside world that is the same for every build. Overriding it is therefore
        // only ever a deliberate act -- a mirror or a proxy -- and the checks below are what make a
        // deliberate act a checked one.
        //
        // https is required rather than allowed alongside http, which is the one place this guard is
        // stricter than the apiBaseUrl one above: the development loopback is a host the debug network
        // security config exempts and this is not, and the request travels to a public host over a network
        // the phone does not control.
        val githubApiBaseUrl = (project.findProperty("githubApiBaseUrl") as String?) ?: "https://api.github.com/"
        requireUsableUrl(
            fieldName = "githubApiBaseUrl",
            value = githubApiBaseUrl,
            allowedSchemes = setOf("https"),
            reason = "The request travels to a public host over a network the phone does not control, " +
                "so it must be https.",
        )
        require(githubApiBaseUrl.endsWith("/")) {
            "githubApiBaseUrl must end with '/', because Retrofit resolves every endpoint relative to it. " +
                "Got '$githubApiBaseUrl'."
        }
        buildConfigField("String", "GITHUB_API_BASE_URL", "\"$githubApiBaseUrl\"")

        // The app's own copy of the version, from the same single computation that filled versionCode and
        // versionName above, which is what makes what the operator reads on the Perfil screen the value
        // that was packaged rather than a second, drifting one. The field names are this build's
        // (APP_VERSION_*) rather than AGP's generated VERSION_NAME/VERSION_CODE so the screen reads a name
        // this project owns, and so nothing here collides with what AGP writes into the same class.
        buildConfigField("String", "APP_VERSION_NAME", "\"$effectiveVersionName\"")
        buildConfigField("int", "APP_VERSION_CODE", effectiveVersionCode.toString())

        // Where the APK is published, for the action on the Perfil screen that opens it. A build
        // declaration rather than a string resource because it is a deployment fact and not UI copy: the
        // screen reads the address the build was given, exactly as it reads its own version, so hosting the
        // APK somewhere else is one property here instead of an edit to a screen. No shape check at this
        // point, unlike the base URL above: this value has a default, so the only way to get it wrong is an
        // explicit override somebody typed, and that is checked once by the release guard below -- which is
        // the run that checks every other declaration a release makes, and the one place a sentence about a
        // malformed release value belongs.
        buildConfigField("String", "RELEASES_URL", "\"$declaredReleasesUrl\"")
    }

    // Signing is configured only when a keystore was fully resolved. No keystore is not an error by
    // itself: AGP then produces app-release-unsigned.apk, which is exactly what scripts/ci-android.sh
    // builds and what proves the release path works without a key. -PrequireSigned=true is what turns
    // the absence into a failure, and the release workflow is the only caller that passes it.
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The JVM lane renders Compose, and a rendered screen resolves its own resources: the strings, the
    // drawable and the theme below are read through the app's R class, not stubbed. This flag is what
    // puts the merged resources and the manifest on the unit-test classpath; without it the first
    // stringResource lookup fails and the lane can only test composables that use none.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // The contract artifact is the single source of truth for the paths this client speaks, and the
    // test that checks the interfaces against it reads it from the test classpath. Pointing the test
    // resources at the package avoids a copy step and any chance of the copy going stale.
    sourceSets["test"].resources.srcDir(rootProject.file("../contracts"))

    // The contract types live in packages/android/contract, generated by
    // scripts/generate-android-contract.sh and committed. Wired in as a source directory rather than
    // as a Gradle module so the generated tree stays out of the application's own layout, and so the
    // module root is never the generator's output directory -- it writes a gradle/wrapper/ of its own
    // and would overwrite this module's.
    //
    // Resolved through rootProject because a bare relative path here is relative to this module, i.e.
    // packages/android/app/, which made it point at a directory that does not exist. Gradle ignores a
    // source directory that is not there, so the generated types were silently NOT compiled and this
    // lane stayed green until something finally referenced one of them.
    sourceSets["main"].kotlin.srcDir(rootProject.file("contract/src/main/kotlin"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// A release build must carry an https base URL, a version, and -- when it is asked for -- a signature.
// Shipping against the emulator loopback is not a configuration anyone chooses on purpose, so it fails
// here rather than on a user's device, where the release manifest grants no cleartext exception and the
// request could not succeed anyway. The version has no default for the same class of reason: an APK whose
// version the build invented cannot be told apart from an older one when an operator asks whether an
// update landed.
val declaredApiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: ""

fun assertReleaseBuildDeclarations() {
    // Local copies, because a smart cast does not survive on a script-level val and the checks below need
    // to narrow these to non-null before use.
    val versionName = declaredVersionName
    val versionCode = declaredVersionCode
    val versionCodeText = declaredVersionCodeText

    // Order is load-bearing in one direction. The base-URL check runs first, so a release build that was
    // given neither property fails with the base-URL sentence; scripts/ci-android.sh asserts that exact
    // sentence, because that guard is the one whose removal a green lane would hide.
    require(declaredApiBaseUrl.isNotEmpty()) {
        "A release build requires -PapiBaseUrl=https://<host>/. There is no default, so that a " +
            "release can never silently point at the development loopback."
    }
    requireUsableUrl(
        fieldName = "apiBaseUrl",
        value = declaredApiBaseUrl,
        allowedSchemes = setOf("https"),
        reason = "A release build hands this address to every installed app, and the release manifest " +
            "grants no cleartext exception.",
        sentenceLead = "A release build requires its apiBaseUrl to be",
    )

    // Where the app is published, checked for shape only. It has a default, so an absent property is not a
    // version the build invented -- but a blank, malformed or non-https one is a value somebody typed, and it
    // becomes the address on a button the crew is told to tap. https for the same reason the base URL above
    // needs it: a release hands this link to other people's browsers, and a download link is not something to
    // send over cleartext.
    //
    // Two checks, because they are two mistakes. A blank override is someone dropping the value. Everything
    // else -- not an absolute https URL, no host, or a character that cannot appear inside the generated Java
    // string literal -- is the shared helper the two base URLs above already call, and it is used here for the
    // same reason it was written: this is the one value that reaches somebody else's browser, and a
    // startsWith("https://") test cannot tell "https://" (no host, opens nothing) from a real link.
    require(declaredReleasesUrl.isNotBlank()) {
        "releasesUrl must not be blank. Omit it to use the default '$defaultReleasesUrl', or give an " +
            "absolute https URL."
    }
    requireUsableUrl(
        fieldName = "releasesUrl",
        value = declaredReleasesUrl,
        allowedSchemes = setOf("https"),
        reason = "The address is handed to the operator's browser, so a download link the network can " +
            "rewrite on the way is the one failure this build can prevent.",
    )

    // The version, which the tag workflow derives from the tag. Both properties are required because half a
    // version is worse than none: a versionCode that disagrees with the tag makes Android treat the install
    // as an update when it is not, or refuse one that is.
    require(versionName != null) {
        "A release build requires -PversionName=<x.y.z> and -PversionCode=<n>. There is no default, so " +
            "that a release can never be published carrying a version the build invented instead of the " +
            "one its tag declares."
    }
    require(versionName.matches(Regex("[0-9]+(\\.[0-9]+)*"))) {
        "A release build requires a versionName of digits separated by dots, for example 1.2.3, but got " +
            "'$versionName'. The git tag carries the leading 'v'; the version does not."
    }
    require(versionCode != null && versionCode > 0) {
        "A release build requires a positive -PversionCode, but got '${versionCodeText ?: "nothing"}'. " +
            "versionCode is the number Android compares to decide whether an install is an update."
    }

    // The signature, only when the caller asked for it. A missing keystore is not an error by itself -- the
    // CI lane builds an unsigned release on purpose -- but a release build that was asked to be signed and
    // produced an unsigned APK instead is the one failure nobody would notice until an operator tries to
    // install the update.
    if (!releaseSigningConfigured) {
        require(!requireSigned) {
            "A release build with -PrequireSigned=true requires a keystore, and none could be resolved. " +
                "Provide trindadeKeystorePath, trindadeKeystorePassword and trindadeKeyAlias as -P " +
                "properties, as TRINDADE_KEYSTORE_PATH, TRINDADE_KEYSTORE_PASSWORD and " +
                "TRINDADE_KEY_ALIAS in the environment, or in keystore.properties. A published APK that " +
                "cannot be verified as coming from this project is worse than no APK."
        }
        require(!releaseSigningStarted) {
            "A release build found part of a signing configuration, so it would produce an unsigned APK " +
                "that looks exactly like a signed one until someone checks it. Missing: " +
                "${missingSigningSettings.joinToString(", ")}."
        }
    }
}

// The guards live on their own task, and that placement is the whole point in two directions.
//
// First, every task that produces a release artifact must generate BuildConfig first, so wiring this task
// as a dependency of generate*Release*BuildConfig covers all of them: assembleRelease, bundleRelease,
// packageReleaseBundle, packageRelease, packageReleaseUniversalApk, and any flavoured variant assembled
// under a name nobody predicted. An earlier version of the base-URL guard hung off assembleRelease and
// bundleRelease by name and left those other producers unguarded.
//
// Second, and this is why it is a task rather than a doFirst: Gradle skips a task's actions, doFirst
// included, when it considers the task up to date. As a doFirst the guard therefore did not run on a
// second release build at all -- with -PrequireSigned=true and no keystore it printed nothing, and
// packageRelease, whose signing configuration had changed, quietly produced an unsigned APK. A task with
// no declared outputs is never up to date, so this one is evaluated on every release build, including the
// ones Gradle otherwise skips.
//
// The remaining assumption is worth stating: this depends on BuildConfig being generated, so turning off
// buildFeatures.buildConfig would silently remove it.
val verifyReleaseBuildDeclarations = tasks.register("verifyReleaseBuildDeclarations") {
    group = "verification"
    description = "Checks the base URL, the version, the releases URL and the signature a release build declares."
    doLast { assertReleaseBuildDeclarations() }
}

tasks.matching {
    it.name.startsWith("generate") && it.name.endsWith("BuildConfig") && it.name.contains("Release")
}.configureEach {
    dependsOn(verifyReleaseBuildDeclarations)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    // The Compose UI lane. The three arrive together and none is optional: ui-test-junit4 renders a
    // composable and asserts through the semantics tree; Robolectric runs that on the JVM, which is the
    // only place this project can run a rendered screen (the emulator lives on a box this lane cannot
    // reach); AndroidJUnit4 is the runner that chooses between the two.
    //
    // The BOM is named again here on purpose. Inheriting the version from the main classpath's platform
    // would work today and would leave this block unreadable on its own -- a dependency that resolves by
    // accident is one that breaks by accident.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)

    // The activity createComposeRule() launches has to be in the merged manifest of the variant under
    // test, and it must never reach a release build. This is the artifact that declares it, and
    // debugImplementation is what keeps it out of the APK the crew installs.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
