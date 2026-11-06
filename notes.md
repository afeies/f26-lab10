# Milestone 1

## The bug

Two people can both get a confirmation for the same room at the same time.

`BookingService.book` does two separate store calls:

1. `hasOverlap`: is the slot free?
2. `insert`: save the booking.

Each call is safe by itself, but nothing stops another customer from getting in
between them. So two customers can both hear "free" and both save a booking.

## The suspect table

I listed everything that touches shared state and gave each a verdict.

| What | Verdict |
|---|---|
| `book` (`hasOverlap` then `insert`) | **The bug.** No lock covers both calls. |
| `InMemoryBookingStore` list | Safe. Each single call is locked. |
| `cancelAndPromote` | Safe by itself, and not the ticket. It could still break a `book` caught in the gap (not tested). |
| `BookingStats` | A real race, but accepted. It only feeds the dashboard. |
| `sequence` / `nextId` | Safe. Ids are unique. |
| `catalog` | Safe. Never changes after the constructor. |
| `joinWaitlist` | Can't break the rule. A waitlist entry holds nothing. |
| `bookingsForRoom` / `listBookings` | Safe. Returns a copy. |
| `cancelBooking` stats and log | Can count a cancel that didn't happen. Doesn't affect bookings. |
| `BookingLog` | Safe. Holds no booking state. |

**How I picked the bug:** the rule is at most one CONFIRMED booking per room per
minute, so I looked at what can create a CONFIRMED booking. Only `book` and
`cancelAndPromote` can. The ticket's log lines say `booking.confirmed`, and only
`book` logs that.

## The race test

The test in `RaceTest.java` forces the bad timing every time:

1. Customer A calls `book`. `PausingStore` pauses A right after `hasOverlap` says
   "free" (aim: `hasOverlap`, call 1).
2. Customer B books the same slot. B also hears "free", saves `bk-1`, and finishes.
3. A is released. It still believes "free", so it saves `bk-2`.
4. The test checks that the room has exactly 1 confirmed booking.

## How to run the test

From the folder with `pom.xml`, run just the race test:

```
mvn -B test -Dtest=RaceTest
```

Or run the full suite, including the race test:

```
mvn -B test
```

## The result

The test failed on both runs with `expected: <1> but was: <2>`, as I predicted.
That failure is the bug showing up, so it should stay red until the fix in
milestone 3.

# Milestone 2

A teammate's patch checks if the slot is free a second time before saving.

- My milestone 1 test passed, but it pauses A before the second check, so it never
  tests the gap that's left.
- I added a test that pauses A right after the second check. B books the slot, then
  A saves too. That gives 2 confirmed bookings, every run.

**Verdict: reject.** The gap between the last check and the save is still there. The
patch only makes the bug rarer. I undid the patch.

To run the new test, apply the patch first (`git apply proposed-fix.diff`), then:

```
mvn -B test -Dtest='RaceTest#twoCustomersCannotBothConfirmAfterTheRecheck'
```

Undo the patch afterward with `git apply -R proposed-fix.diff`.

# Milestone 3