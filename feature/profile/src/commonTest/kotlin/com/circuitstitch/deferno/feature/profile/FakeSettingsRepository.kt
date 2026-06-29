package com.circuitstitch.deferno.feature.profile

import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test [SettingsRepository] emitting a fixed [UserSettings] so the Profile component test can assert the
 * time zone it mirrors. [refresh] is a no-op (the Profile View only observes; it never pulls).
 */
class FakeSettingsRepository(private val settings: UserSettings = UserSettings.Default) : SettingsRepository {
    override fun observeSettings(): Flow<UserSettings> = flowOf(settings)
    override suspend fun refresh() = Unit
}
