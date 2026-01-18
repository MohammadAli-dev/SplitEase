package com.splitease.data.hydration

/**
 * Thrown when a strict hydration invariant is violated.
 *
 * **Propagation Rule**: This exception must NEVER be caught and downgraded inside
 * hydration or replay implementation layers; it must propagate to the
 * coordinator boundary as a fatal failure.
 *
 * @param report The structured failure report for observability.
 * @param message Optional custom message; defaults to a formatted report summary.
 */
class HydrationInvariantException(
    val report: HydrationFailureReport,
    message: String? = null
) : Exception(message ?: "Hydration Invariant Violation [${report.location}]: ${report.invariant}")
