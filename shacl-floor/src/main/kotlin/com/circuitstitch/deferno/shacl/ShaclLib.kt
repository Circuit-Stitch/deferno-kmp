package com.circuitstitch.deferno.shacl

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * The C ABI of the shacl-aio crate (libshacl_aio.so), bound over JNA. The whole surface is the
 * floor extractor plus a matching free — JSON crosses the boundary (see [ShaclFloor]).
 */
internal interface ShaclLib : Library {
    /** Returns a heap C string of JSON `DraftTask[]`, or null on bad input/panic. Caller frees it. */
    fun shacl_extract_json(transcript: String, nowIso: String): Pointer?

    /** Frees a pointer returned by [shacl_extract_json]. */
    fun shacl_string_free(ptr: Pointer)

    companion object {
        val INSTANCE: ShaclLib = Native.load("shacl_aio", ShaclLib::class.java)
    }
}
