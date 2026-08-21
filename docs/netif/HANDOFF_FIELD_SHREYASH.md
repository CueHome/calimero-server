# Field test handoff — calimero-server `netif_fix`

**To:** Shreyash  
**From:** Engineering  
**Date:** 2026-08-21  
**Goal:** Prove the netif fix works on the real TP-UART host **before any merge**.

---

## Build under test (do not change)

| Item | Value |
|---|---|
| Repo | https://github.com/CueHome/calimero-server |
| Branch | `netif_fix` |
| **Commit SHA** | **`622dce9f0be9846e25843a11e81e4977ef4c9b7c`** |
| Short | `622dce9` |
| Guide (detail) | https://github.com/CueHome/calimero-server/blob/netif_fix/docs/netif/FIELD-TEST-GUIDE.md |
| Review notes | https://github.com/CueHome/calimero-server/blob/netif_fix/docs/netif/REVIEW-AND-REMAINING-WORK.md |

Confirm the container was built from **`622dce9`**. A report without this SHA is rejected.

**Note:** The STATUS GATE at the top of the old field guide is outdated. Desk code for A+B+C **is landed** on this SHA. You are testing the real fix.

---

## Rig

- Host with TP-UART and Docker **`--network host`**
- Server XML: `netif="end1"` (not `any`)
- Interface under test: **`end1`**
- ETS on the same LAN
- Log level must show the bound address (prefer **INFO/TRACE**). If you cannot see a line with the bound IP on a normal boot, **stop** — results cannot be scored.

Before any test:

```bash
ip -4 addr show end1
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
docker logs -f --timestamps <container> 2>&1 | tee netif-test-$(date +%Y%m%d-%H%M).log
```

Keep the full timestamped log for the whole session.

---

## What you are testing

Not full KNX product QA. Only:

1. No crash when `end1` is late  
2. No silent stale IP after DHCP/move  
3. Process survives when `end1` is gone, recovers when it returns  

Plus a short regression list.

---

## Test 1 — start before `end1` is up

```bash
docker stop <container>
sudo ip link set end1 down
docker start <container>
# watch ~30 s
sudo ip link set end1 up
```

| Result | Meaning |
|---|---|
| **PASS** | Wait log for `end1`; container does **not** restart-loop; after link up, binds a **real** address (not `0.0.0.0`, not `169.254.x.x`) |
| **FAIL** | Exit/restart loop **or** binds `0.0.0.0` / APIPA while looking “healthy” |

### Test 1b — APIPA trap (required)

Repeat test 1, but delay DHCP (or uplink) so Linux gives `end1` a `169.254.x.x` address before a real lease.

| **PASS** | Keeps waiting through APIPA; binds only the real lease |
| **FAIL** | Binds `169.254.x.x` and logs success |

**Always write down the bound address from the log.** Alive ≠ correct.

---

## Test 2 — address change with ETS connected

1. Server up; note current `end1` IPv4.  
2. ETS search shows that address; open a tunnel.  
3. Change address (clock starts when the **old** address is removed):

```bash
sudo ip addr flush dev end1
sudo ip addr add <new-ip>/24 dev end1
```

4. Watch log + wall clock.  
5. ETS **new search** + reconnect tunnel.  
6. **Repeat the address change a second time** (catches one-shot-only rebind bugs).

| **PASS** | WARNING old→new within **5 s**; usable again within **15 s**; ETS shows **new** address; reconnect works — **twice** |
| **FAIL** | No WARNING · rebind >15 s · ETS still shows old IP · search works but every connect refused |

Notes:

- Existing tunnel **will drop** — expected. Reconnect is the requirement.  
- Score **15 s**, not 5 s (detect ≤5 s + up to 10 s scheduler).  
- If search works but connect is always refused, say so explicitly.

---

## Test 3 — `end1` down for 5 minutes

```bash
sudo ip link set end1 down
# leave 5 minutes
sudo ip link set end1 up
```

| **PASS** | Failure logged; retry about every **~70 s**; process stays up; log not flooded; recovers when link returns **without** manual restart |
| **FAIL** | Process exits · log flood · needs restart to recover |

Measure the gap between two failure lines and write the real number.

---

## Regression (all required)

After 1–3:

| # | Check | PASS if |
|---|---|---|
| R1 | XML `netif="any"` | Starts and binds as before |
| R2 | ETS tunnel + TP-UART | Read/write group addresses to real hardware |
| R3 | Routing (if site uses it) | Multicast routing still works |
| R4 | Clean restart, `end1` already up | No wait; straight to bound |
| R5 | 30 min idle, tunnel open | **No** spurious rebind / drop |

Any regression fail **blocks merge**, even if 1–3 pass.

---

## Report template (fill every line)

```text
Tester: Shreyash
Date:
Host / site:
Image tag:
Commit SHA: (must be 622dce9…)
Config: netif=end1 | host networking: yes/no
Log file name:

Baseline end1 address:
Bound address after normal boot (from log):

TEST 1  result: PASS / FAIL / NOT RUN
  Wait line seen: yes/no
  Restart loop: yes/no
  Bound address after recovery:

TEST 1b result: PASS / FAIL / NOT RUN
  Bound during APIPA phase:
  Bound after real lease:

TEST 2  result: PASS / FAIL / NOT RUN
  Old address → new address:
  Time to WARNING (s):
  Time to usable rebind (s):
  ETS new address shown: yes/no
  Tunnel reconnect: yes/no
  Second change also OK: yes/no
  Connect refused after rebind: yes/no

TEST 3  result: PASS / FAIL / NOT RUN
  Process stayed up 5 min: yes/no
  Measured retry interval (s):
  Log flood: yes/no
  Recovered without restart: yes/no

R1 any: PASS/FAIL/NOT RUN
R2 tunnel/bus: PASS/FAIL/NOT RUN
R3 routing: PASS/FAIL/NOT RUN / N/A
R4 clean restart: PASS/FAIL/NOT RUN
R5 30 min idle: PASS/FAIL/NOT RUN

Oddities (do not polish into a pass):
Blocker for merge: yes/no — reason:
```

Attach: full timestamped log + ETS screenshots for test 2.

**Rules:** Not run ≠ pass. Odd behaviour = write it down. Merge only after all required tests are observed on this host.

**Fallback if site is blocked:** set `netif="any"` temporarily. That is **not** the fix; do not leave it on a site that must pin `end1`.
