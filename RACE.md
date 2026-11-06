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

**How you aimed `PausingStore`, and the result.** Method name and call index, why
that pair pins the schedule you predicted, and the output of two separate
runs (assertion failure and log lines).

---

## Milestone 2: the patch a teammate sent

**Your milestone-1 test on the patched code.** Green or red. Paste the result.

**The re-aimed run.** Method name, call index, the pasted result, and one line
on why that aim tests the patch rather than sidestepping it.

**Your verdict.** Accept or reject, and what your milestone-1 result does and
does not show about the patch.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Name the lock or the atomic operation
and the two operations it now covers. Paste the diff of your change and the
passing run of your milestone-1 test and the full suite.

**Is your milestone-1 test still testing anything?** After your fix, describe
what that test now exercises. Did `PausingStore` pause a call during the green run? How do
you know?

**What runs one at a time now that ran in parallel before.** Name concretely which callers
queue behind which. "Nothing, it is just correct now" does not count as an
answer.

**What you left unfixed.** For anything your review flagged that you chose not
to fix, one line each on why.
