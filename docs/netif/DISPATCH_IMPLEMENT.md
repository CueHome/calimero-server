# DISPATCH — implement netif A+B+C on `netif_fix`

**Status:** READY TO EXECUTE  
**Date:** 2026-08-21  
**Audience:** implementing agent (desk work only)  
**This file is the work order.** Read it end-to-end before touching code.  
**Technical contract (do not re-derive):** `docs/netif/REVIEW-AND-REMAINING-WORK.md`  
**Field procedure (humans only):** `docs/netif/FIELD-TEST-GUIDE.md`

---

## How to start (mandatory)

```text
git fetch origin
git checkout netif_fix
git pull --ff-only origin netif_fix
git log -8 --oneline
git status
```

1. Confirm you are on `netif_fix` of `CueHome/calimero-server`.
2. Read this file fully.
3. Read `docs/netif/REVIEW-AND-REMAINING-WORK.md` fully.
4. Report the current `HEAD` SHA and `git log --oneline eb6fcc9..HEAD` before the first edit.
5. If the branch already has production callers of `NetworkInterfaceResolver` (search the tree), stop and report — do not double-apply.
6. If `git pull --ff-only` fails, stop. Do not merge, rebase, or force-push.

**Baseline when this dispatch was published:** branch HEAD was `b86f1f4` (resolver + tests + review/field/conformance docs only). Later docs commits are fine. Production wiring must still be absent when you begin.

---

## Goal (one sentence)

Named `netif` (e.g. `end1`) must wait for a usable global IPv4 at bind, never crash the JVM at XML parse, never bind `0.0.0.0` / APIPA for a named interface, and rebuild the control endpoint when the host address changes — without opening a PR and without field testing.

---

## Non-negotiable rules

If it is not written here, it is **forbidden**.

| # | Rule |
|---|---|
| 1 | Implement and push to `netif_fix` only. |
| 2 | No PR. No merge to `master`. No new branch. |
| 3 | No rebase onto upstream. No force-push unless the founder orders it in writing. |
| 4 | Do not pin or change `calimero-core`. |
| 5 | Do not make `netif=any` the product default. |
| 6 | Do not live-`rebind()` an open `DatagramSocket`. |
| 7 | Address-change recovery must call **`ControlEndpointService.quit()` only**. Never `LooperTask.quit()` (that cancels the scheduled future forever). |
| 8 | Land **A + B + C + discovery re-resolve + licence headers + `RoutingService` one-liner** in one series. No A-only push. |
| 9 | Do not edit assertions of `xmlParseDoesNotCrashWhenNetifAbsent` or `xmlParseKeepsConfiguredName`. |
| 10 | Source of truth is this branch on GitHub. Ignore any local/session copies, Drive mirrors, or uncommitted workstation patches. |
| 11 | Public repo: no personal names, sprint IDs, agent persona names, or absolute local paths in commits or files. |
| 12 | Desk compile + unit tests only. Do not run Docker/host-net/field tests. |
| 13 | Touch only the files listed in §Files. Anything else: stop and report. |
| 14 | Stop when the stop-gate is met. No extra refactors. |

---

## Problem (verified)

1. **Parse crash:** `Launcher.XmlConfiguration.getNetIf()` resolves the name with `NetworkInterface.getByName` and throws if the interface is missing. JVM exits. Host-net containers that start before `end1` exists crash-loop.
2. **Stale bind:** `ControlEndpointService.createSocket()` binds once and publishes that IPv4 in HPAI / `PID.CURRENT_IP_ADDRESS`. After DHCP moves the address, ETS cannot connect. `onTimeout()` only runs on idle (10 s); keepalives hide the change. Restart is today’s only recovery.

**Branch state before your work:** `NetworkInterfaceResolver` exists and has **zero production callers**. Bind and HPAI behaviour match `master` `eb6fcc9`.

**Why A cannot ship alone:** for a named interface, `usableIpAddresses().findFirst().orElse(anyLocalIPv4Address)` becomes a silent `0.0.0.0` bind when the name is absent. Wildcard reaches search HPAI, connect HPAI, data-endpoint local address, and PID IP attributes. Named interface must **not** use that fallback.

---

## Required implementation

### A — `Launcher.java`

- Keep the configured netif **name** as a `String`.
- Do not throw when the name is not present at parse time.
- Pass the string into service containers (they already take a name).
- Missing/blank → `"any"` as today.
- Do not change XML schema, keys, routing, TP-UART, or security paths.

### B — `ControlEndpointService.createSocket()`

- If netif is not “any”: call resolver `awaitBindAddress(name)`, bind **exactly** that IPv4 and port.
- Wait: poll 500 ms, cap 60 s (`-Dio.calimero.server.netif.retryMillis` / `.waitMillis`).
- Log once when wait starts; then at ≥10 s cadence. No 500 ms spam.
- On timeout: throw unchecked so `LooperTask` (`maxRetries = -1`) retries. Process stays up.
- **No** `orElse(anyLocalIPv4Address)` for a named interface.
- **No** APIPA / link-local / loopback as a successful bind address.
- On bind failure after socket allocation: **close the socket** before rethrow (FD leak under infinite retry).
- After successful bind: PID IP / CURRENT_IP / mask / MAC must match the address actually bound.
- Prefer logging the bound address at **INFO** (not only TRACE) so field logs show success vs failure without raising log level.
- TCP/BAOS endpoints follow the same local address via a **new** `ControlEndpointService` after quit/rebuild. Do not add a second rebind path.

### C — address change (`ControlEndpointService`)

- One check used from `onReceive` (before `super.onReceive`) **and** `onTimeout`.
- Throttle ≥ 5 s between checks.
- If the bound IPv4 is no longer in the usable set for that **name**: log WARNING (container, iface, old → new), then `this.quit()`.
- Comment at the call site: do not call `LooperTask.quit()`.
- Discard the quit instance (`inShutdown` ignores `CONNECT_REQ`).
- Expected field timing: detect ≤5 s; new socket/HPAI ≤15 s (check + 10 s scheduler).

### Discovery — `KNXnetIPServer.java`

- Do not keep `NetworkInterface[]` snapshots from startup for the process lifetime.
- Re-resolve by **name** inside the discovery `LooperTask` supplier on every attempt.
- Same usable rules as B.
- If a **named** discovery interface is missing: log ERROR and skip that interface. Do **not** treat “empty array” as “join every interface” (under host networking that joins docker0/veth).
- Missing discovery must not kill the control-endpoint path.

### Resolver — `NetworkInterfaceResolver.java` (rewrite then wire)

The class on the branch is not safe to call as-is.

| Requirement | Detail |
|---|---|
| Single `isAny` | One definition used by Launcher and `usableIpAddresses()`. No leftover `"any".equals` beside a different helper. |
| Usable IPv4 | `isUp`, `Inet4Address`, `!isLinkLocalAddress()`, `!isLoopbackAddress()`. `169.254/16` is failure. |
| `awaitBindAddress(name)` | One pass: lookup → up → filter → select. Callers bind exactly that address. |
| Multi-address iface | Prefer a global (non-link-local) address; never prefer APIPA when a global exists. |
| `SocketException` | Catch inside the wait loop; treat as not-yet-usable; DEBUG; continue until deadline. |
| Interrupt | Restore interrupt flag; distinguishable failure from “iface never appeared”. |
| Properties | Bad `-D` values: WARNING with key/raw; reject negatives; no `toNanos` overflow. |
| Messages | Report wait in ms (no truncated `timeout 0s`). |
| Dead code | Delete unused `requireName()`. |
| Licence header | Full upstream 36-line header (GPL-2 + **Classpath Exception** + warranty). Copyright **additive**: `2010, 2025 B. Malinowsky` and `2026 CueHome`. |

### Build — `RoutingService.java`

- Delete the stale assignment `dataEndpt = ctrlEndpt;` (fork lag vs upstream `3ada9aa`).
- Do not pin core. Do not touch hop-count or other gateway behaviour.

### Tests — `NetworkInterfaceResolverTest.java`

- **Do not change** assertions of the two XML tests; they go green when A lands.
- Restore full licence header (test file currently incomplete).
- Fix `loopbackIsUsableIfPresent` so it does not vacuous-pass on macOS (`lo` vs `lo0`): enumerate `isLoopback()` or `assumeTrue`.
- Temp files: `@TempDir` or `finally`.
- Add coverage: mid-wait appear (test double, no root/`end1` required); `SocketException` continues; interrupt; link-local-only not usable; named wait expiry throws without implying wildcard.

### Docs

- Do not rewrite `REVIEW-AND-REMAINING-WORK.md`, `FIELD-TEST-GUIDE.md`, or this dispatch.
- Optional one-line status only if needed: wiring SHA + “field tests still pending”. Prefer that line in the delivery report only.

---

## Files you may touch

```
src/io/calimero/server/NetworkInterfaceResolver.java
src/io/calimero/server/Launcher.java
src/io/calimero/server/knxnetip/ControlEndpointService.java
src/io/calimero/server/knxnetip/KNXnetIPServer.java
src/io/calimero/server/knxnetip/RoutingService.java
test/io/calimero/server/NetworkInterfaceResolverTest.java
```

Stop and report before touching anything else (`LooperTask`, `DiscoveryService`, `SubnetConnector`, `build.gradle.kts`, sample configs, other docs).

---

## Git

```text
git add <only files from the list above>
git diff --cached --stat
```

Commit message shape:

```text
fix(netif): await named interface at bind and rebind on address change
```

Optional second commit in the same push:

```text
fix(netif): restore Classpath Exception headers and drop stale dataEndpt
```

```text
git push origin netif_fix
git rev-parse HEAD
git log --oneline eb6fcc9..HEAD
```

No `--force`. No PR.

---

## Commands to run and paste

JDK 21, repo root:

```text
./gradlew compileJava compileTestJava
./gradlew test --tests io.calimero.server.NetworkInterfaceResolverTest
./gradlew test --tests io.calimero.server.gateway.KnxServerGatewayTest
```

If gateway tests fail for reasons unrelated to this change, report the stack and do not “fix” gateway logic. If they fail because compile is still broken, §RoutingService is incomplete.

Also paste:

```text
git diff --stat eb6fcc9
rg -n "NetworkInterfaceResolver|awaitBindAddress" src/
```

---

## Delivery report (chat reply only — do not add a new repo doc)

```text
Repo: CueHome/calimero-server
Branch: netif_fix
HEAD before: <sha>
HEAD after: <sha>
Commits this work: <shas>

A: <file:line>
B: <file:line — awaitBindAddress; no named wildcard; socket closed on bind fail>
C: <file:line — receive+timeout; this.quit()>
Discovery: <file:line — resolve by name; no empty→join-all for named>
RT isAny unified: yes/no + lines
RT no APIPA/loopback: yes/no + lines
RT SocketException in loop: yes/no + lines
RT awaitBindAddress: yes/no + lines
dataEndpt deleted: yes/no
Headers Classpath Exception: yes/no
XML tests unedited: yes/no
Test output: <paste>
git diff --stat eb6fcc9: <paste>
Resolver callers: <paste>
Not done: no PR, no field, no rebase
Blockers: <none | list>
```

---

## Stop-gate

**Done** when all are true:

- Missing named netif at parse does not throw.
- Named netif never binds `0.0.0.0`, APIPA, or loopback.
- Bind failure closes the allocated socket.
- Address change uses `ControlEndpointService.quit()` only.
- Discovery re-resolves by name; named miss does not join all interfaces.
- Resolver has production callers on the pushed SHA.
- XML tests pass without assertion edits.
- Resolver tests green (including new cases).
- Headers include Classpath Exception.
- `dataEndpt` assignment removed.
- No PR opened.

**Stop immediately** if you are about to force-push, open a PR, pin core, rebase, land a partial fix, weaken tests, or edit files outside the allow-list.

If the gate cannot be met: push nothing further; report blocker with file:line.

---

## Explicitly not this agent’s job

- Field / Docker / host-net / TP-UART tests (see `FIELD-TEST-GUIDE.md`).
- Declaring the branch “site ready”.
- Opening a PR.
- Changing sample `server-config.xml` defaults.

Desk-complete means: pushed SHA meets the stop-gate; humans still run field tests 1–3 before any merge discussion.
