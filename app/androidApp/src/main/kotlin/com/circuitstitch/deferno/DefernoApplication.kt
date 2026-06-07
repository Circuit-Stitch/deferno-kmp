package com.circuitstitch.deferno

import android.app.Application
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.demo.SampleData

/**
 * Application entry point.
 *
 * Holds the **process-global data layer** the shell is scoped against (ADR-0008 G2): repositories are
 * created once per process and shared across scenes, while presentation (the per-scene
 * [com.circuitstitch.deferno.shell.RootComponent] built in [MainActivity]) is scene-scoped (G3).
 *
 * STUB until DI lands (ADR-0008): in-memory [DemoTaskRepository]/[DemoPlanRepository] over [SampleData].
 * When the DI scene graph arrives these are replaced by the real, DI-provided singletons.
 */
class DefernoApplication : Application() {

    internal lateinit var taskRepository: DemoTaskRepository
        private set

    internal lateinit var planRepository: DemoPlanRepository
        private set

    override fun onCreate() {
        super.onCreate()
        taskRepository = DemoTaskRepository(SampleData.tasks)
        planRepository = DemoPlanRepository(
            SampleData.planTaskIds.mapNotNull { id -> SampleData.tasks.firstOrNull { it.id == id } },
        )
    }
}
