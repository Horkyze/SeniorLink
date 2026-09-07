package family.seniorlink.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class AppUpdate(val version: String, val downloadUrl: String)

sealed interface UpdateCheckResult {
    data class Available(val update: AppUpdate) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

class ReleaseChecker(
    private val openConnection: () -> HttpURLConnection = {
        URL(RELEASES_URL).openConnection() as HttpURLConnection
    },
) {
    suspend fun check(installedVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            require(ReleaseVersion.parse(installedVersion) != null)
            connection = openConnection().apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "SeniorLink/$installedVersion")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext UpdateCheckResult.Failed
            val body = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count == -1) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            selectUpdate(body, installedVersion)?.let { UpdateCheckResult.Available(it) } ?: UpdateCheckResult.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep failure distinct from a successful check with no newer release.
            UpdateCheckResult.Failed
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        // The family pilot is published as prereleases, which /releases/latest excludes.
        internal const val RELEASES_URL = "https://api.github.com/repos/Horkyze/SeniorLink/releases?per_page=100"
        internal const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }

        internal fun selectUpdate(body: String, installedVersion: String): AppUpdate? {
            val installed = ReleaseVersion.parse(installedVersion) ?: return null
            val entries = json.parseToJsonElement(body).jsonArray
            val releases = entries.mapNotNull { element ->
                runCatching { json.decodeFromJsonElement<Release>(element) }.getOrNull()
            }
            require(entries.isEmpty() || releases.isNotEmpty()) { "Invalid release response" }
            return releases.mapNotNull { release ->
                if (release.draft) return@mapNotNull null
                val version = ReleaseVersion.parse(release.tag_name) ?: return@mapNotNull null
                if (version <= installed) return@mapNotNull null
                // Published pilot APKs are universal; never offer a source archive or checksum.
                val asset = release.assets.firstOrNull {
                    it.state == "uploaded" && it.name.endsWith(".apk", ignoreCase = true) &&
                        validDownloadUrl(it.browser_download_url)
                } ?: return@mapNotNull null
                version to AppUpdate(release.tag_name.removePrefix("v"), asset.browser_download_url)
            }.maxByOrNull { it.first }?.second
        }

        private fun validDownloadUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
                uri.port == -1 && uri.path.startsWith("/Horkyze/SeniorLink/releases/download/")
        }.getOrDefault(false)
    }
}

@Serializable
private data class Release(val tag_name: String, val draft: Boolean, val assets: List<Asset>)

@Serializable
private data class Asset(val name: String, val browser_download_url: String, val state: String)

/** Semantic version precedence; build metadata does not make an installed version older. */
internal data class ReleaseVersion(val numbers: List<String>, val prerelease: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for ((left, right) in numbers.zip(other.numbers)) {
            compareNumbers(left, right).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty()) return if (other.prerelease.isEmpty()) 0 else 1
        if (other.prerelease.isEmpty()) return -1
        for ((left, right) in prerelease.zip(other.prerelease)) {
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            val order = when {
                leftNumeric && rightNumeric -> compareNumbers(left, right)
                leftNumeric != rightNumeric -> if (leftNumeric) -1 else 1
                else -> left.compareTo(right)
            }
            if (order != 0) return order
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex(
            "v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?",
        )

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (prerelease.any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
            return ReleaseVersion(match.groupValues.subList(1, 4), prerelease)
        }

        private fun compareNumbers(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}
