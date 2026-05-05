package com.zucham.qbsmarter.domain.driver.gan

import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent

/**
 * Generation-agnostic parser interface for GAN smart-cube protocols.
 * Each generation (Gen2, Gen3, Gen4) has its own implementation;
 * [GanCubeDriver] picks one at connect time based on the detected
 * generation and feeds it the decrypted notification bytes.
 *
 * **State.** Parsers are stateful – they track rolling serial numbers,
 * cube-clock offsets, and (for Gen3/Gen4) a move-event FIFO buffer.
 * [reset] is called on every new connection to clear that state so
 * stale serials from a previous cube don't affect the new session.
 *
 * **Backfill callback.** Gen3 and Gen4 detect missed moves by serial-
 * number gaps and recover by asking the cube to retransmit the lost
 * window. They can't issue the GATT write themselves – the parser is
 * pure decoder, not transport-aware. Instead [parseStatePacket] takes
 * a `historyRequester` lambda the parser calls when it needs a refill.
 * The driver wires this to its own `send(...)` path. Gen2 doesn't use
 * the callback (its recovery is full-state Facelets resync, driven by
 * the orchestrator on [SmartCubeEvent.MovesMissed]).
 *
 * **Suspending on parse.** [parseStatePacket] is suspending so Gen3/
 * Gen4 can `await` the history-request GATT write before returning
 * partial events. In practice the suspend point is at the
 * `historyRequester` invocation, which happens at most once per
 * packet. Gen2's implementation never suspends.
 */
internal interface GanParser {
    fun reset()
    fun buildCommand(cmd: SmartCubeCommand): ByteArray?
    suspend fun parseStatePacket(
        message: ByteArray,
        historyRequester: suspend (startSerial: Int, count: Int) -> Unit,
    ): List<SmartCubeEvent>
}
