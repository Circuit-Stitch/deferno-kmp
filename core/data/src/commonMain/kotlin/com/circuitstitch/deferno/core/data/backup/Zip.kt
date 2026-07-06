package com.circuitstitch.deferno.core.data.backup

/**
 * A minimal, dependency-free zip writer (#313, ADR-0041). The Backup file is a zip, but no zip library
 * is on the KMP classpath and the catalog has none — so rather than add a dependency (or split an
 * expect/actual across `java.util.zip` + Foundation) for what is a few lines, this writes the format by
 * hand in commonMain so every target inherits it. Entries are **STORED** (uncompressed): the layout is a
 * fixed, well-understood one (local header → data → central directory → end-of-central-directory, all
 * little-endian).
 *
 * ponytail: STORED (not DEFLATE) — on-device attachments are brain-dump audio (#315), already compressed,
 * so DEFLATE would add a commonMain inflate/deflate implementation for ~0 size win. The whole zip is still
 * built in memory as one `ByteArray` (the share sheet / document takes it whole anyway); the accumulator is
 * a growable `ByteArray` [ByteBuf], **not** an `ArrayList<Byte>`, so a few MB of audio doesn't balloon to
 * ~16× via boxing. If a single backup ever outgrows a comfortable heap, switch to a streaming sink.
 */
internal fun zipStored(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteBuf()
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

/**
 * Reads a STORED-entry zip — the inverse of [zipStored] — into an entry name → bytes map (#314,
 * ADR-0041). It walks the **local file headers** from the start: [zipStored] writes the sizes in each
 * local header (general-purpose bit 3 = 0), so the data length is known up front and no
 * central-directory scan is needed; the walk stops at the first non-local-header signature (the central
 * directory). A truncated/garbage input simply yields no matching headers (and `copyOfRange` throws on a
 * lying length) — the caller treats a missing manifest or a thrown read as a malformed Backup file.
 *
 * ponytail: STORED only (method 0) — the one format [zipStored] emits. A DEFLATE entry (method 8, e.g. a
 * backup someone re-zipped with Archive Utility) throws here and surfaces as "malformed"; add an inflate
 * path when that case is real, alongside the DEFLATE writer the attachment slice will need.
 */
internal fun unzipStored(bytes: ByteArray): Map<String, ByteArray> {
    val entries = LinkedHashMap<String, ByteArray>()
    var p = 0
    while (p + 30 <= bytes.size && bytes.readU32(p) == LOCAL_FILE_HEADER) {
        val method = bytes.readU16(p + 8)
        val size = bytes.readU32(p + 18) // compressed size == uncompressed for STORED
        val nameLen = bytes.readU16(p + 26)
        val extraLen = bytes.readU16(p + 28)
        val nameStart = p + 30
        val dataStart = nameStart + nameLen + extraLen
        require(method == 0) { "unsupported zip compression method: $method" }
        entries[bytes.decodeToString(nameStart, nameStart + nameLen)] = bytes.copyOfRange(dataStart, dataStart + size)
        p = dataStart + size
    }
    return entries
}

private fun ByteArray.readU16(i: Int): Int = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.readU32(i: Int): Int = readU16(i) or (readU16(i + 2) shl 16)

private class CentralEntry(val nameBytes: ByteArray, val crc: Int, val size: Int, val offset: Int)

private const val LOCAL_FILE_HEADER = 0x04034b50
private const val CENTRAL_FILE_HEADER = 0x02014b50
private const val END_OF_CENTRAL_DIR = 0x06054b50
private const val VERSION = 20 // 2.0 — the floor that supports STORED entries
private const val DOS_DATE_1980 = 0x21 // (year 1980<<9)|(month 1<<5)|(day 1)

/**
 * A growable little-endian byte accumulator for [zipStored] — a `ByteArray` that doubles on overflow, so
 * embedding MB-sized attachment bytes (#315) stays ~1× rather than the ~16× an `ArrayList<Byte>` would cost
 * by boxing every byte. Only what the zip writer needs: [u16]/[u32] (little-endian) + raw [bytes].
 */
private class ByteBuf {
    private var buf = ByteArray(1024)
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size
        while (cap < size + extra) cap = cap shl 1
        buf = buf.copyOf(cap)
    }

    fun u16(v: Int) {
        ensure(2)
        buf[size++] = (v and 0xFF).toByte()
        buf[size++] = ((v ushr 8) and 0xFF).toByte()
    }

    fun u32(v: Int) {
        u16(v and 0xFFFF)
        u16((v ushr 16) and 0xFFFF)
    }

    fun bytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
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
