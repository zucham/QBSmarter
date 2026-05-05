package com.zucham.qbsmarter.domain.driver

/** Commands the app can send to the cube. Drivers map these to wire bytes. */
sealed interface SmartCubeCommand {
    data object RequestFacelets : SmartCubeCommand
    data object RequestHardware : SmartCubeCommand
    data object RequestBattery : SmartCubeCommand
    data object RequestReset : SmartCubeCommand

    /**
     * Ask the cube to retransmit a window of historical moves (Gen3/Gen4
     * only). The cube's wire protocol from generation 3 onward includes
     * a buffer-recovery path: when the parser detects a non-contiguous
     * move serial, it pauses event emission and emits this command so
     * the cube re-sends the missing moves. Once the gap fills the FIFO
     * is drained in order.
     *
     * Gen2 has no equivalent mechanism – on a missed-moves event it
     * falls back to a full Facelets resync ([RequestFacelets]) and the
     * Gen2 driver translates this command to a no-op accordingly.
     *
     * @property startSerial highest serial number we already have; the
     *   cube replies with moves with serials *less than* this.
     * @property count number of moves to fetch backward from
     *   [startSerial]. The cube enforces firmware-specific alignment
     *   (Gen3/Gen4 align to even-count windows starting at odd serials)
     *   so the wire encoder may round both fields.
     */
    data class RequestMoveHistory(
        val startSerial: Int,
        val count: Int,
    ) : SmartCubeCommand
}
