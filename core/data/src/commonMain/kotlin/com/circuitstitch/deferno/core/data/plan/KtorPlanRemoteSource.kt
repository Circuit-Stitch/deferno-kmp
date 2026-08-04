package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.asSnapshot
import com.circuitstitch.deferno.core.model.PlanItemRef
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.map
import com.circuitstitch.deferno.core.network.mapper.itemId
import com.circuitstitch.deferno.core.network.mapper.itemKind
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.appendPathSegments
import kotlinx.datetime.LocalDate

/**
 * The production [PlanRemoteSource] over the shared Deferno [HttpClient] (#17/#18, ADR-0001).
 *
 * **`/items/plan`, not `/tasks/plan` (#385).** The two paths mount handlers that seed the day
 * identically and differ only in what they will return: the `/tasks/plan` handler resolves the day's
 * ordered ids against the server's Task store alone and drops the rest, so a day holding a Habit and
 * a Chore comes back as `[]` — the client rendered a blank Plan because the server sent one. The
 * polymorphic mirror returns every planned row tagged by kind.
 *
 * The rows decode as [ItemView], the same `oneOf{task,habit,chore,event}` union `/items` uses: the
 * response envelope stamps the legacy `type` discriminator on every row regardless of the inner
 * union's own `kind` tag, and `type` is what this client keys on (see [ItemView]). So the shipping
 * tolerant reader parses this endpoint with no new DTO — the inline `today_occurrence` and the
 * redundant `kind` pass straight through `ignoreUnknownKeys`.
 *
 * Only the ordering is kept ([PlanItemRef] — the id and its kind). The plan is a curation, not a
 * payload (ADR-0053): the rows themselves live in the four per-kind caches that the `/items` cold
 * sync reconciles independently, and the kind tag is what lets the repository resolve each id against
 * the right one instead of probing all four.
 *
 * **Discarding the payload is a requirement, not a simplification.** `blocked` and `is_blocker` are
 * not fields on the server's item structs at all — the `/items` handler stamps them onto its own
 * projection, and `items_plan.rs` does no such stamping. So every row here decodes with both flags
 * `false` regardless of the truth, and upserting these rows into the per-kind caches would silently
 * clear dependency state that `/items` had set correctly. Read the order from this endpoint; read the
 * items from `/items`.
 *
 * Offline-first (ADR-0001): a failure maps to [RemoteSnapshot.Unavailable], so a failed plan refresh
 * leaves the cached plan untouched — distinct from an [RemoteSnapshot.Available] empty day, which
 * legitimately clears it.
 */
class KtorPlanRemoteSource(
    private val client: HttpClient,
) : PlanRemoteSource {

    override suspend fun fetchPlan(date: LocalDate, tz: String): RemoteSnapshot<List<PlanItemRef>> =
        client.requestApi<List<ItemView>> {
            url { appendPathSegments("items", "plan") }
            parameter("date", date.toString())
            parameter("tz", tz)
        }
            .map { rows -> rows.map { PlanItemRef(id = it.itemId, kind = it.itemKind) } }
            .asSnapshot()
}
