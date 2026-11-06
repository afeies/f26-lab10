package edu.cmu.cs214.lab10.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.cmu.cs214.lab10.domain.BookingStatus;
import edu.cmu.cs214.lab10.domain.Room;
import edu.cmu.cs214.lab10.service.BookingService;
import edu.cmu.cs214.lab10.store.InMemoryBookingStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Concurrency tests that drive a chosen interleaving with {@link PausingStore}.
 */
class RaceTest {

    private static final String WEAN = "WEH-5409";
    private static final int START = 540;
    private static final int END = 600;
    private static final List<Room> ROOMS = List.of(
            new Room(WEAN, "Wean 5409", 12),
            new Room("GHC-4301", "Gates 4301", 30));

    /**
     * RES-4471. Parks customer A after its availability check has answered "free"
     * and before it writes, then runs customer B's whole booking in that window.
     */
    @Test
    void twoCustomersCannotBothConfirmTheSameSlot() throws InterruptedException {
        // The real store holds the bookings the test checks at the end.
        InMemoryBookingStore real = new InMemoryBookingStore();
        // Pause the first hasOverlap call, right after it answers "free".
        PausingStore gated = new PausingStore(real, "hasOverlap", 1);
        // Both customers share one service, which goes through the pausing store.
        BookingService service = new BookingService(gated, ROOMS);

        // Customer A starts alone, so A makes hasOverlap call 1 and gets paused.
        Thread customerA = new Thread(() -> service.book(WEAN, START, END), "customer-A");
        customerA.start();

        // Wait until A is paused between its check and its insert.
        gated.awaitPaused();

        // Customer B books the same slot and passes through, since the gate fires only once.
        Thread customerB = new Thread(() -> service.book(WEAN, START, END), "customer-B");
        customerB.start();
        // Wait for B to finish its whole booking while A is still paused.
        customerB.join(PausingStore.TIMEOUT_MILLIS);

        // Let A continue with its stale "free" answer.
        gated.release();
        // Wait for A to finish its insert.
        customerA.join(PausingStore.TIMEOUT_MILLIS);

        // Printed, not asserted: this shows whether the aim fired on this run
        // without tying the test to one schedule.
        System.out.println("gate fired=" + gated.fired() + " hasOverlap calls=" + gated.targetCallCount());

        // Count CONFIRMED bookings for this slot, reading the real store directly.
        long confirmed = real.bookingsForRoom(WEAN).stream()
                .filter(booking -> booking.status() == BookingStatus.CONFIRMED)
                .filter(booking -> booking.overlaps(START, END))
                .count();
        // The invariant: the slot must have exactly one CONFIRMED booking.
        assertEquals(1, confirmed, "CONFIRMED bookings covering " + WEAN + " " + START + ".." + END);
    }

    /**
     * RES-4471 against proposed-fix.diff. Same two customers and same invariant, but
     * A is parked after the patch's re-check has answered "free" and before it
     * writes. Aimed at the patched code: A alone makes hasOverlap calls 1 and 2.
     */
    @Test
    void twoCustomersCannotBothConfirmAfterTheRecheck() throws InterruptedException {
        InMemoryBookingStore real = new InMemoryBookingStore();
        // Pause the second hasOverlap call: A's re-check, the patch's last look.
        PausingStore gated = new PausingStore(real, "hasOverlap", 2);
        BookingService service = new BookingService(gated, ROOMS);

        // Customer A starts alone, so A makes calls 1 and 2 and gets paused on 2.
        Thread customerA = new Thread(() -> service.book(WEAN, START, END), "customer-A");
        customerA.start();

        // Wait until A is paused between its re-check and its insert.
        gated.awaitPaused();

        // Customer B makes calls 3 and 4, both "free", and inserts while A is paused.
        Thread customerB = new Thread(() -> service.book(WEAN, START, END), "customer-B");
        customerB.start();
        customerB.join(PausingStore.TIMEOUT_MILLIS);

        // Let A continue with its stale re-check answer.
        gated.release();
        customerA.join(PausingStore.TIMEOUT_MILLIS);

        System.out.println("gate fired=" + gated.fired() + " hasOverlap calls=" + gated.targetCallCount());

        long confirmed = real.bookingsForRoom(WEAN).stream()
                .filter(booking -> booking.status() == BookingStatus.CONFIRMED)
                .filter(booking -> booking.overlaps(START, END))
                .count();
        assertEquals(1, confirmed, "CONFIRMED bookings covering " + WEAN + " " + START + ".." + END);
    }

    // ------------------------------------------------------------------
    // Wiring example 1: wrap the store
    //
    // PausingStore is a BookingStore that wraps another BookingStore. Build the
    // real store, wrap it, and hand the wrapper to the service. The service
    // cannot tell the difference.
    //
    //   InMemoryBookingStore real = new InMemoryBookingStore();
    //   PausingStore gated = new PausingStore(real, "bookingsForRoom", 1);
    //   BookingService service = new BookingService(gated, ROOMS);
    //
    // (ROOMS is the room list, as in BookingServiceTest.)
    //
    // Assert against `real`, not `gated`: you want the end state of the store,
    // and reading through the wrapper counts as a call.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 2: aim the gate
    //
    // The two constructor arguments after the delegate are a method name and a
    // call index counting from 1 across all threads:
    //
    //   new PausingStore(real, "bookingsForRoom", 1)   // park call 1
    //   new PausingStore(real, "insert", 3)            // park call 3 of insert
    //
    // Valid method names are the four on BookingStore: hasOverlap, insert,
    // bookingsForRoom, cancelAndPromote. On the chosen call the wrapper
    // delegates, then parks the calling thread before returning the answer it
    // already computed. The gate fires once and once only.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 3: the two-thread pattern
    //
    // Every wait below is bounded, so this pattern cannot hang no matter what
    // the code under test does. Do not add a sleep to it; a sleep is a guess,
    // and the latches are not. (Your test method needs
    // `throws InterruptedException` for the joins.)
    //
    // The scenario here is deliberately harmless: it parks a reader in
    // bookingsForRoom (the aim from example 1) while a writer books a
    // different room. It exists to show the choreography, not a bug.
    //
    //   Thread reader = new Thread(() -> service.listBookings("WEH-5409"));
    //   reader.start();
    //
    //   gated.awaitPaused();          // returns when a thread is parked, or
    //                                 // false after PausingStore.TIMEOUT_MILLIS
    //                                 // if the gated call never happened
    //
    //   Thread writer = new Thread(() -> service.book("GHC-4301", 720, 780));
    //   writer.start();
    //   writer.join(PausingStore.TIMEOUT_MILLIS);
    //
    //   gated.release();              // the parked reader returns and finishes
    //   reader.join(PausingStore.TIMEOUT_MILLIS);
    //                                 // note the writer needed no release: it
    //                                 // was never blocked by the parked reader
    //
    // Then assert on the end state, never on the order things happened in. For
    // the harmless scenario above, the writer's booking is stored whatever the
    // reader saw:
    //
    //   assertEquals(1, real.bookingsForRoom("GHC-4301").size());
    //
    // An end-state assertion still means something after you change the code
    // underneath it. A schedule assertion does not.
    // ------------------------------------------------------------------
}
