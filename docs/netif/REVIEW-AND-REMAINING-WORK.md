# `netif_fix` — review findings and remaining work

**Date:** 2026-08-21 · **Branch:** `netif_fix`, from `master` `eb6fcc9`
**On the branch today:** `7a778ed` `NetworkInterfaceResolver` · `601297d` `NetworkInterfaceResolverTest`
**Still to land:** `Launcher.java` · `ControlEndpointService.java` · `KNXnetIPServer.java` ·
the `RoutingService.java:92` deletion

**Decision (project owner, 2026-08-21):** parts A, B and C land **together**, no partial fix.
Two half-fixes would still crash on a late `end1` or leave ETS on a stale HPAI after DHCP.

This document is the review output: what the fix must do, what the review found wrong with what is
already committed, and what has to be true before a PR. It prescribes; it does not implement.

---

## 0. Source of truth for every line reference below

Every line reference below was read out of the source, not recalled — and then **verified byte-identical
against this branch**. `Launcher.java`, `ControlEndpointService.java`, `KNXnetIPServer.java`,
`LooperTask.java` and `RoutingService.java` are all identical between the reading copy and `netif_fix`
(`diff` clean, all five). **The line numbers hold as written. No re-confirmation needed.**

---

## 1. The one thing that must not be got wrong — A without B binds `0.0.0.0`

`ControlEndpointService.createSocket()` (`:717`) ends with:

```java
ip = usableIpAddresses().findFirst().orElse(anyLocalIPv4Address);
s.bind(new InetSocketAddress(ip, svcCont.port()));
```

and `usableIpAddresses()` (`:760`) returns `Stream.empty()` when the **named** interface is absent
(`:764` — the `"any"` fallbacks below it are unreachable for `netif="end1"`).

So the moment **A** removes the parse-time hard fail, an absent `end1` no longer crashes — it
**binds the wildcard address, succeeds, publishes a `0.0.0.0` HPAI, and never retries.** A green log
line, a dead server, no crash loop to alert on. That is a worse failure than the one being fixed.

**Therefore: for a named (non-`any`) interface, the `orElse(anyLocalIPv4Address)` fallback must be
deleted, not kept as a safety net.** B's wait-then-throw takes its place. Land A and B in the same
commit. This is the mechanism that makes the founder's "one go" call correct, not merely tidy.

---

## 2. What each part changes

### A — `Launcher.java`: keep the name, drop the hard fail

`XmlConfiguration.getNetIf()` (`:566`–`:581`) calls `NetworkInterface.getByName(attr)` at parse time
and throws `KNXIllegalArgumentException` when it returns `null`. The JVM exits before the server loop.
That is the crash loop.

**Good news — the plumbing is already right.** At `:386` the resolved object is immediately reduced to
a string:

```java
final String netifName = netif != null ? netif.getName() : "any";
```

and `DefaultServiceContainer` / `RoutingServiceContainer` (`:390`, `:393`) are constructed with that
**String**. The container never held a `NetworkInterface`. So A is small:

- `getNetIf()` returns the configured **name** (`String`), never resolves, never throws.
- An unresolvable name is a `LOG.info`-level fact at parse, not an exception.
- `netif="any"` keeps today's exact behaviour.
- Do **not** widen the change into the container constructors — they already take a name.

### B — `ControlEndpointService.createSocket()`: resolve at bind, wait, then fail loud

`usableIpAddresses()` (`:761`) **already** does `NetworkInterface.getByName(svcCont.networkInterface())`
at runtime. Resolve-at-bind is half-present; B finishes it.

- Poll every **500 ms**, cap **60 s** (`-Dio.calimero.server.netif.retryMillis` / `.waitMillis`).
- Log **once on entering the wait** and then at a decaying or ≥10 s cadence — not every 500 ms.
  A genuinely removed NIC must not write 120 lines/minute forever.
- On success: bind, then set `PID.IP_ADDRESS` / `PID.CURRENT_IP_ADDRESS` (`:194`–`:197`) from the
  address actually bound.
- On cap expiry: **throw a `RuntimeException`.** Do not fall back to `anyLocalIPv4Address` (§1).

**Why the throw is safe — verified, do not re-derive:** `KNXnetIPServer.java:407` builds the control
endpoint as `new LooperTask(this, ..., -1, builder)` and `:212` calls `LooperTask.scheduleWithRetry`.
`LooperTask.java:83` — `maxRetries = -1` means infinite. `LooperTask.run()` (`:88`) catches
`RuntimeException` from `supplier.get()`, logs WARNING, and returns; `scheduleWithFixedDelay(task, 0,
10s)` (`:63`, `retryDelay = 10`) re-fires. The process stays up.

**Derived cadence for the field test:** the wait blocks *inside* `supplier.get()`, and
`scheduleWithFixedDelay` does not overlap runs. A 60 s cap therefore yields a **~70 s retry cycle**,
not 10 s. Field test #5 must be scored against ~70 s.

**Verify before landing:** the 60 s park happens on `Executor.scheduledExecutor()` (calimero-core). If
that pool is small, a parked control-endpoint task can delay the discovery endpoint's own retries.
Check the pool size and report it. If it is single-threaded, say so and stop — the cap needs rethinking.

### C — address change: check on receive **and** timeout, 5 s, close and let the looper rebuild

`onTimeout()` (`:303`–`:316`) already compares the bound IP against `usableIpAddresses()` and calls
`quit()`. Two gaps: the looper timeout is **10 s** (`super(server, null, 512, 10000)`, `:267`) and
`onTimeout` only fires on **idle** — KNX keepalive traffic suppresses it indefinitely. That is why
`192.168.0.221 → 192.168.1.73` stayed silent until process restart.

- Run the same comparison on **receive** as well as timeout, throttled to at most once per **5 s**.
- On mismatch: log at WARNING with old → new, then `quit()`. Keep the existing `onTimeout` check.
- **Do not** attempt a live `rebind()` of an open `DatagramSocket`.

**Why `quit()` rebuilds rather than kills — verified, this is the load-bearing check:**
`ControlEndpointService.quit()` (`:271`) sets `inShutdown`, closes data connections and endpoints,
and calls `super.quit()`, so `looper.run()` returns. `LooperTask.run()` then calls `cleanup(INFO, null)`
— and `cleanup()` (`LooperTask.java:132`) **only logs; it does not cancel `scheduledFuture`.** The
fixed-delay scheduler re-fires 10 s later and calls `supplier.get()`, producing a **fresh**
`ControlEndpointService` with a fresh socket, fresh `CURRENT_IP`, fresh HPAI.

**Consequence for field test #4:** detection ≤5 s **plus** up to 10 s scheduler delay ⇒ rebind lands in
**~5–15 s**. An acceptance criterion of "~5 s" will score a correct fix as a failure. Use ≤15 s.

**Hard constraint:** the quit'd instance must be **discarded, never reused.** `:346` makes an endpoint
with `inShutdown == true` silently ignore every `CONNECT_REQ`. A patch that reuses the instance will
look alive in logs and refuse every ETS connection.

### C-adjacent — `KNXnetIPServer.java` discovery must re-resolve by name

`startDiscoveryService(outgoingIf, discoveryIfs, -1)` (`:574`, `:739`) is passed **`NetworkInterface[]`
objects resolved once at startup**. A Java `NetworkInterface` is a snapshot. After `end1` disappears and
returns, those objects are stale and discovery keeps announcing the old address even once the control
endpoint has rebound — ETS search would show the fix as not working.

**Re-resolve by name inside the `LooperTask` supplier, on every attempt.** Discovery is also `-1`
(infinite retry), so the same wait-then-throw shape from B applies. Do not resolve to objects at startup.

---

## 3. Definition of done

1. A, B, C and the discovery change land on `netif_fix` in **one commit series, no partial push**.

2. **The build blocker is solved and it is not what the decision doc thought.** This was reproduced and fixed
   in a throwaway build copy; the finding is verified, the landing is not done.

   The doc said `./gradlew test` was red on `RoutingService.dataEndpt` "vs current `calimero-core` SNAPSHOT"
   and that the resolver tests "need a compile-clean core to run." **The fork is stale, not core.**
   `RoutingService.java:92` still carries `dataEndpt = ctrlEndpt;`. Upstream `calimero-project/calimero-server`
   **deleted that exact line** in `3ada9aa` ("Remove unused code", 2026-04-03) — its
   `RoutingServiceHandler` constructor no longer has it. Our `master` `eb6fcc9` never picked that up.

   **Fix = delete `RoutingService.java:92`.** Do not pin `calimero-core`. Verified on JDK 21
   against `3.0-SNAPSHOT`:

   | Attempt | Result |
   |---|---|
   | `3.0-SNAPSHOT`, line present | 1 error — reproduces the reported failure exactly |
   | pin `3.0-M2` | same 1 error — **pinning does not help** |
   | pin `3.0-M1` | **200 errors** — far worse, do not go here |
   | `3.0-SNAPSHOT`, line deleted | `BUILD SUCCESSFUL`, `compileJava` + `compileTestJava` clean |

3. **`NetworkInterfaceResolverTest` has now been run — it is red, and it is red for the right reason.**
   6 tests, **4 pass, 2 fail**, 0.112 s:

   | Test | Result |
   |---|---|
   | `anyIsNotResolved()` | pass |
   | `missingInterfaceIsEmptyOnFind()` | pass |
   | `loopbackIsUsableIfPresent()` | **vacuous pass** — see below |
   | `awaitMissingInterfaceFailsAfterTimeout()` | pass |
   | `xmlParseKeepsConfiguredName()` | **FAIL** — `KNXIllegalArgumentException: no network interface found with the specified name 'end1'` |
   | `xmlParseDoesNotCrashWhenNetifAbsent()` | **FAIL** — `AssertionFailedError: Unexpected exception thrown: KNXIllegalArgumentException ... 'end1-does-not-exist'` |

   **One of those four "passes" is not a pass.** `loopbackIsUsableIfPresent()` opens with
   `NetworkInterface.getByName("lo")` and returns early if null. On macOS the loopback is `lo0`, so
   `getByName("lo")` returns `null` (verified on macOS: the loopback is `lo0`) and the test returns having executed
   **zero assertions** — JUnit reports success. It is the only test touching the resolver's *success*
   path, and it is a silent no-op on every developer Mac. Even on Linux its assertion is a three-way
   disjunction satisfiable without the resolver working. Real score: **3 meaningful passes, 1 vacuous,
   2 red.**

   Both failures are the **part-A** tests, and both fail on the parse-time throw A is meant to remove. The
   suite was written test-first and it is honest — it fails without the fix. **That is your acceptance gate:
   land A and those two go green with no edit to the test file.** If you find yourself changing the test to
   make it pass, stop and stop and raise it.

4. Delivery report cites **code evidence per claim** — file + line + the changed lines. No "done" without it.
   Include the `6/6 passing` test output.

5. **No PR** until field tests 1-3 below are observed on the TP-UART host.

## 3b. Licence header — BLOCKING, and it is not cosmetic

The licence audit returned **GATE: BLOCKED**. Independently re-read and confirmed.

Upstream calimero files carry a **36-line** header. `NetworkInterfaceResolver.java` carries **10 lines**,
and `NetworkInterfaceResolverTest.java` carries **none at all** (0 lines before `package`) — the only two
files out of 40 in the tree without the full header.

What the short header drops:

| Clause | Upstream `Launcher.java` | New file | Consequence |
|---|---|---|---|
| **Classpath / linking exception** | `:19`-`:34` | **absent** | **This is the blocker.** It is the clause that lets CueHome link a proprietary bridge, Matter controller or closed driver against this GPL-2 server and ship the combination on its own terms. The exception is per-file and its own text says an author who does not wish to extend it should "delete this exception statement from your version" — so an omitted exception reads as a deliberate refusal. One bare-GPL file in the shipped jar undermines the linking safety the rest of the codebase provides. |
| **Warranty disclaimer** | `:11` | absent | GPL-2 §1 requires notices as to absence of warranty be kept intact. Direct commercial-liability exposure in a paid installation. |
| **FSF copy-of-licence** | — | absent | Low impact; `LICENSE.txt` ships in every jar via `build.gradle.kts:62`. |
| **Copyright holder** | `2010, 2025 B. Malinowsky` | **replaced** with `2026 CueHome` | Must be **additive**, not a replacement — the file contains upstream expression (the exception message at `:67` is byte-identical to `Launcher.java:576`, and `findUsable` mirrors `getNetIf`). GPL-2 §2(a) also wants a dated change notice. |

**Fix — no code change required.** Restore the full 36-line upstream header on **both** new files, with the
copyright block additive:

```
Copyright (c) 2010, 2025 B. Malinowsky
Copyright (c) 2026 CueHome
```

That one edit clears the whole finding. Do it in the same commit series.

**Two things already settled, so they need no chasing:**
- The fork `CueHome/calimero-server` is **public**, correctly marked as a fork of
  `calimero-project/calimero-server`. The GPL-2 §3 source-availability obligation is therefore already
  substantially met by publication — add a written offer to the installation docs as belt-and-braces, but
  it does not block this merge.
- The branch introduced **no new dependency**. `build.gradle.kts` and `settings.gradle` are byte-identical
  to upstream. JUnit Jupiter 6.0.0 (EPL-2.0) is pre-existing, test-scope only, and never reaches the jar.

**Escalated to founder, not to you:** whether shipping the image to a site counts as GPL distribution turns
on whether the customer takes possession of a copy. That is a commercial-arrangements question for counsel,
not an engineering one. Note GPL-2 has **no** network-service clause — merely running the server on a
customer LAN triggers nothing. This is routinely over-read as if it were AGPL. It is not.

---

## 3c. Red Team findings — the resolver as written does NOT yet make §1 safe

The red-team pass returned **1 CRITICAL, 5 HIGH, 7 WARNING**. The two that change the design were re-verified independently.
**These are required work, not advisories.** §1 of this dispatch says a silent `0.0.0.0` bind is worse than
the crash loop. RT found **two separate paths that produce exactly that** in the code already on the branch.

### RT-B-01 [HIGH] — two different definitions of "any" in one bind path — **confirmed**

`NetworkInterfaceResolver.isAny()` (`:42`-`:45`) is case-insensitive and accepts `any`, `all`, `default`,
blank and `null`. Both places it must interoperate with use an **exact, case-sensitive** match:
`Launcher.java:571` and `ControlEndpointService.java:765` both test `"any".equals(...)`.

Traced chain for `netif="ALL"`: `isAny("ALL")` → true → B treats it as the any-path and leaves the fallback
in place → `usableIpAddresses()` calls `getByName("ALL")` → `null` → `!"any".equals("ALL")` → `Stream.empty()`
→ `.orElse(anyLocalIPv4Address)` → **binds `0.0.0.0`.** Today that same config **hard-fails loudly** at
`Launcher.java:580`. This is a straight regression introduced by the fix.

**Required:** one definition of "any" in the process. Either narrow `isAny()` to `name == null || "any".equals(name)`,
or make it authoritative and change `Launcher.java:571` and `ControlEndpointService.java:765` to call it.
Do not leave two.

### RT-B-02 [HIGH] — link-local satisfies "usable", and it is the site's exact scenario — **confirmed**

`findUsable` (`:63`) is `nif.inetAddresses().anyMatch(Inet4Address.class::isInstance)` — no link-local
filter, no loopback filter. Upstream filters loopback, but only on its `any` branch (`:769`, `:774`), never
on the named branch.

The failure is the real one: container starts, `end1` comes up, **DHCP has not answered yet**, Linux
assigns APIPA `169.254.x.x`. `awaitUsable` sees an `Inet4Address`, declares success and returns **before the
lease arrives**. `createSocket()` binds `169.254.x.x`, writes it to `PID.CURRENT_IP_ADDRESS` and publishes it
in the HPAI. The wait *succeeded*, so there is no throw, no retry, no crash loop — a green log and a server
ETS cannot reach.

**Required:** `findUsable` must require at least one IPv4 that is `!isLinkLocalAddress() && !isLoopbackAddress()`.
Loopback only behind an explicit test-rig opt-in.

### RT-B-03 [HIGH] — a transient `SocketException` mid-wait abandons the whole wait

`findUsable` (`:66`-`:68`) converts `SocketException` into an escaping `KnxRuntimeException`, and
`awaitUsable`'s loop (`:86`-`:102`) calls it with **no try/catch**. Both sources — `getByName()` and
`isUp()` — are native JNI calls that surface `ioctl` failures. An interface being torn down or re-created
by DHCP/udev makes `SIOCGIFFLAGS` fail with `ENODEV`, which is *the* condition this class exists to survive.
There is also a plain TOCTOU: `getByName` succeeds, the interface vanishes, `isUp()` throws.

**Required:** catch `SocketException` **inside** the loop, treat as "not yet usable", log DEBUG, sleep,
continue. Let it escape only after the deadline.

### RT-B-04 [HIGH] — the resolver hands back a snapshot and no address

Every public method returns `Optional<NetworkInterface>` — a snapshot, the exact anti-pattern this dispatch
names under "C-adjacent". There is no `awaitAddress`. So B either binds an address up to 500 ms stale, or
re-resolves through `usableIpAddresses()` — a **second lookup with a different predicate** that does not
call `isUp()` and does not filter link-local, and whose miss falls into the forbidden wildcard.

**Required:** add an atomic `Optional<InetAddress> awaitBindAddress(String name)` doing lookup, up-check,
address filter and selection in one pass; bind exactly what it returns; refactor `usableIpAddresses()` to
delegate to the resolver so one predicate governs both bind and C's comparison.

### RT-C-01 [HIGH] — C must call `ControlEndpointService.quit()`, **never** `LooperTask.quit()`

This dispatch's load-bearing claim — `cleanup()` only logs, so the endpoint rebuilds — is true, but **only**
via `ControlEndpointService.quit()`. The other `quit()` in scope does the opposite:

```java
// LooperTask.java:124-130
void quit() {
    looper().ifPresentOrElse(UdpServiceLooper::quit, () -> cleanup(INFO, null));
    final var future = scheduledFuture;
    if (future != null) future.cancel(true);   // permanent — kills the rebuild forever
}
```

`scheduleWithFixedDelay` cancellation is permanent. If C is implemented by reaching for the `LooperTask`
(the intuitive "restart the task" move, reachable via `KNXnetIPServer.Endpoint.controlEndpoint`), the
endpoint is **never rebuilt for the process lifetime**, while `cleanup()` logs a cheerful `"... closed"` at
INFO — indistinguishable from a clean shutdown.

**Required:** C invokes `this.quit()` on the `ControlEndpointService` instance. It must never touch
`LooperTask.quit()` or `scheduledFuture`. Put that in a comment at the call site.

### WARNINGs — fix in the same pass

| ID | Fix |
|---|---|
| RT-B-05 | Interrupt during the park throws a generic `KnxRuntimeException`, so every shutdown while parked logs `WARNING ... retry in 10 seconds` + stack trace when `cancel(true)` has already made retry impossible. Throw a distinguishable type; log DEBUG. |
| RT-B-06 | `Duration.toSeconds()` truncates — an 800 ms wait reports `timeout 0s`, and `waitMillis=-5000` prints `after -5s`. Report millis and clamp the message like the deadline is clamped. |
| RT-B-07 | `durationProperty` silently swallows malformed values — `waitMillis=90s` yields the 60 s default with no diagnostic, and `Long.MAX_VALUE` reaches `toNanos()` and throws `ArithmeticException`. Log WARNING naming key and bad value; reject negatives; clamp the upper bound. |
| RT-T-02 | `loopbackIsUsableIfPresent` asserts **nothing** on macOS — `getByName("lo")` is null on Darwin (`lo0`), so it returns early and JUnit reports success. The only test touching the resolver's success path is a silent no-op on every developer Mac. Enumerate and pick the first `isLoopback()`, or use `assumeTrue` so a skip reports as a skip. |
| RT-T-03 | No test covers the behaviours the fix depends on: the interface **appearing mid-wait** (the whole of change B and field test 1), the interrupt path, or the `SocketException` path. Add them. |
| RT-T-01 | Both XML tests leak a temp file on failure — `Files.deleteIfExists` at `:68`/`:87` sits after the assertion. Move to a finally/`@TempDir`. |
| RT-Z-01 | `requireName()` (`:127`-`:129`) is dead code, zero callers. Delete it. |

### RT closed the open item from §2B — proceed

`Executor.scheduledExecutor()` is **not** single-threaded. Bytecode shows `corePoolSize = Integer.MAX_VALUE`
backed by **virtual threads**, `allowCoreThreadTimeOut(true)`. A 60 s park cannot delay discovery's retries,
and `Thread.sleep` on a virtual thread unmounts its carrier rather than pinning it. **The 60 s cap stands as
designed.** §5's "if single-threaded, stop" is resolved: go.

### RT verified clean — do not re-litigate these

Exception hierarchy (both unchecked, infinite retry holds) · double-quit idempotency (safe, so C firing on
both receive and timeout in one window is harmless) · monotonic `nanoTime` (immune to NTP steps — correct,
`currentTimeMillis` would have been a bug) · log flooding (~1.7 lines/min, field test 3 passes on this axis)
· thread-safety across multiple service containers (no mutable static state) · no undeclared dependencies ·
no interrupt-flag leak between tasks.

---

## 4. Field tests — acceptance criteria (corrected timings)

| # | Setup | Expected | Fails if |
|---|---|---|---|
| 1 | Docker host-net, `netif="end1"`, container started **before** `end1` is up | wait log, then bind, no crash loop | binds `0.0.0.0`, or exits |
| 2 | Change `end1` IPv4 while ETS is connected | WARNING old→new within **5 s**; rebound within **15 s**; ETS search shows the new address; reconnect works | silent, or >15 s, or ETS connect refused after rebind |
| 3 | Remove `end1` entirely, leave 5 min | fail logged, retry cycle ~**70 s**, process stays up, log not flooded | process exits, or log floods |

`netif=any` remains a workaround. It is **not** the product default where the site pins `end1`.

---

## 5. Open items and unverified claims

- **The three patched files do not exist anywhere reachable.** They are not on `netif_fix`, not on
  `master`, and there is no `artifacts/calimero-server-netif_fix/` on the estate machine. The branch
  contains the resolver and its test and **nothing else** — `git diff --stat eb6fcc9 netif_fix` is two
  files, 219 insertions, zero deletions, zero modifications. The "patched locally, ready to land" work is
  uncommitted on one workstation, unbacked-up, and has never been seen by any gate. **That is the single
  largest risk on this track and it is not an engineering risk.**
- `7a778ed`'s subject — *"await interface at bind, rebind on address change"* — describes behaviour the
  branch does not contain. The resolver has **zero production callers**. Nothing binds, awaits or rebinds
  differently on `netif_fix` than on `eb6fcc9`. Do not carry that claim into the delivery report.
- **Agent 2 (scoped Validator) had not returned when this revision was published.** Its section is pending.
  Treat the Docker/network conformance question — in particular whether the charter's "bind `0.0.0.0`"
  Docker rule needs a documented carve-out for host-net KNXnet/IP — as **open**, not settled.
- The fork is **28 commits behind** upstream `calimero-project/calimero-server` and carries a bespoke,
  untested `adjustHopCount()` in `KnxServerGateway.java` landed via two web-UI edits. Out of scope here,
  but a rebase would silently drop it.
