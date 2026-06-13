package com.circuitstitch.deferno.core.scopes

/** iOS needs no host handle: the Keychain vault + SQLiter driver are context-free. */
actual class PlatformContext()
