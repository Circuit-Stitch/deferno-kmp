package com.circuitstitch.deferno.ui

import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.MeResult
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.settings.SettingsWriter
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The subset of the androidApp View fakes (#27) the moved shell *component* tests rely on (ADR-0017):
 * the Active-Account / identity / settings doubles the RootComponent + Main shell are built over.
 * Ported here into `app/shell` `commonTest` so the component tests run on the JVM-fast path with no
 * Android/Compose dependency; kept in the `com.circuitstitch.deferno.ui` package the tests already
 * import. Confined to the test source set — never on a main classpath (ADR-0017).
 *
 * The richer feature-component fakes + Compose-View fixtures stay in `app/androidApp`'s test sources,
 * where the View interaction/screenshot tests still need them.
 */

/** A sample signed-in identity for the shell / Profile fixtures (mirrors contracts/fixtures/auth-me.json). */
internal val sampleUser = User(
    id = UserId("1d35f62e-eed9-44de-96e8-e61a307af83f"),
    username = "sampleuser",
    displayName = "Sample User",
    role = "admin",
    personalOrgId = OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390"),
    orgSlug = "u-e4h2qk",
    isAdmin = false,
    consoleUrl = "https://auth2.defernowork.com/ui/console",
)

/** A sample Active Account for the shell / Profile fixtures (the "active Account" control). */
internal val sampleAccount = Account(AccountId("work"), "Work")

/** A sample settings bag for the shell / Settings fixtures (#72). */
internal val sampleUserSettings = UserSettings(
    themeFamily = ThemeFamily.Deferno,
    themeMode = ThemeMode.Auto,
    globalDoneVisibilitySeconds = 259200L,
    dashboardDoneVisibilitySeconds = 86400L,
    timeZone = "America/Los_Angeles",
    trackingEnabled = false,
    dragAndDropEnabled = false,
    username = "sampleuser",
)

/** Programmable [AuthRepository] for the shell + Profile components (defaults to the signed-in [sampleUser]). */
internal class FakeAuthRepository(var result: MeResult = MeResult.Authenticated(sampleUser)) : AuthRepository {
    var loadCount: Int = 0
        private set

    override suspend fun loadMe(): MeResult {
        loadCount++
        return result
    }
}

/**
 * In-memory [SettingsRepository] for the shell / Settings component tests (#72). Backed by a
 * [MutableStateFlow] so `observeSettings()` re-emits; `refresh()` is a no-op (the data is local).
 */
internal class FakeSettingsRepository(initial: UserSettings = sampleUserSettings) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override fun observeSettings(): Flow<UserSettings> = state
    override suspend fun refresh() { /* offline fake */ }
}

/**
 * In-memory [SettingsWriter] for the shell / Settings component tests (#72). Records each intent and
 * mirrors the optimistic apply into [repo] so the observed settings reflect the write (live-apply).
 */
internal class FakeSettingsWriter(private val repo: FakeSettingsRepository = FakeSettingsRepository()) : SettingsWriter {
    val themeChanges = mutableListOf<Pair<ThemeFamily, ThemeMode>>()
    val trackingChanges = mutableListOf<Boolean>()
    val dragAndDropChanges = mutableListOf<Boolean>()

    override suspend fun setTheme(family: ThemeFamily, mode: ThemeMode) {
        themeChanges += family to mode
        repo.state.value = repo.state.value.copy(themeFamily = family, themeMode = mode)
    }

    override suspend fun setTracking(enabled: Boolean) {
        trackingChanges += enabled
        repo.state.value = repo.state.value.copy(trackingEnabled = enabled)
    }

    override suspend fun setDragAndDrop(enabled: Boolean) {
        dragAndDropChanges += enabled
        repo.state.value = repo.state.value.copy(dragAndDropEnabled = enabled)
    }

    override suspend fun setDoneVisibility(globalSeconds: Long?, dashboardSeconds: Long?) {
        repo.state.value = repo.state.value.copy(
            globalDoneVisibilitySeconds = globalSeconds,
            dashboardDoneVisibilitySeconds = dashboardSeconds,
        )
    }
}
