package com.circuitstitch.deferno.core.network.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformHttpClientEngine(): HttpClientEngine = OkHttp.create()
