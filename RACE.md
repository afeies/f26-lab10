# RACE.md

One section per milestone. Fill each one in order as you go.

The prediction in milestone 1 is written BEFORE you run your race test. A TA
may ask about it. Commit it on its own so the order is visible.

Keep it short and specific. Point at methods, call sites, and the invariant.

---

## Milestone 1: from the ticket to a test that fails every time

### Suspect list

Everything your review turned up that touches shared state, with a one-line
verdict for each. Add or remove rows as needed.

| # | What you found (method, field, or class) | Verdict, in one line |
|---|---|---|
| 1 | `BookingService.book`: `hasOverlap` (line 70) then `insert` (line 74) | **The ticket's bug.** Atomicity violation: each store call is atomic, but no lock spans the check and the write, so the write can act on a stale answer. |
| 2 | `InMemoryBookingStore.bookings`, all methods `synchronized` | Correctly guarded and never escapes; every single call is atomic, but that is exactly what the `BookingService` javadoc wrongly assumes is enough for #1. |
| 3 | `InMemoryBookingStore.cancelAndPromote` / `overlapsConfirmed` | The only other CONFIRMED writer; its check and write share one lock, so it is safe by itself and not the ticket (it logs `booking.promoted`), though by reading it can still invalidate the check of a `book` inside the #1 window. |
| 4 | `BookingStats` plain `long` counters | A real data race (lost updates), but documented, accepted, and read by no decision; the ticket's bad state is in the booking data, not the dashboard. |
| 5 | `BookingService.sequence` / `nextId` | Safe: `AtomicLong` gives unique ids, and the ticket's pair has different ids (bk-8412, bk-8413). |
| 6 | `BookingService.catalog` (`HashMap`) | Safe: a `final` field filled only in the constructor and read-only afterward; `rooms()` returns a copy. |
| 7 | `joinWaitlist` | Cannot break the invariant: one store call, and a WAITLISTED entry holds nothing; a client's reject-then-join can miss a cancel and leave it waiting on a free slot, which is a liveness gap only. |
| 8 | `bookingsForRoom` / `listBookings` | Safe: copied under the lock into an immutable list of immutable records; it can go stale, but no decision is made on it. |
| 9 | `cancelBooking` stats and log calls | Outside the store lock and unconditional, so an unknown or repeat cancel still counts and logs as cancelled; wrong telemetry, not booking state. |
| 10 | `BookingLog` | Safe: immutable formatter and `println`; lines are written after the store call, so their order may not match store order, but they hold no booking state. |

**Which one explains the ticket, and how you know.** It is #1. The invariant is
"a room has at most one CONFIRMED booking covering any given minute," so the
only suspects that matter are the code paths that can create a CONFIRMED
booking. There are two: `cancelAndPromote` and `book`. `cancelAndPromote` checks
and writes under one lock and logs `booking.promoted`. The ticket's lines are
both `booking.confirmed`, which only `book` emits, and neither booking was
waitlisted or cancelled. `book` checks in one lock acquisition and writes in
another, so two callers can both see "free" and both insert. That also fits the
evidence: same room and range, consecutive ids, 1 ms apart, no exception, and no
rejection between them. The stats race (#4) is real but cannot put a row in the
store. Not yet the proof, just supporting evidence: on the unmodified code,
`StressRunner 2000` ended 46, 18, and 19 rounds with 2 confirmed bookings in
three runs, while single-threaded tests stay green because they never enter the
window.

### Predicted interleaving (write this before you run your race test)

Two threads, one row per step, in the form from lecture. Describe what each
thread does at that step and leave the other column empty. Add or remove rows
as your prediction needs.

| Step | Thread A | Thread B |
|---|---|---|
| t1 | `book(WEH-5409, 540, 600)` calls `hasOverlap` (call 1): the store is empty, so it computes `false`. The store lock is released, and `PausingStore` parks A before returning. | |
| t2 | | `book(WEH-5409, 540, 600)` calls `hasOverlap` (call 2, not gated): the store is still empty, so `false`. |
| t3 | | `nextId()` gives bk-1; `insert` stores it CONFIRMED; logs `booking.confirmed booking=bk-1`; returns CONFIRMED. |
| t4 | Released, A returns its stale `false`. `nextId()` gives bk-2; `insert` stores it CONFIRMED without looking again; logs `booking.confirmed booking=bk-2`; returns CONFIRMED. | |
| | **End state:** the store has 2 CONFIRMED bookings for WEH-5409 [540, 600), bk-1 and bk-2, and no rejection is logged. Asserting "exactly 1 confirmed" fails with expected 1, actual 2, on every run. | |

If the run turned out different from your prediction, add one line on what
you missed. Leave the table as you first wrote it.

### Your race test

**How you aimed `PausingStore`, and the result.** Aim: `hasOverlap`, call 1.

Why that pair pins the schedule: A starts alone, so it must make call 1, and the
gate parks it after the real store has answered `false` and released its lock,
but before `book` can use that answer. That parks A at exactly the point between
the check and the write. `awaitPaused()` then guarantees B starts only once A is
there, and B's `hasOverlap` is call 2, so the one-shot gate lets it straight
through to finish while A waits. No sleeps or lucky timing are involved. By
reasoning (not run), `insert`, 1 would park A after its row is stored, so B would
be correctly rejected and the test would pass without exercising the race.

The run matched the prediction: bk-1 (B) was logged before bk-2 (A), neither was
rejected, and the end state was 2 CONFIRMED. `gate fired=true hasOverlap calls=2`
is a line the test prints to show the gate fired and each customer checked once.
The runs took about 0.03 s, so no bounded wait timed out.

Run 1 (`mvn -B test -Dtest=RaceTest`):

```
2026-11-06T14:22:13.598Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T14:22:13.601Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
gate fired=true hasOverlap calls=2
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.044 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.twoCustomersCannotBothConfirmTheSameSlot -- Time elapsed: 0.031 s <<< FAILURE!
[ERROR]   RaceTest.twoCustomersCannotBothConfirmTheSameSlot:54 CONFIRMED bookings covering WEH-5409 540..600 ==> expected: <1> but was: <2>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Run 2 (`mvn -B test -Dtest=RaceTest`, a separate JVM):

```
2026-11-06T14:22:15.424Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T14:22:15.427Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
gate fired=true hasOverlap calls=2
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.045 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.twoCustomersCannotBothConfirmTheSameSlot -- Time elapsed: 0.030 s <<< FAILURE!
[ERROR]   RaceTest.twoCustomersCannotBothConfirmTheSameSlot:54 CONFIRMED bookings covering WEH-5409 540..600 ==> expected: <1> but was: <2>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

---

## Milestone 2: the patch a teammate sent

**Your milestone-1 test on the patched code.** Green, on both runs. The full
suite was green too (12 tests). `calls=4` is A's check, B's check, B's re-check,
and A's re-check.

```
2026-11-06T14:35:35.852Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T14:35:35.860Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
gate fired=true hasOverlap calls=4
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.064 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] BUILD SUCCESS
```

**The re-aimed run.** `twoCustomersCannotBothConfirmAfterTheRecheck`, aim:
`hasOverlap`, call 2. It failed the same way on both runs (`mvn -B test
-Dtest='RaceTest#twoCustomersCannotBothConfirmAfterTheRecheck'`, separate JVMs).

Why this aim tests the patch rather than sidestepping it: A starts alone, so call
1 is its first check and call 2 is the patch's own re-check. The gate parks A after
the re-check answers "free" and before `insert`, which is the window the patch's
comment says no longer exists. B then makes calls 3 and 4, both "free", and
inserts. A wakes up and inserts on its stale answer. A third check would leave the
same gap, because any check that is not under one lock with the write can go stale.

| Step | Thread A | Thread B |
|---|---|---|
| t1 | `hasOverlap` (call 1): `false`. `nextId()` gives bk-1. | |
| t2 | Re-check `hasOverlap` (call 2): `false`. Parked before returning. | |
| t3 | | `hasOverlap` (call 3): `false`. `nextId()` gives bk-2. Re-check (call 4): `false`. |
| t4 | | `insert` bk-2, CONFIRMED. Logged first. |
| t5 | Released with the stale `false`. `insert` bk-1, CONFIRMED. | |

Run 1:

```
2026-11-06T14:35:46.786Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
2026-11-06T14:35:46.789Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
gate fired=true hasOverlap calls=4
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.043 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR]   RaceTest.twoCustomersCannotBothConfirmAfterTheRecheck:102 CONFIRMED bookings covering WEH-5409 540..600 ==> expected: <1> but was: <2>
[INFO] BUILD FAILURE
```

Run 2:

```
2026-11-06T14:35:48.640Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
2026-11-06T14:35:48.644Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
gate fired=true hasOverlap calls=4
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.048 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR]   RaceTest.twoCustomersCannotBothConfirmAfterTheRecheck:102 CONFIRMED bookings covering WEH-5409 540..600 ==> expected: <1> but was: <2>
[INFO] BUILD FAILURE
```

**Your verdict.** **Reject.** On the patched code, the re-aimed test ends with 2
CONFIRMED bookings for WEH-5409 [540, 600) on every run, which breaks the same
invariant RES-4471 reports. The patch adds a second `hasOverlap` but still has no
lock spanning the last check and the `insert`, so the check-then-act gap is moved,
not closed. The patch's "no window left" comment is false.

What the milestone-1 result shows: the patch handles one schedule, where B's whole
booking lands between A's first check and A's re-check. A's re-check sees B's row.

What it does not show: that the race is gone. That test parks A before the re-check,
so it never exercises the gap that remains. Its green result is true but only covers
that one schedule. The teammate's evidence has the same limit: the suite is
single-threaded, and "no bad rounds" depends on timing. `StressRunner 2000` on the
patched code gave 0, 0, then 1 bad rounds in three runs (`round 81: 2 confirmed
bookings for WEH-5409 540..600`), against 46, 18, and 19 on the unpatched code. So
the patch makes the bug rarer and harder to catch, without removing it.

The patch is undone (`git apply -R proposed-fix.diff`). Milestone 3 starts from the
original `BookingService`. The re-aimed test's aim only means something with the
patch applied: on the original code A makes call 1 and finishes without parking,
`awaitPaused()` times out, and B is parked on call 2 with a correct `true`, so the
test passes after about 4 s without exercising a race. Its green result there says
nothing about the original code, which the milestone-1 test still fails (expected
1, actual 2).

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** The atomic store method:
`BookingStore.insertIfFree(Booking)`. In `InMemoryBookingStore` it is one
`synchronized` method on the store's monitor, covering the overlap check and the
`bookings.add` write. `BookingService.book` now makes that one call in place of
`hasOverlap` then `insert`.

Why not a lock in the service: the same store can back several `BookingService`
instances (see the constructor's javadoc), and each would have its own lock. A
service lock would also not cover `cancelAndPromote`, which can confirm a booking
under the store's lock between a service-level check and write. The store's monitor
already guards every booking write, so it covers both.

As predicted, adding the method to the interface broke the build until `PausingStore`
passed it through: `PausingStore is not abstract and does not override abstract
method insertIfFree`.

The diff, with the javadoc-only hunks left out (`BookingService` class comment,
`BookingStore.insertIfFree` doc):

```diff
--- a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
+++ b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
@@ public BookingResult book(String roomId, int startMinute, int endMinute)
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
+        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
+        if (!store.insertIfFree(booking)) {
             return reject(roomId, startMinute, endMinute, "slot already held");
         }
-        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
-        store.insert(booking);
         stats.recordConfirmed();

--- a/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
+    boolean insertIfFree(Booking booking);

--- a/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
+    @Override
+    public synchronized boolean insertIfFree(Booking booking) {
+        // The check and the write hold the same lock, so nothing can take the
+        // slot in between, including a promotion in cancelAndPromote.
+        if (overlapsConfirmed(booking)) {
+            return false;
+        }
+        bookings.add(booking);
+        return true;
+    }

--- a/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
+    @Override
+    public boolean insertIfFree(Booking booking) {
+        return delegate.insertIfFree(booking);
+    }
```

Milestone-1 test (`mvn -B clean test -Dtest='RaceTest#twoCustomersCannotBothConfirmTheSameSlot'`).
A second run gave the same result:

```
2026-11-06T14:43:17.919Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T14:43:19.922Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
gate fired=false hasOverlap calls=0
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.051 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] BUILD SUCCESS
```

Full suite (`mvn -B clean test`), booking log lines trimmed:

```
gate fired=false hasOverlap calls=0
gate fired=false hasOverlap calls=0
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.050 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.014 s -- in edu.cmu.cs214.lab10.service.BookingServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s -- in edu.cmu.cs214.lab10.domain.BookingTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Is your milestone-1 test still testing anything?** Less than before. No, `PausingStore`
did not pause a call during the green run, and we know from the output:
`gate fired=false hasOverlap calls=0`. The 2 s elapsed time is `awaitPaused()` running
out its timeout. So the run is now sequential: A books and finishes alone, the wait
times out, then B is rejected by a slot that is already held. It no longer forces two
threads into a window, because in the fixed `book` path there is no store-level
window to park in. `insertIfFree` passes straight through the wrapper, and the gap
is inside one lock.

I did not change the test, because its end-state assertion is still right. What it
still does: it guards against regression. If `book` goes back to `hasOverlap` then
`insert`, the gate fires again and the test goes red, as it did on the original
code in milestone 2 (expected 1, actual 2). The same applies to the re-aimed test,
which also reports `fired=false`.

What it does not do: prove the fix under concurrency. That rests on the store's
monitor covering the check and the write, which is reasoning from the code plus the
removal of the window. `StressRunner 2000` gave 0, 0, 0 bad rounds, which is
supporting evidence only, since the patched code also had clean runs.

**What runs one at a time now that ran in parallel before.** Cost: every `book` now
holds the store's one monitor for its whole check and write, and everything else on
that store queues behind it. That includes bookings for **other rooms**: booking
GHC-4301 waits for a WEH-5409 booking's check and write to finish. So do
`cancelAndPromote`, `bookingsForRoom`, `joinWaitlist`'s `insert`, and callers from
any other service instance on the same store.

Before, a booking's check and write were two separate lock holds, and all of those
callers could run between them. Now they cannot. Each individual store call already
ran one at a time, so the new serialization is that gap between the two steps. The
hold is a scan of the whole list (every room), so it grows with total bookings, not
with the room's. A per-room lock would let different rooms proceed in parallel, but
I did not do that. Stats and logging stay outside the lock. I did not measure the
cost in time.

**What you left unfixed.**

- `BookingStats` lost updates: documented and accepted, and no decision reads them.
- `cancelBooking` stats and log on unknown or repeat cancels: wrong telemetry, not booking state, and outside this ticket.
- `joinWaitlist` after a rejection can miss a cancel and wait on a free slot: a liveness gap that cannot break the invariant, and fixing it is a behavior decision for the waitlist.
- `BookingLog` order may not match store order: lines are written after the store call and hold no state.
- Rejected bookings now use up an id, since `nextId()` runs before `insertIfFree`: ids stay unique, and only gaps appear.
- `hasOverlap` and `insert` stay public on `BookingStore`: `joinWaitlist` needs `insert` and `hasOverlap` is part of the interface, so a future caller could rebuild this race. The javadoc on `insertIfFree` warns against it.
