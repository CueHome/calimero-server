# `netif_fix` — field test guide

**Date:** 2026-08-21 · **Build under test:** branch `netif_fix`
**Rig:** the TP-UART host, Docker **host network** mode, server XML with `netif="end1"`

> **STATUS GATE — read before you schedule a rig.**
> This handoff is **staged, not open.** Not yet landed: `Launcher.java`,
> `ControlEndpointService.java`, `KNXnetIPServer.java` or the `RoutingService.java:92` deletion.
> Until the delivery report shows **`NetworkInterfaceResolverTest` 6/6 green**, testing test 1
> will simply reproduce today's crash loop and tell you nothing.
> **Do not start until the branch is confirmed code-complete.**
>
> Gates run 2026-08-21 against what exists — **all three failed**: red team **ISSUES FOUND**
> (1 CRITICAL, 5 HIGH, 7 WARNING) · licence **BLOCKED** · conformance **FAIL** (5 CRITICAL, 3 HIGH,
> 4 WARNING) with `APPROVED FOR HUMAN TESTING: NO`. All must clear, and all three must be re-run after the
> remaining code lands, before this handoff opens.
>
> There is also **no deployment documentation in the repo** — no Dockerfile, no compose file, and the
> shipped sample config uses `netif="any"`, the opposite of the site setting. That has to be written before
> a rig is worth booking.

---

## 1. What changed and why you are testing it

The server is pinned to a named NIC, `end1`. Two field faults:

- **Crash loop.** The container starts before `end1` has an address. The server resolved the name while
  reading its XML config, found nothing, threw, and the JVM exited — before the server loop ever ran.
  Docker restarted it, and it did the same thing, forever.
- **Silent stale address.** DHCP moved the host from `192.168.0.221` to `192.168.1.73`. The server had
  baked the old address into its KNXnet/IP HPAI at bind time and kept advertising it. ETS kept seeing
  `.221`. Nothing in the log said otherwise. Only a process restart fixed it.

The fix waits for the interface at bind instead of resolving at parse, and re-checks the address every
5 s so a DHCP move rebuilds the endpoint on its own.

**You are testing the recovery behaviour, not KNX telegram function.** Normal bus operation is regression
scope (§6), not the point of the exercise.

---

## 2. Before you start — capture the baseline

```bash
ip -4 addr show end1
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
```

Record the image tag and the commit SHA the container was built from. A test result without the SHA is
not evidence and will be rejected.

Follow the log in a second terminal for the whole session and keep the full capture:

```bash
docker logs -f --timestamps <container> 2>&1 | tee netif-test-$(date +%Y%m%d-%H%M).log
```

**Timestamps are mandatory** (`--timestamps`). Two of the three tests are scored on elapsed time, and a
log without timestamps cannot be scored.

> **You must raise the log level, or these tests cannot be scored at all.**
> The line that reports the **bound address** is logged at `TRACE`, but the shipped
> `resources/simplelogger.properties` sets `defaultLogLevel=info`. At the default level the only thing you
> see is `… is up and running` — which prints happily for a wrong or wildcard bind.
>
> Worse: on a `0.0.0.0` bind the interface-name prefix **silently disappears** from that line, because the
> reverse lookup on the wildcard address returns nothing. So the failure case looks like a slightly shorter
> success line. Nothing else distinguishes them.
>
> Set `-Dorg.slf4j.simpleLogger.defaultLogLevel=trace` (or edit `simplelogger.properties`) before you start,
> and confirm you can actually see a `control endpoint bound to …` line on a known-good boot. If you cannot
> see that line, stop — you have no instrument, and every result below is unscoreable.
>
> Promoting that line to `INFO` is on the fix list. Until it lands, this workaround is mandatory.

---

## 3. Test 1 — cold start before the interface is up

**Why:** this is the crash loop. It is the reason the site was down.

```bash
docker stop <container>
sudo ip link set end1 down
docker start <container>
# watch the log for ~30 s, then:
sudo ip link set end1 up
```

| | |
|---|---|
| **PASS** | Log shows a wait line naming `end1` while the link is down. Container stays up — `docker ps` shows no restart count climbing. When the link comes up, the server binds and logs the bound address. |
| **FAIL** | Container exits or restart-loops · **or** the server binds `0.0.0.0` and reports success while `end1` is down. |

### 1b. The APIPA sub-case — Red Team says this is the one most likely to bite

Repeat test 1, but **delay the DHCP server** (or unplug the uplink) so that when `end1` comes up Linux
assigns itself an APIPA address, `169.254.x.x`, before the real lease arrives.

| | |
|---|---|
| **PASS** | The server keeps waiting through the `169.254.x.x` phase and binds only once the real lease lands. |
| **FAIL** | The server binds `169.254.x.x`, logs success, and ETS cannot reach it. |

This is the most probable real-world failure of the whole change and it looks completely healthy in the log.
**Record the bound address, not just "it started."**

> The `0.0.0.0` case is the dangerous one and it looks like a pass in a hurry. **Check the bound address
> in the log, not just that the process is alive.** A server bound to `0.0.0.0` will advertise an
> unusable address to ETS and appear healthy in every other respect.

---

## 4. Test 2 — DHCP address change while ETS is connected

**Why:** this is the silent-staleness fault. Do this one with ETS actually connected — a disconnected
test will not exercise the path that failed in the field.

1. Confirm the server is up and bound to the current `end1` address.
2. From ETS, run a search — confirm the server appears at the **current** address — and open a tunnel.
3. Change the address:

```bash
sudo ip addr flush dev end1
sudo ip addr add 192.168.1.73/24 dev end1     # substitute the rig's target subnet
```

4. Watch the log and the wall clock.
5. In ETS, run a **fresh search** and re-open the tunnel.

| | |
|---|---|
| **PASS** | A WARNING naming the old → new address appears within **5 s**. The endpoint is rebound within **15 s** of the change. A fresh ETS search shows the **new** address. Reconnecting the tunnel works. |
| **FAIL** | No log line · **or** rebind takes longer than 15 s · **or** ETS search still shows the old address after rebind · **or** ETS can search but every connect attempt is refused. |

> **Start the clock at old-address REMOVAL, not at new-address assignment.** Linux DHCP clients routinely
> hold both leases for a beat, and during that window the server correctly sees its address still present.
> Timing from assignment will make a correct fix look slow.
>
> **Then do it a second time.** Change the address again and confirm a **second** rebind. One rebind proves
> little — a specific internal mistake (cancelling the scheduled rebuild instead of closing the endpoint)
> gives you exactly one successful rebind and then permanent silence, with a log line that reads like a
> clean shutdown. Only the second change catches it.
>
> **Score against 15 s, not 5 s.** Detection is ≤5 s, but the rebuild is driven by a scheduled task on a
> fixed 10 s cycle, so a correct fix lands anywhere in the 5-15 s window. An earlier draft of this plan
> said "~5 s" — that number was wrong and would have failed a working build.
>
> **The existing tunnel WILL drop.** That is designed, not a defect — a UDP socket bound to a
> now-nonexistent address cannot be moved in place. Reconnection is the requirement; survival is not.
>
> **The refused-connect failure mode is specific and worth watching for.** If the server rebinds and ETS
> can find it but every connect is refused, say so explicitly in the report — it points at a known
> internal hazard (a shut-down endpoint being reused instead of rebuilt) and the reviewer needs to hear it.

---

## 5. Test 3 — interface removed and left removed

**Why:** proves the server degrades instead of dying, and that it does not flood the log while waiting.

```bash
sudo ip link set end1 down
# leave it down a full 5 minutes, keep the log running
sudo ip link set end1 up
```

| | |
|---|---|
| **PASS** | A failure is logged after the wait expires, then a retry cycle of roughly **70 s**. Process stays up for the full 5 minutes. Log is readable — a handful of lines, not a wall. When the link returns, the server binds and recovers with no manual restart. |
| **FAIL** | Process exits · **or** the log floods (hundreds of lines) · **or** it never recovers when the link returns and needs a restart. |

> **~70 s, not 10 s.** The wait blocks inside the retry task, so the cycle is the wait plus the retry
> delay. Count the gap between two consecutive failure lines and report the actual number you measure.

---

## 6. Regression — must still work

Run these after 1-3. Any failure here blocks merge regardless of how 1-3 went.

- [ ] `netif="any"` in the XML still starts and binds exactly as before the change.
- [ ] Normal ETS tunnel: read and write group addresses across the TP-UART to real hardware.
- [ ] KNXnet/IP **routing** (multicast), if the site uses it.
- [ ] A clean `docker restart` with `end1` already up — no wait, no warning, straight to bound.
- [ ] Server survives 30 min idle with a tunnel open and does **not** spuriously rebind.

That last one matters: the address check now runs on **every receive** as well as on idle timeout. A bug
there would show up as the server rebinding under normal traffic and dropping tunnels for no reason.

---

## 7. What to send back

For each test: the timestamped log extract, the measured elapsed time, the ETS screenshot where relevant,
and the commit SHA. Report what you observed, including anything odd you cannot explain — do not smooth it
into a pass. A test you could not run is reported as **not run**, never as a pass.

Send to the reviewer. **Merge to `main` happens only after these are observed on the TP-UART host** — project owner decision, 2026-08-21.

---

## 8. Known-unverified going in

Stated so nobody discovers these mid-test and assumes the rig is broken:

- Nothing in tests 1-3 has ever been observed on hardware. The mechanism is verified by source reading
  only.
- `netif=any` is the standing workaround. If a test blocks the site, set it and move on — but it is
  **not** the fix and must not be left in place on a site that pins `end1`.
