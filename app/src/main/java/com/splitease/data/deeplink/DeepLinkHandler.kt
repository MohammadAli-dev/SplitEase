package com.splitease.data.deeplink

import android.net.Uri

/**
 * Result of parsing a deep link.
 */
sealed class DeepLinkResult {
    /**
     * Deep link is an invite claim request.
     * @param inviteToken The extracted invite token.
     */
    data class ClaimInvite(val inviteToken: String) : DeepLinkResult()

    /**
     * Deep link is not recognized or has an invalid format.
     */
    object Unknown : DeepLinkResult()
}

/**
 * Parses incoming deep links and extracts relevant data.
 *
 * Supported Patterns:
 * - https://splitease.app/connect/{inviteToken}
 */
interface DeepLinkHandler {
    /**
     * Parse a deep link URI and return the appropriate result.
     *
     * @param uri The incoming deep link URI.
     * @return [DeepLinkResult] indicating the type and data of the deep link.
     */
    fun parse(uri: Uri): DeepLinkResult
}

class DeepLinkHandlerImpl : DeepLinkHandler {

    companion object {
        private const val HOST_SPLITEASE = "splitease.app"
        private const val PATH_CONNECT = "connect"
    }

    override fun parse(uri: Uri): DeepLinkResult {
        // Validate host (case-insensitive per RFC 3986)
        if (!uri.host.equals(HOST_SPLITEASE, ignoreCase = true)) {
            return DeepLinkResult.Unknown
        }

        // Expected path: /connect/{inviteToken}
        val pathSegments = uri.pathSegments
        if (pathSegments.size == 2 && pathSegments[0] == PATH_CONNECT) {
            val inviteToken = pathSegments[1]
            if (inviteToken.isNotBlank()) {
                return DeepLinkResult.ClaimInvite(inviteToken)
            }
        }

        return DeepLinkResult.Unknown
    }
}
