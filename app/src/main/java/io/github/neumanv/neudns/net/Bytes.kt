package io.github.neumanv.neudns.net

/** Lectura y escritura de enteros big-endian (orden de red) sobre arrays de bytes. */

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.u32(i: Int): Long = (u16(i).toLong() shl 16) or u16(i + 2).toLong()

internal fun ByteArray.put16(i: Int, v: Int) {
    this[i] = (v ushr 8).toByte()
    this[i + 1] = v.toByte()
}

internal fun ByteArray.put32(i: Int, v: Long) {
    put16(i, (v ushr 16).toInt())
    put16(i + 2, v.toInt())
}
