package com.zucham.qbsmarter.domain.driver.protocol

import com.zucham.qbsmarter.domain.driver.SmartCubeEvent

/**
 * Ordered move buffer with serial-gap detection and backfill, shared by
 * every protocol whose cube numbers its moves with a rolling 8-bit
 * serial (GAN Gen3 and Gen4 today).
 *
 * Those cubes stream moves as they happen, but BLE notifications get
 * dropped. Each move carries a serial, so a gap is detectable — and
 * recoverable, because the cube will retransmit a window of history on
 * request. This class is the machinery for that: moves go in as they
 * arrive, come out strictly in serial order, and a gap stalls the queue
 * until the missing moves are backfilled or the buffer overflows.
 *
 * Previously this existed twice, copied verbatim into the Gen3 and Gen4
 * parsers with a comment explaining that factoring it out would "obscure
 * the per-generation differences". There turned out to be no
 * per-generation differences: only the wire offsets that produce a
 * `Move` differ, and those stay in the parsers. Two copies of subtle
 * circular-arithmetic code is exactly the thing worth having once.
 *
 * Not thread-safe; the driver touches a protocol from one coroutine.
 *
 * @param overflowLimit how many moves may pile up behind a gap before we
 *   give up on backfill and ask for a full state resync instead.
 */
class MoveRecoveryFifo(
    private val overflowLimit: Int = DEFAULT_OVERFLOW_LIMIT,
) {

    /**
     * Highest serial the cube has mentioned, from any packet type
     * (including a state snapshot). May run ahead of [lastSerial] when
     * notifications were lost.
     */
    var serial: Int = -1

    /** Serial of the most recent move actually emitted upstream. */
    var lastSerial: Int = -1

    /**
     * Device time of the last live move packet, used to debounce
     * periodic state snapshots: inside that window the cube is probably
     * still streaming moves we haven't processed, and asking for history
     * would request moves already in flight.
     */
    var lastLocalTimestamp: Long = 0

    private val moves: ArrayDeque<SmartCubeEvent.Move> = ArrayDeque()
    private val serials: ArrayDeque<Int> = ArrayDeque()

    /** Queue a freshly-received move and record its serial. */
    fun push(move: SmartCubeEvent.Move, moveSerial: Int) {
        moves.addLast(move)
        serials.addLast(moveSerial)
        serial = moveSerial
    }

    /**
     * Emit every move that is contiguous with what we've already
     * delivered, stopping at the first gap.
     *
     * On hitting a gap, asks [requestHistory] for the missing window
     * (when [allowHistoryRequest]) and stops — order matters more than
     * latency, since delivering moves out of order corrupts the cube
     * state far worse than a few milliseconds of delay.
     *
     * @param allowHistoryRequest false while handling a history reply,
     *   so backfill can't recursively request more backfill.
     * @return moves to emit, plus a [SmartCubeEvent.MovesMissed] if the
     *   buffer overflowed.
     */
    suspend fun drain(
        allowHistoryRequest: Boolean,
        ts: Long,
        requestHistory: suspend (startSerial: Int, count: Int) -> Unit,
    ): List<SmartCubeEvent> {
        val emitted = mutableListOf<SmartCubeEvent>()
        while (moves.isNotEmpty()) {
            val headSerial = serials.first()
            val diff = if (lastSerial == -1) 1 else ((headSerial - lastSerial) and 0xFF)
            if (diff > 1) {
                if (allowHistoryRequest) requestHistory(headSerial, diff)
                break
            }
            emitted += moves.removeFirst()
            serials.removeFirst()
            lastSerial = headSerial
        }
        if (moves.size > overflowLimit) {
            // Backfill isn't catching up. Surface MovesMissed so the
            // orchestrator can do a full state resync, and drop the
            // backlog — the resync resets the serial baseline anyway, so
            // holding it would only leak memory.
            val missed = moves.size
            moves.clear()
            serials.clear()
            emitted += SmartCubeEvent.MovesMissed(missedCount = missed, deviceTimestamp = ts)
        }
        return emitted
    }

    /**
     * Insert a move recovered from a history reply.
     *
     * History arrives newest-first, so a recovered move is only ever
     * prepended, and only when its serial is exactly one below the
     * current head — anything else is a duplicate or outside the gap
     * we're filling, and gets dropped rather than corrupting the order.
     */
    fun injectRecovered(move: SmartCubeEvent.Move, serialOfMove: Int) {
        if (moves.isNotEmpty()) {
            if (serials.contains(serialOfMove)) return
            val headSerial = serials.first()
            if (!isSerialInRange(lastSerial, headSerial, serialOfMove)) return
            if (serialOfMove == ((headSerial - 1) and 0xFF)) {
                moves.addFirst(move)
                serials.addFirst(serialOfMove)
            }
        } else {
            // Empty buffer: this is recovery driven by a periodic state
            // snapshot rather than by a live gap. Validate against
            // (lastSerial, serial] with serial as the newest thing known.
            if (isSerialInRange(lastSerial, serial, serialOfMove, closedEnd = true)) {
                moves.addFirst(move)
                serials.addFirst(serialOfMove)
            }
        }
    }

    /**
     * Ask for history if the cube's reported [serial] has run ahead of
     * what we've emitted. Called when a state snapshot reveals we missed
     * moves that never arrived as notifications at all.
     */
    suspend fun requestMissedIfBehind(
        requestHistory: suspend (startSerial: Int, count: Int) -> Unit,
    ) {
        val diff = (serial - lastSerial) and 0xFF
        if (diff <= 0) return
        // Firmware quirk: a state event carrying serial 0 (the wrap
        // point) can't be trusted to mean "move 0 happened", and acting
        // on it replays a bogus move.
        if (serial == 0) return
        val headSerial = serials.firstOrNull() ?: ((serial + 1) and 0xFF)
        requestHistory(headSerial, diff + 1)
    }

    /**
     * Whether [candidate] lies in the circular range (start, end) of
     * 8-bit serials, open at both ends unless told otherwise.
     *
     * Circular because serials wrap at 256: the range 250 → 5 is valid
     * and contains 251..255 and 0..4.
     */
    private fun isSerialInRange(
        start: Int,
        end: Int,
        candidate: Int,
        closedStart: Boolean = false,
        closedEnd: Boolean = false,
    ): Boolean {
        val totalSpan = (end - start) and 0xFF
        val offset = (candidate - start) and 0xFF
        val withinSpan = totalSpan >= offset
        val notAtStart = closedStart || ((start - candidate) and 0xFF) > 0
        val notAtEnd = closedEnd || ((end - candidate) and 0xFF) > 0
        return withinSpan && notAtStart && notAtEnd
    }

    companion object {
        /**
         * GAN's on-cube history window is small; once more than this
         * many moves are stuck behind a gap, backfill has demonstrably
         * lost the race and a full resync is the cheaper recovery.
         */
        const val DEFAULT_OVERFLOW_LIMIT = 16
    }
}
