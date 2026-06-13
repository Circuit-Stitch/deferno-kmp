import Deferno
import SwiftUI

// Retroactive `Identifiable` conformances so SwiftUI `ForEach`/`List` can diff the shared Kotlin types.
// Each id is a stable String — directly for enums (`.name`), or via the SKIE-free bridge for the
// value-class-keyed types whose `.value` the Obj-C header erases (Account, SpeechEngineOption).

extension Destination: Identifiable {
    public var id: String { ShellBridgeKt.destinationName(destination: self) }
}

extension Account: Identifiable {
    public var id: String { ShellBridgeKt.accountKey(account: self) }
}

extension SettingsCategory: Identifiable {
    public var id: String { name }
}

extension SpeechEngineOption: Identifiable {
    public var id: String { ShellBridgeKt.speechOptionKey(option: self) }
}
