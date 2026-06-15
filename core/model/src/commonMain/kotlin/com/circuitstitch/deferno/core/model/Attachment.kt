package com.circuitstitch.deferno.core.model

import kotlin.time.Instant

/**
 * A file attached to a Task — the domain model of `GET /tasks/{id}/attachments` (the web detail's
 * "Attachments" grid). The wire shape (snake_case keys, RFC3339 strings) is condensed at the network
 * boundary (ADR-0011). v1 is **read-only**: the detail lists and opens attachments but does not upload
 * or delete them (the presign→PUT→commit upload flow is a follow-up — see the feedback flow for the
 * reusable mechanism).
 *
 * - [url] is a ready-to-open link to the file (the backend signs it); the UI hands it to the platform
 *   to open in a browser/viewer.
 * - [mime] drives the rendering choice (image thumbnail vs generic file row).
 * - [size] is in bytes, shown human-readable.
 */
data class Attachment(
    val id: String,
    val filename: String,
    val mime: String,
    val size: Long,
    val url: String,
    val caption: String? = null,
    val createdBy: UserId,
    val createdAt: Instant,
)
