package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.Serializable

/**
 * The [SidecarMethods.SetStatusItem] params (#125, ADR-0024): show or hide the Helper's menu-bar
 * status item. While visible, every click pushes [SidecarTopics.StatusItemClicked] (empty `{}`
 * payload); the Helper removes the item when the requesting connection closes, so it appears only
 * while the app runs. Carries no private content, so it is not redacted.
 */
@Serializable
data class SetStatusItemWire(val visible: Boolean)
