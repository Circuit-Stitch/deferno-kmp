import Carbon.HIToolbox
import Foundation

/// A live hotkey binding; `unregister()` releases the OS registration (idempotent).
public final class HotkeyRegistration {
    private let lock = NSLock()
    private var ref: EventHotKeyRef?
    private let refId: UInt32
    private unowned let center: HotkeyCenter

    fileprivate init(center: HotkeyCenter, refId: UInt32, ref: EventHotKeyRef) {
        self.center = center
        self.refId = refId
        self.ref = ref
    }

    public func unregister() {
        lock.lock()
        let ref = self.ref
        self.ref = nil
        lock.unlock()
        guard let ref else { return }
        center.unregister(refId: refId, ref: ref)
    }
}

/// The **process-wide** Carbon global-hotkey registry (#125, ADR-0024): `RegisterEventHotKey` bindings
/// dispatched through one lazily-installed Carbon event handler. Carbon is the deliberate engine choice
/// — system-wide, no Accessibility/Input-Monitoring TCC (unlike `NSEvent` global monitors or event
/// taps), and supported on every macOS this helper targets.
///
/// All Carbon calls run on the **main thread** (`DispatchQueue.main.sync` from the connection's read
/// thread): the event handler target is the main run loop's dispatcher, which `deferno-sidecar` keeps
/// running (`NSApplication.run` in real mode). Per-connection bookkeeping (which ids a client owns)
/// stays in the provider; this registry only maps its internal ref-ids to fire closures.
public final class HotkeyCenter {

    public static let shared = HotkeyCenter()

    /// The `EventHotKeyID.signature` for this process's bindings ("Dfrn").
    private static let signature: OSType = 0x4466_726E

    private let lock = NSLock()
    private var nextRefId: UInt32 = 1
    private var handlers: [UInt32: () -> Void] = [:]
    private var handlerInstalled = false

    /// Register `key`+`modifiers`; `onFire` runs on the main thread per press. Returns nil when the OS
    /// refuses the registration (→ `unavailable`, `hotkey-unavailable`). The key was already validated
    /// against `HotkeyKeyTable` by the request parse.
    public func register(
        key: String,
        modifiers: Set<HotkeyModifier>,
        onFire: @escaping () -> Void
    ) -> HotkeyRegistration? {
        guard let keyCode = HotkeyKeyTable.keyCodes[key] else { return nil }
        var registration: HotkeyRegistration?
        runOnMain {
            self.installHandlerIfNeeded()
            let refId = self.allocateRefId(onFire)
            var ref: EventHotKeyRef?
            let hotKeyId = EventHotKeyID(signature: Self.signature, id: refId)
            let status = RegisterEventHotKey(
                keyCode,
                HotkeyKeyTable.carbonModifiers(modifiers),
                hotKeyId,
                GetEventDispatcherTarget(),
                0,
                &ref
            )
            guard status == noErr, let ref else {
                self.removeHandler(refId)
                return
            }
            registration = HotkeyRegistration(center: self, refId: refId, ref: ref)
        }
        return registration
    }

    fileprivate func unregister(refId: UInt32, ref: EventHotKeyRef) {
        runOnMain {
            UnregisterEventHotKey(ref)
            self.removeHandler(refId)
        }
    }

    // MARK: internals

    private func allocateRefId(_ onFire: @escaping () -> Void) -> UInt32 {
        lock.lock()
        defer { lock.unlock() }
        let refId = nextRefId
        nextRefId += 1
        handlers[refId] = onFire
        return refId
    }

    private func removeHandler(_ refId: UInt32) {
        lock.lock()
        handlers[refId] = nil
        lock.unlock()
    }

    private func fire(_ refId: UInt32) {
        lock.lock()
        let handler = handlers[refId]
        lock.unlock()
        handler?()
    }

    /// Install the one Carbon hotkey-pressed handler for this process (main thread).
    private func installHandlerIfNeeded() {
        guard !handlerInstalled else { return }
        handlerInstalled = true
        var eventType = EventTypeSpec(
            eventClass: OSType(kEventClassKeyboard),
            eventKind: UInt32(kEventHotKeyPressed)
        )
        InstallEventHandler(
            GetEventDispatcherTarget(),
            { _, event, userData in
                guard let event, let userData else { return noErr }
                var hotKeyId = EventHotKeyID()
                let status = GetEventParameter(
                    event,
                    EventParamName(kEventParamDirectObject),
                    EventParamType(typeEventHotKeyID),
                    nil,
                    MemoryLayout<EventHotKeyID>.size,
                    nil,
                    &hotKeyId
                )
                if status == noErr, hotKeyId.signature == HotkeyCenter.signature {
                    Unmanaged<HotkeyCenter>.fromOpaque(userData).takeUnretainedValue().fire(hotKeyId.id)
                }
                return noErr
            },
            1,
            &eventType,
            Unmanaged.passUnretained(self).toOpaque(),
            nil
        )
    }

    private func runOnMain(_ body: @escaping () -> Void) {
        if Thread.isMainThread {
            body()
        } else {
            DispatchQueue.main.sync(execute: body)
        }
    }
}
