package ai.schism.split.core.update

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Public GitHub source repo, linked from Settings. */
const val GITHUB_REPO_URL = "https://github.com/dk-a-dev/schism"

private const val GITHUB_API_BASE = "https://api.github.com/repos/dk-a-dev/schism/releases"

/** Public, unauthenticated GitHub Releases API endpoint for the latest release. */
const val GITHUB_LATEST_RELEASE_API_URL = "$GITHUB_API_BASE/latest"

/** A GitHub release, distilled to what the update-check UI needs. [notes] is the release body ("what's new"). */
data class ReleaseInfo(
    val versionName: String,
    val tag: String,
    val apkUrl: String?,
    val releaseUrl: String,
    val notes: String,
)

/** Compares dotted numeric version strings component-by-component (e.g. "1.2.0" vs "1.1.9").
 *  Missing trailing components are treated as 0, so "1.1" and "1.1.0" compare equal. */
fun isNewer(latest: String, current: String): Boolean {
    val latestParts = latest.trim().removePrefix("v").split(".")
    val currentParts = current.trim().removePrefix("v").split(".")
    val size = maxOf(latestParts.size, currentParts.size)
    for (i in 0 until size) {
        val l = latestParts.getOrNull(i)?.toIntOrNull() ?: 0
        val c = currentParts.getOrNull(i)?.toIntOrNull() ?: 0
        if (l != c) return l > c
    }
    return false
}

/** Play's installer package names. `com.google.android.feedback` is the legacy one still reported
 *  on some devices. */
private val PLAY_INSTALLERS = setOf("com.android.vending", "com.google.android.feedback")

/**
 * Whether this install may offer its own updates, given the package that installed it.
 *
 * A Play-installed build must not: Play's Device and Network Abuse policy forbids an app
 * distributing app updates outside Play, and the GitHub release banner links straight at an APK.
 * Play delivers updates for those users. A sideloaded build (installer null, adb, a file manager,
 * another store) keeps the GitHub check, because nothing else will tell it a new version exists.
 */
fun selfUpdateAllowed(installerPackage: String?): Boolean = installerPackage !in PLAY_INSTALLERS

/** Checks GitHub's public Releases API for a newer build than the one installed, and the release
 *  notes for a given version. Never throws — any network/parse failure surfaces as a null result.
 *
 *  On a Play install every lookup short-circuits to null, so no request reaches GitHub at all —
 *  that keeps the policy guarantee and the privacy disclosure ("the app contacts GitHub") true for
 *  exactly the builds it applies to. */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val allowed: Boolean by lazy {
        selfUpdateAllowed(
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getInstallerPackageName(context.packageName)
                }
            }.getOrNull(),
        )
    }
    // A PLAIN client on purpose — NOT the app's shared OkHttp. That one carries a
    // BackendUrlInterceptor that rewrites every request's host to the Schism backend (so a GitHub
    // call would be sent to api.schism… and fail) plus an AuthInterceptor whose bearer token GitHub
    // rejects. This client talks to GitHub's public API directly with no app interceptors.
    private val client = OkHttpClient()

    /** The latest published release (for the "update available" check). */
    suspend fun latestRelease(): ReleaseInfo? =
        if (!allowed) null else fetch(GITHUB_LATEST_RELEASE_API_URL)

    /** The release for a specific tag (e.g. "v1.1.5") — used to show "what's new" for the running build. */
    suspend fun releaseForTag(tag: String): ReleaseInfo? {
        if (!allowed) return null
        val t = if (tag.startsWith("v")) tag else "v$tag"
        return fetch("$GITHUB_API_BASE/tags/$t")
    }

    private suspend fun fetch(url: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return@use null
                val releaseUrl = json.optString("html_url").takeIf { it.isNotBlank() } ?: return@use null
                val notes = json.optString("body").trim()

                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        if (asset.optString("name").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            break
                        }
                    }
                }

                ReleaseInfo(
                    versionName = tag.removePrefix("v"),
                    tag = tag,
                    apkUrl = apkUrl,
                    releaseUrl = releaseUrl,
                    notes = notes,
                )
            }
        }.getOrNull()
    }
}
