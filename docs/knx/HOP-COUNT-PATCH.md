# `KnxServerGateway.adjustHopCount` — the Cue deviation from upstream

**Read this before rebasing or merging upstream into this fork.** This method differs from upstream in a way
that changes KNX routing safety, the difference is not recorded in any commit message, and a rebase can lose
or silently keep it without anyone noticing. `KnxServerGatewayHopCountTest` pins the current behaviour so the
decision has to be deliberate either way.

## It is a rewrite, not an addition

`adjustHopCount` **exists upstream**, with the same name, at the same line, called from the same five sites.
The fork rewrote its body. Anyone told this is "a bespoke method that does not exist upstream" has been
misinformed — the interesting thing is the behavioural delta, not the method's existence.

| | upstream `calimero-project/calimero-server` | this fork |
|---|---|---|
| System broadcast | no special case | returned unchanged (**new early return**) |
| **Hop count 0** | **logs WARNING, returns `null` → frame discarded** | **returns the frame → forwarded, not decremented** |
| Extended frame | `ldataEx.setHopCount(count)` — mutated **in place**, same instance returned | rebuilt via the 7-arg `CEMILDataEx` constructor — **new instance**, input untouched |
| Standard frame | rebuilt as `CEMILData` | rebuilt as `CEMILData`, or `CEMILDataEx` if payload > 16 (see below) |
| Method | instance method, **can return `null`** | `static`, **never returns `null`** |

## Why it exists — no rationale was ever recorded

Searched and came up empty:

- Both commits (`5ebb820`, `eb6fcc9`, 2026-02-25) carry the GitHub web-editor default message
  "Update KnxServerGateway.java". No body, no ticket, no issue.
- No design note, ADR, or incident report anywhere in the estate mentions KNX hop count.
- The reverse-engineering pass over this repo recorded the **effect** and classed it as a deliberate change,
  but recorded no **intent**.

The only statement of purpose that exists anywhere is an explicit *inference* by that RE pass:

> Likely intent: stop legitimate hop-0 telegrams being silently dropped in Cue's one-gateway setup.

**That is a guess, and it is labelled as one here so it does not harden into folklore by being repeated.**
It is a plausible guess: in a single-gateway topology there is no routing loop to protect against, and
upstream's silent discard would drop real telegrams that arrived with an exhausted counter. Nobody has
confirmed it. If you know the actual reason, replace this section — that is the whole point of the file.

## What it costs

**KNX routing-counter loop prevention is removed.** The hop count exists so a telegram cannot circulate
forever; discarding at zero is how that is enforced. Forwarding at zero — and not decrementing — means an
exhausted-hop telegram keeps going. This is safe in a single-gateway topology and unsafe the moment the
installation has a KNX/IP routing loop. Upstream's WARNING is also gone, so if it ever does happen there is
nothing in the log to say so.

Tracked as an open MEDIUM defect in the estate reverse-engineering register (`S10-B01`).

**Extended-frame fidelity is unverified.** Rebuilding a `CEMILDataEx` through the 7-arg constructor instead
of mutating it in place may not carry over fields the constructor defaults — additional info blocks in
particular. Nobody has checked against `calimero-core`. Open LOW defect (`S10-B02`).

**The `payload.length > 16` branch is dead code.** It is only evaluated when the frame is *not* already a
`CEMILDataEx`, and a plain `CEMILData` cannot hold more than 16 payload bytes — the constructor rejects it
("maximum TPDU length is 16 in L-Data frames"). So the condition is always false there. Harmless, and it
reads as a defensive guard written without realising the type already guarantees it. Pinned by
`longPayloadCannotReachTheStandardFrameRebuild` rather than deleted, because the observable contract it was
reaching for — long payload in, extended frame out — is real, and is satisfied by the earlier branch.

**Five caller null-guards are unreachable.** Because the method can no longer return `null`, every
`if (send == null)` around its five call sites is dead. Left in place deliberately: they become live again
the moment upstream's discard is restored. `neverReturnsNullForAnyBranch` fails if that happens, which is
the signal to go and re-read them.

## The thing most likely to surprise you

**The fork may not be what runs in the field.** The device build pulls `calimeroproject/knxserver:latest` —
the **upstream** image — and re-tags it as `cuehome-knx`. On that path none of this patch ships, and the
re-tag makes the running container look like a Cue build when it is not. Open defect `S4a-B10`.

So before treating the loop-prevention removal as a live production risk, confirm what is actually deployed.
And before assuming this patch protects anything in the field, confirm the same thing.

## If you are rebasing

The fork is well behind upstream. When you rebase or merge:

1. `KnxServerGatewayHopCountTest` will fail if this method's behaviour reverts to upstream's. That failure is
   information, not an obstacle — decide, then update the test to match the decision.
2. If you keep the fork's behaviour, keep this document with it.
3. If you adopt upstream's behaviour, the five `send == null` guards become live again — check each one.
4. Do not "fix" the dead `> 16` branch and the stale comment as drive-by cleanups in a rebase commit. They
   are documented here; changing them is a separate, visible decision.

## Evidence

- Fork: `src/io/calimero/server/gateway/KnxServerGateway.java`, `adjustHopCount`, call sites at five places
  in the same file.
- Upstream: same path in `calimero-project/calimero-server`.
- Commits: `5ebb820`, `eb6fcc9` (2026-02-25).
- Estate RE register: `S10-B01` (MEDIUM, open), `S10-B02` (LOW, open), `S4a-B10` (deployment provenance,
  open), `SRV-27` (open decision — needs `calimero-core` to confirm frame fidelity).
- Tests: `test/io/calimero/server/gateway/KnxServerGatewayHopCountTest.java`.
