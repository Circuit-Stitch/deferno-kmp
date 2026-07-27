import Deferno
import SwiftUI

// Retroactive `Identifiable` conformances so SwiftUI `ForEach`/`List` can diff the shared Kotlin types.
// Each id is a stable String — directly for enums (`.name`), or via the SKIE-free bridge for the
// value-class-keyed types whose `.value` the Obj-C header erases (Account, SpeechEngineOption).
//
// `@retroactive` is the acknowledgement Swift wants for conforming an imported type to an imported
// protocol: we own neither side, so if `Deferno` ever declares `Identifiable` itself these lines must
// go. Nothing else in the app may re-declare them — one conformance per type, right here.

extension Destination: @retroactive Identifiable {
    public var id: String { ShellBridgeKt.destinationName(destination: self) }
}

extension Account: @retroactive Identifiable {
    public var id: String { ShellBridgeKt.accountKey(account: self) }
}

extension SettingsCategory: @retroactive Identifiable {
    public var id: String { name }
}

extension SpeechEngineOption: @retroactive Identifiable {
    public var id: String { ShellBridgeKt.speechOptionKey(option: self) }
}
