package com.oshi.messenger.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capability marker for the authenticated V2 cover transport.
 *
 * The former DNS, header, domain-fronting, analytics and image helper methods
 * were removed. They had no verified receiver and could silently lose messages
 * or create a fingerprintable transport outside the signed V2 cover protocol.
 */
@Singleton
class CovertChannelManager @Inject constructor() {
    companion object {
        const val NETWORK_TRANSPORT_AVAILABLE = true
    }
}
