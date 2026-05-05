package com.zucham.qbsmarter.ui.screens.solve

/**
 * Solve phase state machine:
 *   IDLE → SCRAMBLING → READY → (INSPECTION) → RUNNING → SOLVED
 *
 * Transitions:
 *   • IDLE → SCRAMBLING: user presses "New" or screen opens
 *   • SCRAMBLING → READY: live facelets equal scramble target
 *   • READY → INSPECTION: user taps Start (only if inspection enabled)
 *   • READY → RUNNING (skipping INSPECTION): inspection disabled,
 *     first move starts the timer
 *   • INSPECTION → RUNNING: 15s elapsed OR first move detected
 *   • RUNNING → SOLVED: live state is solved
 */
enum class SolvePhase { IDLE, SCRAMBLING, READY, INSPECTION, RUNNING, SOLVED }
