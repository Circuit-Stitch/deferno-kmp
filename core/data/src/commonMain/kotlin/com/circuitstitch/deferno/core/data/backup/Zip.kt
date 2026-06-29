package com.circuitstitch.deferno.core.data.backup

/**
 * A minimal, dependency-free zip writer (#313, ADR-0041). The Backup file is a zip, but no zip library
 * is on the KMP classpath and the catalog has none — so rather than add a dependency (or split an
 * expect/actual across `java.util.zip` + Foundation) for what is a few lines, this writes the format by
 * hand in commonMain so every target inherits it. Entries are **STORED** (uncompressed): `items.json`
 * is small text and a STORED single-entry zip is a fixed, well-understood layout (local header → data →
 * central directory → end-of-central-directory, all little-endian).
 *
 * ponytail: STORED + in-memory `ByteArray`, fine for the items-only export (KBs). When attachment
 * bytes join the zip (a later ADR-0041 slice), switch to DEFLATE + a streaming sink so megabytes don't
 * all sit in memory.
 */
internal fun zipStored(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ArrayList<Byte>()
    val central = ArrayList<CentralEntry>(entries.size)

    for ((name, data) in entries) {
        val nameBytes = name.encodeToByteArray()
        val crc = crc32(data)
        val offset = out.size
        out.u32(LOCAL_FILE_HEADER)
        out.u16(VERSION) // version needed to extract
        out.u16(0) // general-purpose bit flag
        out.u16(0) // compression method: STORED
        out.u16(0) // last-mod time
        out.u16(DOS_DATE_1980) // last-mod date (1980-01-01; 0 is an invalid date some tools reject)
        out.u32(crc)
        out.u32(data.size) // compressed size (== uncompressed for STORED)
        out.u32(data.size) // uncompressed size
        out.u16(nameBytes.size)
        out.u16(0) // extra field length
        out.bytes(nameBytes)
        out.bytes(data)
        central += CentralEntry(nameBytes, crc, data.size, offset)
    }

    val centralStart = out.size
    for (e in central) {
        out.u32(CENTRAL_FILE_HEADER)
        out.u16(VERSION) // version made by
        out.u16(VERSION) // version needed to extract
        out.u16(0) // flags
        out.u16(0) // compression: STORED
        out.u16(0) // mod time
        out.u16(DOS_DATE_1980) // mod date
        out.u32(e.crc)
        out.u32(e.size) // compressed
        out.u32(e.size) // uncompressed
        out.u16(e.nameBytes.size)
        out.u16(0) // extra length
        out.u16(0) // comment length
        out.u16(0) // disk number start
        out.u16(0) // internal attributes
        out.u32(0) // external attributes
        out.u32(e.offset) // local header offset
        out.bytes(e.nameBytes)
    }
    val centralSize = out.size - centralStart

    out.u32(END_OF_CENTRAL_DIR)
    out.u16(0) // this disk number
    out.u16(0) // disk with central directory
    out.u16(central.size) // entries on this disk
    out.u16(central.size) // total entries
    out.u32(centralSize)
    out.u32(centralStart)
    out.u16(0) // .zip comment length

    return out.toByteArray()
}

private class CentralEntry(val nameBytes: ByteArray, val crc: Int, val size: Int, val offset: Int)

private const val LOCAL_FILE_HEADER = 0x04034b50
private const val CENTRAL_FILE_HEADER = 0x02014b50
private const val END_OF_CENTRAL_DIR = 0x06054b50
private const val VERSION = 20 // 2.0 — the floor that supports STORED entries
private const val DOS_DATE_1980 = 0x21 // (year 1980<<9)|(month 1<<5)|(day 1)

private fun MutableList<Byte>.u16(v: Int) {
    add((v and 0xFF).toByte())
    add(((v ushr 8) and 0xFF).toByte())
}

private fun MutableList<Byte>.u32(v: Int) {
    u16(v and 0xFFFF)
    u16((v ushr 16) and 0xFFFF)
}

private fun MutableList<Byte>.bytes(b: ByteArray) {
    for (x in b) add(x)
}

/**
 * CRC-32 (IEEE 802.3, polynomial 0xEDB88320) — the checksum the zip headers carry and an unzipper
 * verifies. Bitwise (no 256-entry table): the manifest is small, so the table's speed win is moot and
 * the loop is fewer lines.
 */
private fun crc32(data: ByteArray): Int {
    var crc = -1 // 0xFFFFFFFF
    for (b in data) {
        crc = crc xor (b.toInt() and 0xFF)
        repeat(8) { crc = (crc ushr 1) xor (0xEDB88320.toInt() and -(crc and 1)) }
    }
    return crc.inv()
}
