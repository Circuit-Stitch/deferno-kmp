import Foundation

// macOS's system libsqlite3 is built with SQLITE_OMIT_LOAD_EXTENSION, so two symbols the statically
// linked SQLiter driver (touchlab) references are absent from `-lsqlite3`. The Phase-1 demo renders
// the shell over in-memory fakes and never opens a database, so these are never *called* — they only
// need to exist so the linker is satisfied (ADR-0029). Phase 1b (the real encrypted per-Account DB)
// links a full SQLite that provides them (e.g. SQLCipher, as app/iosApp does).
//
// ponytail: link-time stubs, not a vendored SQLite — the demo opens no DB. Swap for a real SQLite
// build when sign-in + the encrypted DB land in Phase 1b.

@_cdecl("sqlite3_enable_load_extension")
func deferno_stub_sqlite3_enable_load_extension(_ db: OpaquePointer?, _ onoff: Int32) -> Int32 {
    return 1 // SQLITE_ERROR — never invoked in the demo (no DB is opened)
}

@_cdecl("sqlite3_load_extension")
func deferno_stub_sqlite3_load_extension(
    _ db: OpaquePointer?,
    _ file: UnsafePointer<CChar>?,
    _ proc: UnsafePointer<CChar>?,
    _ errmsg: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    return 1 // SQLITE_ERROR — never invoked in the demo (no DB is opened)
}
