package com.circuitstitch.deferno.ui

import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.MeResult
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.auth.AuthComponent
import com.circuitstitch.deferno.feature.auth.AuthState
import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.plan.PlanState
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.profile.ProfileState
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import com.circuitstitch.deferno.feature.tasks.TaskListComponent
import com.circuitstitch.deferno.feature.tasks.TaskListState
import com.circuitstitch.deferno.feature.tasks.TaskTreeComponent
import com.circuitstitch.deferno.feature.tasks.TaskTreeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * Test doubles + fixtures for the feature Views (#27). The Views are rendered through their public
 * `*Screen(component)` entry points driven by these fakes, so the tests touch only public API and
 * record the navigation/refresh intents each View emits.
 */

private val FIXED_CREATED = Instant.parse("2026-06-01T09:00:00Z")

internal fun sampleTask(
    id: String,
    title: String = "Task $id",
    workingState: WorkingState = WorkingState.Open,
    ref: String? = "u-deferno-$id",
    parentId: String? = null,
    children: List<String> = emptyList(),
    sequence: Long? = null,
    pinned: Boolean = false,
    description: String? = null,
    hydration: HydrationState = HydrationState.Summary,
): Task = Task(
    id = TaskId(id),
    orgSlug = "u-deferno",
    title = title,
    workingState = workingState,
    ref = ref,
    parentId = parentId?.let(::TaskId),
    children = children.map(::TaskId),
    sequence = sequence,
    pinned = pinned,
    description = description,
    dateCreated = FIXED_CREATED,
    hydration = hydration,
)

/** A small, varied task set for list/screenshot fixtures. */
internal object SampleTasks {
    val list: List<Task> = listOf(
        sampleTask("1", "Plan the spring launch", WorkingState.InProgress, pinned = true, children = listOf("1a", "1b")),
        sampleTask("2", "Water the plants"),
        sampleTask("3", "Reply to Sam", WorkingState.InReview),
        sampleTask("4", "Old idea worth revisiting", WorkingState.Dropped),
    )

    val children: List<Task> = listOf(
        sampleTask("1a", "Draft the announcement", parentId = "1", sequence = 1),
        sampleTask("1b", "Schedule the post", WorkingState.Done, parentId = "1", sequence = 2),
    )
}

internal class FakeTaskListComponent(initial: TaskListState) : TaskListComponent {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<TaskListState> = _state
    val clicked = mutableListOf<TaskId>()
    var refreshCount = 0
        private set

    override fun onTaskClicked(id: TaskId) { clicked += id }
    override fun onRefresh() { refreshCount++ }
}

internal class FakeTaskDetailComponent(
    initial: TaskDetailState,
    override val taskId: TaskId = TaskId("1"),
) : TaskDetailComponent {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<TaskDetailState> = _state
    var closeCount = 0
        private set
    var showTreeCount = 0
        private set
    var addToPlanCount = 0
        private set

    override fun onCloseClicked() { closeCount++ }
    override fun onShowTreeClicked() { showTreeCount++ }
    override fun onAddToPlanClicked() { addToPlanCount++ }
}

internal class FakeTaskTreeComponent(
    initial: TaskTreeState,
    override val rootId: TaskId = TaskId("1"),
) : TaskTreeComponent {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<TaskTreeState> = _state
    val childClicked = mutableListOf<TaskId>()
    var closeCount = 0
        private set

    override fun onChildClicked(id: TaskId) { childClicked += id }
    override fun onCloseClicked() { closeCount++ }
}

internal class FakePlanComponent(initial: PlanState) : PlanComponent {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PlanState> = _state
    val clicked = mutableListOf<TaskId>()
    var refreshCount = 0
        private set

    override fun onTaskClicked(id: TaskId) { clicked += id }
    override fun onRefresh() { refreshCount++ }
}

/** A sample signed-in identity for the #20 auth screen fixtures (mirrors contracts/fixtures/auth-me.json). */
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

internal class FakeAuthComponent(initial: AuthState) : AuthComponent {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<AuthState> = _state
    var retryCount = 0
        private set

    override fun onRetry() { retryCount++ }
}

/** A sample Active Account for the shell / Profile fixtures (the "active Account" control). */
internal val sampleAccount = Account(AccountId("work"), "Work")

/** Programmable [AuthRepository] for the shell + Profile Views (defaults to the signed-in [sampleUser]). */
internal class FakeAuthRepository(var result: MeResult = MeResult.Authenticated(sampleUser)) : AuthRepository {
    var loadCount: Int = 0
        private set

    override suspend fun loadMe(): MeResult {
        loadCount++
        return result
    }
}

internal class FakeProfileComponent(
    initial: ProfileState,
    override val account: Account = sampleAccount,
) : ProfileComponent {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<ProfileState> = _state
    var retryCount = 0
        private set
    var signOutCount = 0
        private set

    override fun onRetry() { retryCount++ }
    override fun onSignOut() { signOutCount++ }
}
