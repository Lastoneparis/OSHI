package com.oshi.messenger

import org.junit.Assume.assumeTrue
import java.io.File
import java.net.ServerSocket
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Local-server harness for the JVM tests that talk to the node servers of `ServerPatches/`.
 *
 * OPT-IN: runs only when `app/build/oshi-live-harness` exists (create it, run the tests, delete it)
 * and `node` + the server sources are present. Never contacts anything but 127.0.0.1.
 */
object LiveHarness {
    val repoRoot: File? = listOf(File("../.."), File(".."), File("."))
        .map { it.canonicalFile }.firstOrNull { File(it, "ServerPatches").isDirectory }

    private val node: String? = listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")
        .firstOrNull { File(it).canExecute() }

    fun assumeEnabled() {
        assumeTrue("live harness not requested (touch app/build/oshi-live-harness)", File("build/oshi-live-harness").exists())
        assumeTrue("node not found", node != null)
        assumeTrue("ServerPatches not found", repoRoot != null)
    }

    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    class Server(val port: Int, private val proc: Process, private val log: StringBuffer) {
        fun stop() { proc.destroyForcibly(); proc.waitFor() }
        fun log(): String = log.toString()
    }

    fun start(script: File, env: Map<String, String>, healthPath: String, port: Int): Server {
        val pb = ProcessBuilder(node!!, script.absolutePath).redirectErrorStream(true)
        pb.environment().putAll(env)
        pb.directory(script.parentFile)
        val p = pb.start()
        val log = StringBuffer()
        Thread { p.inputStream.bufferedReader().forEachLine { log.append(it).append('\n') } }.apply { isDaemon = true }.start()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) error("server exited:\n$log")
            val ok = runCatching { get(port, healthPath).statusCode() in 200..499 }.getOrDefault(false)
            if (ok) return Server(port, p, log)
            Thread.sleep(100)
        }
        p.destroyForcibly(); error("server did not start:\n$log")
    }

    /** Minimal response view (same accessors the tests used on java.net.http's HttpResponse). */
    class Resp(private val code: Int, private val text: String) {
        fun statusCode(): Int = code
        fun body(): String = text
    }

    // OkHttp, not java.net.http: the latter is not on the Android unit-test classpath.
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun exec(b: Request.Builder, headers: Map<String, String>): Resp {
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r -> return Resp(r.code, r.body?.string() ?: "") }
    }

    fun get(port: Int, path: String, headers: Map<String, String> = emptyMap()): Resp =
        exec(Request.Builder().url("http://127.0.0.1:$port$path").get(), headers)

    fun post(port: Int, path: String, body: ByteArray, headers: Map<String, String> = emptyMap()): Resp =
        exec(
            Request.Builder().url("http://127.0.0.1:$port$path")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json"),
            headers,
        )
}
