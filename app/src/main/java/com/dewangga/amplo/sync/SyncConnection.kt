package com.dewangga.amplo.sync

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import org.json.JSONArray
import org.json.JSONObject

/** A computer that runs amplo with sync on. */
data class Computer(val name: String, val host: String, val port: Int = SyncConnection.PORT) {
    /** "192.168.1.20:47821", as amplo saves it. */
    val address: String get() = "$host:$port"

    companion object {
        /** Reads "192.168.1.20", "192.168.1.20:47821" or a saved [address]. Null when it is not one. */
        fun parse(text: String, name: String = text.trim()): Computer? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
            val host = trimmed.substringBeforeLast(':', trimmed)
            val port = if (':' in trimmed) trimmed.substringAfterLast(':').toIntOrNull() ?: return null else SyncConnection.PORT
            if (host.isEmpty() || port !in 1..65535) return null
            return Computer(name, host, port)
        }
    }
}

/** The computer refused a request, with a message for the user. */
class SyncRefused(message: String) : Exception(message)

/**
 * Talks to amplo on a computer. Each request is one line of JSON, and some have file bytes after
 * it. Each answer is one line of JSON, with file bytes after it when it says "size".
 */
class SyncConnection(computer: Computer, connectTimeoutMs: Int = 5000) : Closeable {
    private val socket = Socket()
    private val input: InputStream
    private val output: OutputStream

    /** The key from pairing. Requests carry it once it is set. */
    var key: String? = null

    init {
        try {
            socket.connect(InetSocketAddress(computer.host, computer.port), connectTimeoutMs)
            socket.soTimeout = READ_TIMEOUT_MS
            input = BufferedInputStream(socket.getInputStream(), BUFFER)
            output = BufferedOutputStream(socket.getOutputStream(), BUFFER)
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    /** The answer to [request]. Throws [SyncRefused] when the computer refuses it. */
    fun ask(request: JSONObject): JSONObject {
        write(request, null, 0) {}
        return answer()
    }

    /** Sends [size] bytes of [body] after the request, as for "put". [progress] gets the bytes sent. */
    fun send(request: JSONObject, body: InputStream, size: Long, progress: (Long) -> Unit): JSONObject {
        write(request, body, size, progress)
        return answer()
    }

    /** Asks for a file and writes it to [to]. [progress] gets the bytes received. */
    fun receive(request: JSONObject, to: OutputStream, progress: (Long) -> Unit): Long {
        write(request, null, 0) {}
        val answer = answer()
        val size = answer.optLong("size", 0)
        copy(input, to, size, progress)
        return size
    }

    private fun write(request: JSONObject, body: InputStream?, size: Long, progress: (Long) -> Unit) {
        key?.let { request.put("key", it) }
        if (body != null) request.put("size", size)
        output.write(request.toString().toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
        if (body != null) copy(body, output, size, progress)
        output.flush()
    }

    private fun answer(): JSONObject {
        val answer = JSONObject(readLine())
        if (!answer.optBoolean("ok", false)) throw SyncRefused(answer.optString("error", "The computer refused the request."))
        return answer
    }

    private fun readLine(): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw EOFException("The computer closed the connection")
            if (byte == '\n'.code) return line.toString(Charsets.UTF_8.name())
            line.write(byte)
            if (line.size() > MAX_LINE) throw IOException("The answer of the computer is too long")
        }
    }

    override fun close() = socket.close()

    companion object {
        const val PORT = 47821
        /** The version of the requests. The computer tells its own in "hello". */
        const val VERSION = 1
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER = 256 * 1024
        private const val MAX_LINE = 64 * 1024 * 1024
        private val QUESTION = "amplo-sync?".toByteArray()

        /** Copies exactly [size] bytes. A source that ends early is an error. */
        internal fun copy(from: InputStream, to: OutputStream, size: Long, progress: (Long) -> Unit) {
            val buffer = ByteArray(BUFFER)
            var left = size
            var done = 0L
            while (left > 0) {
                val read = from.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) throw EOFException("The file ended early")
                to.write(buffer, 0, read)
                left -= read
                done += read
                progress(done)
            }
        }

        /** The computers on the network that run amplo with sync on. Waits up to [waitMs]. */
        fun discover(waitMs: Long = 1500): List<Computer> {
            val found = LinkedHashMap<String, Computer>()
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 250
                val targets = broadcastAddresses()
                val deadline = System.currentTimeMillis() + waitMs
                var nextSend = 0L
                val buffer = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    // A broadcast can get lost on Wi-Fi, so it goes out a few times.
                    if (System.currentTimeMillis() >= nextSend) {
                        for (target in targets) {
                            try {
                                socket.send(DatagramPacket(QUESTION, QUESTION.size, target, PORT))
                            } catch (_: IOException) {
                            }
                        }
                        nextSend = System.currentTimeMillis() + 500
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val answer = try {
                        JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    } catch (_: Exception) {
                        continue
                    }
                    if (answer.optString("app") != "amplo") continue
                    val host = packet.address.hostAddress ?: continue
                    val computer = Computer(answer.optString("name", host), host, answer.optInt("port", PORT))
                    found[computer.address] = computer
                }
            }
            return found.values.toList()
        }

        private fun broadcastAddresses(): List<InetAddress> {
            val addresses = LinkedHashSet<InetAddress>()
            try {
                for (network in NetworkInterface.getNetworkInterfaces()) {
                    if (!network.isUp || network.isLoopback) continue
                    network.interfaceAddresses.mapNotNullTo(addresses) { it.broadcast }
                }
            } catch (_: Exception) {
            }
            addresses += InetAddress.getByName("255.255.255.255")
            return addresses.toList()
        }

        /** The songs and playlists in an answer to "library". */
        fun parseLibrary(answer: JSONObject): Pair<List<SyncSong>, List<SyncPlaylist>> {
            val songs = answer.optJSONArray("songs") ?: JSONArray()
            val playlists = answer.optJSONArray("playlists") ?: JSONArray()
            return List(songs.length()) { index ->
                val song = songs.getJSONObject(index)
                SyncSong(song.getString("path"), song.optLong("size"), song.optBoolean("lyrics"))
            } to List(playlists.length()) { index ->
                val playlist = playlists.getJSONObject(index)
                val paths = playlist.optJSONArray("songs") ?: JSONArray()
                SyncPlaylist(playlist.getString("name"), List(paths.length()) { paths.getString(it) })
            }
        }
    }
}
