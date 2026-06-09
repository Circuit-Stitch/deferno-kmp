package com.circuitstitch.deferno.core.sidecar

import java.nio.file.Path

/**
 * The **client half** of peer authentication (ADR-0009/0024): before trusting a socket endpoint, verify
 * it is really *ours*. A separate, **per-transport** seam — never folded into [Transport] — so each
 * transport keeps its full-strength, OS-appropriate check instead of a portable lowest-common-denominator
 * (ADR-0025): [PosixPeerTrust] checks file ownership + mode bits; a future Windows pipe transport will
 * bring its own ACL check.
 *
 * This complements, and does not replace, the **server-half** kernel check (`getpeereid`/`SO_PEERCRED`)
 * that authenticates the *connecting client's* uid — that is the Helper's responsibility (#121). The
 * in-band token in [SidecarFrame.Hello] is the third, transport-agnostic leg, performed by the client
 * after [Transport.connect].
 */
fun interface PeerTrust {
    /**
     * Verify the endpoint at [address] is trustworthy.
     *
     * @throws SidecarSecurityException if it is not owned by the current user, or is reachable by group
     *   or other principals (ADR-0009).
     */
    fun verify(address: Path)
}
