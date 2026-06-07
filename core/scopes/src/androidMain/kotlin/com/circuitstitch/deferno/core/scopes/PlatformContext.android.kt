package com.circuitstitch.deferno.core.scopes

import android.content.Context

/** [context]: the application Context the Android AppScope bindings need (vault, DB, prefs). */
actual class PlatformContext(val context: Context)
