package com.oshi.messenger.service.audio

import android.util.Log

/**
 * ForwardErrorCorrection - iOS Compatible XOR-based FEC
 * Matches iOS SimpleXORFEC implementation.
 * Groups of 4 packets; generates XOR parity packet (type 0xFE).
 * Can recover 1 lost packet per group.
 */
class ForwardErrorCorrection(private val groupSize: Int = 4) {

    companion object {
        private const val TAG = "FEC"
        const val FEC_PACKET_TYPE: Byte = 0xFE.toByte()
    }

    // ---- Sender side ----
    private val currentGroup = mutableListOf<Pair<Long, ByteArray>>() // (seqNum, data)
    private var groupSequenceStart: Long = 0

    /**
     * Add a packet for FEC encoding.
     * Returns the FEC packet when group is complete (every 4 packets), null otherwise.
     * FEC packet format: [groupStart(8 BE)][groupSize(1)][XOR data]
     */
    fun addPacketForEncoding(data: ByteArray, sequenceNumber: Long): ByteArray? {
        if (currentGroup.isEmpty()) {
            groupSequenceStart = sequenceNumber
        }
        currentGroup.add(sequenceNumber to data)

        if (currentGroup.size >= groupSize) {
            val fecPacket = generateFECPacket()
            currentGroup.clear()
            return fecPacket
        }
        return null
    }

    private fun generateFECPacket(): ByteArray {
        if (currentGroup.isEmpty()) return ByteArray(0)

        // Find max data length in group
        val maxLen = currentGroup.maxOf { it.second.size }

        // XOR all packets together (pad shorter ones with zeros)
        val xorData = ByteArray(maxLen)
        for ((_, data) in currentGroup) {
            for (i in data.indices) {
                xorData[i] = (xorData[i].toInt() xor data[i].toInt()).toByte()
            }
        }

        // Build FEC packet: [groupStart(8 BE)][groupSize(1)][XOR data]
        val fecPacket = ByteArray(8 + 1 + xorData.size)
        // Group start sequence (8 bytes, big endian)
        for (i in 0..7) {
            fecPacket[i] = (groupSequenceStart shr (56 - i * 8)).toByte()
        }
        // Group size
        fecPacket[8] = currentGroup.size.toByte()
        // XOR data
        System.arraycopy(xorData, 0, fecPacket, 9, xorData.size)

        return fecPacket
    }

    // ---- Receiver side ----
    // Map: groupStartSeq -> Map<seqNum, data>
    private val receivedGroups = HashMap<Long, HashMap<Long, ByteArray>>()
    private val receivedFEC = HashMap<Long, ByteArray>() // groupStartSeq -> XOR data
    private val groupSizes = HashMap<Long, Int>() // groupStartSeq -> expected size

    var packetsRecovered: Long = 0
        private set

    fun storeReceivedPacket(data: ByteArray, sequenceNumber: Long) {
        // Determine which group this belongs to (each group = 4 packets)
        val groupStart = (sequenceNumber / groupSize) * groupSize
        val group = receivedGroups.getOrPut(groupStart) { HashMap() }
        group[sequenceNumber] = data
        groupSizes.putIfAbsent(groupStart, groupSize)

        // Try recovery if we have FEC for this group
        tryRecoverGroup(groupStart)
        cleanupOldGroups(groupStart)
    }

    fun processFECPacket(fecData: ByteArray) {
        if (fecData.size < 10) return

        // Parse: [groupStart(8 BE)][groupSize(1)][XOR data]
        var groupStart = 0L
        for (i in 0..7) {
            groupStart = (groupStart shl 8) or (fecData[i].toLong() and 0xFF)
        }
        val gSize = fecData[8].toInt() and 0xFF
        val xorData = fecData.copyOfRange(9, fecData.size)

        receivedFEC[groupStart] = xorData
        groupSizes[groupStart] = gSize

        tryRecoverGroup(groupStart)
    }

    private fun tryRecoverGroup(groupStart: Long) {
        val xorData = receivedFEC[groupStart] ?: return
        val group = receivedGroups[groupStart] ?: return
        val expectedSize = groupSizes[groupStart] ?: groupSize

        // Check if exactly 1 packet is missing
        val receivedCount = group.size
        if (receivedCount != expectedSize - 1) return // Can only recover exactly 1

        // Find the missing sequence number
        val expectedSeqs = (groupStart until groupStart + expectedSize).toSet()
        val receivedSeqs = group.keys
        val missingSeqs = expectedSeqs - receivedSeqs
        if (missingSeqs.size != 1) return

        val missingSeq = missingSeqs.first()

        // XOR all received packets with FEC to recover missing
        val recovered = xorData.copyOf()
        for ((_, data) in group) {
            for (i in data.indices) {
                if (i < recovered.size) {
                    recovered[i] = (recovered[i].toInt() xor data[i].toInt()).toByte()
                }
            }
        }

        // Store recovered packet
        group[missingSeq] = recovered
        packetsRecovered++
        Log.d(TAG, "Recovered packet seq=$missingSeq via FEC (total recovered: $packetsRecovered)")
    }

    /**
     * Try to recover a specific missing packet.
     * Returns recovered data or null if not possible.
     */
    fun tryRecover(missingSequence: Long): ByteArray? {
        val groupStart = (missingSequence / groupSize) * groupSize
        val group = receivedGroups[groupStart] ?: return null
        return group[missingSequence]
    }

    private fun cleanupOldGroups(currentGroupStart: Long) {
        val keysToRemove = receivedGroups.keys.filter { it < currentGroupStart - groupSize * 10 }
        keysToRemove.forEach {
            receivedGroups.remove(it)
            receivedFEC.remove(it)
            groupSizes.remove(it)
        }
    }

    fun reset() {
        currentGroup.clear()
        receivedGroups.clear()
        receivedFEC.clear()
        groupSizes.clear()
        packetsRecovered = 0
    }
}
