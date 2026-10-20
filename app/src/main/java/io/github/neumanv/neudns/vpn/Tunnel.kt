package io.github.neumanv.neudns.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.github.neumanv.neudns.dns.DnsMessage
import io.github.neumanv.neudns.dns.DohResolver
import io.github.neumanv.neudns.net.IpPacket
import io.github.neumanv.neudns.net.TcpDnsHandler
import io.github.neumanv.neudns.net.runGuarded
import io.github.neumanv.neudns.net.trySubmit
import io.github.neumanv.neudns.net.put16
import io.github.neumanv.neudns.net.u16
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Lee los paquetes del túnel y contesta las consultas DNS.
 *
 * El hilo lector duerme en poll() sin límite de tiempo: mientras las apps no pregunten nada,
 * no despierta la CPU. Para pararlo se escribe en una tubería que también vigila poll().
 */
internal class Tunnel(
    private val tun: ParcelFileDescriptor,
    private val resolver: DohResolver,
    private val executor: Executor,
) {
    private val output = FileOutputStream(tun.fileDescriptor)
    private val wakeRead: FileDescriptor
    private val wakeWrite: FileDescriptor
    private val tcp = TcpDnsHandler(resolver::resolve, executor, ::write, MTU - MAX_IP_HEADER - TCP_HEADER)
    private val reader = Thread(::readLoop, "neudns")

    init {
        val pipe = Os.pipe()
        wakeRead = pipe[0]
        wakeWrite = pipe[1]
    }

    fun start() = reader.start()

    fun stop() {
        try {
            Os.write(wakeWrite, byteArrayOf(1), 0, 1)
        } catch (_: Exception) {
        }
        try {
            reader.join(STOP_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        closeQuietly()
    }

    private fun readLoop() {
        val input = FileInputStream(tun.fileDescriptor)
        val buffer = ByteArray(MTU)
        val tunPoll = StructPollfd().apply {
            fd = tun.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        val wakePoll = StructPollfd().apply {
            fd = wakeRead
            events = OsConstants.POLLIN.toShort()
        }
        val fds = arrayOf(tunPoll, wakePoll)

        while (true) {
            try {
                Os.poll(fds, -1)
                if (wakePoll.revents.toInt() != 0) break
                val events = tunPoll.revents.toInt()
                if (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                if (events and OsConstants.POLLIN == 0) continue
                val length = input.read(buffer)
                if (length > 0) runGuarded { handle(buffer, length) }
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR) break
            } catch (e: IOException) {
                break
            }
        }
    }

    private fun handle(buffer: ByteArray, length: Int) {
        val packet = IpPacket.parse(buffer, length) ?: return
        when (packet.protocol) {
            // UDP que no sea DNS (por ejemplo QUIC hacia un DNS público) se descarta.
            IpPacket.UDP -> if (packet.destinationPort == DNS_PORT) handleUdp(packet)
            IpPacket.TCP -> tcp.handle(packet)
        }
    }

    private fun handleUdp(packet: IpPacket) {
        val segment = packet.segment
        val end = minOf(segment.u16(4), segment.size)
        if (packet.sourcePort == 0 || end - UDP_HEADER < DnsMessage.HEADER_SIZE) return
        val query = segment.copyOfRange(UDP_HEADER, end)

        // Si la cola está llena la consulta se descarta: la app la repetirá en un momento.
        executor.trySubmit {
            val response = resolver.resolve(query) ?: return@trySubmit
            val limit = minOf(
                DnsMessage.maxUdpResponseSize(query),
                MTU - IpPacket.headerSize(packet.version) - UDP_HEADER,
            )
            val payload = if (response.size <= limit) response else DnsMessage.truncated(response) ?: return@trySubmit
            write(packet.reply(IpPacket.UDP, udpSegment(DNS_PORT, packet.sourcePort, payload)))
        }
    }

    private fun udpSegment(sourcePort: Int, destinationPort: Int, payload: ByteArray) =
        ByteArray(UDP_HEADER + payload.size).apply {
            put16(0, sourcePort)
            put16(2, destinationPort)
            put16(4, size)
            System.arraycopy(payload, 0, this, UDP_HEADER, payload.size)
        }

    private fun write(packet: ByteArray) {
        synchronized(output) {
            try {
                output.write(packet)
            } catch (_: IOException) {
                // El túnel se ha cerrado mientras se resolvía la consulta.
            }
        }
    }

    private fun closeQuietly() {
        for (fd in listOf(wakeRead, wakeWrite)) {
            try {
                Os.close(fd)
            } catch (_: ErrnoException) {
            }
        }
        // Con el candado de escritura: ningún hilo puede estar escribiendo mientras se cierra.
        synchronized(output) {
            try {
                tun.close()
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        const val MTU = 4096
        private const val MAX_IP_HEADER = 40
        private const val TCP_HEADER = 20
        private const val UDP_HEADER = 8
        private const val DNS_PORT = 53
        private const val STOP_TIMEOUT_MS = 1000L
    }
}
