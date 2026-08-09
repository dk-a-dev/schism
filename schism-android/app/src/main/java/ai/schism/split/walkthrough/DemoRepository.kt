package ai.schism.split.walkthrough

/** Models in this file deliberately do not implement API, database, or serialization contracts. */
data class DemoParticipant(
    val id: String,
    val name: String,
    val isCurrentUser: Boolean = false,
)

data class DemoGroup(
    val id: String,
    val name: String,
    val currencySymbol: String,
    val currencyCode: String,
    val participants: List<DemoParticipant>,
)

data class DemoReceiptItem(
    val id: String,
    val name: String,
    val amountMinor: Long,
    val assignedParticipantIds: Set<String>,
)

data class DemoReceipt(
    val id: String,
    val merchant: String,
    val subtotalMinor: Long,
    val taxMinor: Long,
    val totalMinor: Long,
    val items: List<DemoReceiptItem>,
)

data class DemoBalance(
    val paid: Long,
    val paidFor: Long,
    val total: Long,
)

data class DemoReimbursement(
    val fromParticipantId: String,
    val toParticipantId: String,
    val amount: Long,
)

data class DemoBalances(
    val perParticipant: Map<String, DemoBalance>,
    val reimbursements: List<DemoReimbursement>,
)

data class DemoLiveSplitPreview(
    val claimedItemCount: Int,
    val totalItemCount: Int,
    val participantTotalsMinor: Map<String, Long>,
)

data class DemoSnapshot(
    val group: DemoGroup,
    val receipt: DemoReceipt,
    val participantTotalsMinor: Map<String, Long>,
    val balances: DemoBalances,
    val liveSplitPreview: DemoLiveSplitPreview,
)

/** Dependency-free demo data boundary used by the future demo UI. */
interface DemoDataSource {
    val snapshot: DemoSnapshot

    fun assign(itemId: String, participantIds: Set<String>): DemoSnapshot

    fun reset(): DemoSnapshot
}

/**
 * Deterministic, process-local Weekend dinner fixture. It cannot write Room, call the backend,
 * request permissions, or launch external intents because none of those dependencies enter this
 * class or its interface.
 */
class DemoRepository : DemoDataSource {
    private val initialReceipt = weekendDinnerReceipt()
    private var currentReceipt = initialReceipt

    override val snapshot: DemoSnapshot
        get() = snapshotFor(currentReceipt)

    override fun assign(itemId: String, participantIds: Set<String>): DemoSnapshot {
        require(participantIds.isNotEmpty()) { "At least one participant must claim an item" }
        val participantOrder = DEMO_GROUP.participants.map { it.id }
        require(participantIds.all(participantOrder::contains)) { "Unknown demo participant" }
        require(currentReceipt.items.any { it.id == itemId }) { "Unknown demo receipt item" }

        val orderedAssignments = participantOrder.filterTo(linkedSetOf(), participantIds::contains)
        currentReceipt = currentReceipt.copy(
            items = currentReceipt.items.map { item ->
                if (item.id == itemId) item.copy(assignedParticipantIds = orderedAssignments) else item
            },
        )
        return snapshot
    }

    override fun reset(): DemoSnapshot {
        currentReceipt = initialReceipt
        return snapshot
    }

    private fun snapshotFor(receipt: DemoReceipt): DemoSnapshot {
        val participantTotals = participantTotals(receipt)
        val balances = balances(participantTotals, receipt.totalMinor)
        return DemoSnapshot(
            group = DEMO_GROUP,
            receipt = receipt,
            participantTotalsMinor = participantTotals,
            balances = balances,
            liveSplitPreview = DemoLiveSplitPreview(
                claimedItemCount = receipt.items.count { it.assignedParticipantIds.isNotEmpty() },
                totalItemCount = receipt.items.size,
                participantTotalsMinor = participantTotals,
            ),
        )
    }

    private fun participantTotals(receipt: DemoReceipt): Map<String, Long> {
        val participantOrder = DEMO_GROUP.participants.map { it.id }
        val subtotals = participantOrder.associateWithTo(linkedMapOf()) { 0L }
        receipt.items.forEach { item ->
            val assigned = participantOrder.filter(item.assignedParticipantIds::contains)
            require(assigned.isNotEmpty()) { "Every demo item must be assigned" }
            val each = item.amountMinor / assigned.size
            val remainder = (item.amountMinor % assigned.size).toInt()
            assigned.forEachIndexed { index, participantId ->
                subtotals[participantId] = subtotals.getValue(participantId) + each +
                    if (index < remainder) 1L else 0L
            }
        }

        val overhead = receipt.totalMinor - receipt.subtotalMinor
        val allocatedOverhead = proportionalAllocation(overhead, subtotals, receipt.subtotalMinor)
        return participantOrder.associateWithTo(linkedMapOf()) { participantId ->
            subtotals.getValue(participantId) + allocatedOverhead.getValue(participantId)
        }
    }

    private fun proportionalAllocation(
        amount: Long,
        weights: Map<String, Long>,
        totalWeight: Long,
    ): Map<String, Long> {
        val allocations = weights.mapValuesTo(linkedMapOf()) { (_, weight) -> amount * weight / totalWeight }
        var remaining = amount - allocations.values.sum()
        val order = weights.entries.sortedWith(
            compareByDescending<Map.Entry<String, Long>> { amount * it.value % totalWeight }
                .thenBy { DEMO_GROUP.participants.indexOfFirst { participant -> participant.id == it.key } },
        )
        var index = 0
        while (remaining > 0) {
            val participantId = order[index % order.size].key
            allocations[participantId] = allocations.getValue(participantId) + 1
            remaining--
            index++
        }
        return allocations
    }

    private fun balances(participantTotals: Map<String, Long>, receiptTotal: Long): DemoBalances {
        val payerId = "demo-you"
        val perParticipant = DEMO_GROUP.participants.associateTo(linkedMapOf()) { participant ->
            val paid = if (participant.id == payerId) receiptTotal else 0L
            val paidFor = participantTotals.getValue(participant.id)
            participant.id to DemoBalance(paid = paid, paidFor = paidFor, total = paid - paidFor)
        }
        val reimbursements = DEMO_GROUP.participants
            .filter { it.id != payerId }
            .map { participant ->
                DemoReimbursement(
                    fromParticipantId = participant.id,
                    toParticipantId = payerId,
                    amount = participantTotals.getValue(participant.id),
                )
            }
        return DemoBalances(perParticipant = perParticipant, reimbursements = reimbursements)
    }

    companion object {
        private val DEMO_GROUP = DemoGroup(
            id = "demo-weekend-dinner",
            name = "Weekend dinner",
            currencySymbol = "₹",
            currencyCode = "INR",
            participants = listOf(
                DemoParticipant(id = "demo-you", name = "You", isCurrentUser = true),
                DemoParticipant(id = "demo-maya", name = "Maya"),
                DemoParticipant(id = "demo-kabir", name = "Kabir"),
            ),
        )

        private fun weekendDinnerReceipt() = DemoReceipt(
            id = "demo-weekend-receipt",
            merchant = "The Green Table",
            subtotalMinor = 126_000L,
            taxMinor = 6_300L,
            totalMinor = 132_300L,
            items = listOf(
                DemoReceiptItem(
                    id = "paneer-tikka",
                    name = "Paneer tikka",
                    amountMinor = 48_000L,
                    assignedParticipantIds = linkedSetOf("demo-you", "demo-maya"),
                ),
                DemoReceiptItem(
                    id = "dal-makhani",
                    name = "Dal makhani",
                    amountMinor = 36_000L,
                    assignedParticipantIds = linkedSetOf("demo-maya", "demo-kabir"),
                ),
                DemoReceiptItem(
                    id = "garlic-naan",
                    name = "Garlic naan",
                    amountMinor = 24_000L,
                    assignedParticipantIds = linkedSetOf("demo-you", "demo-maya", "demo-kabir"),
                ),
                DemoReceiptItem(
                    id = "lime-sodas",
                    name = "Lime sodas",
                    amountMinor = 18_000L,
                    assignedParticipantIds = linkedSetOf("demo-you", "demo-kabir"),
                ),
            ),
        )
    }
}
