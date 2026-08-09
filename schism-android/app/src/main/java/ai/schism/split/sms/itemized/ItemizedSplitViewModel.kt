package ai.schism.split.sms.itemized

import ai.schism.split.core.billing.EntitlementRepository
import ai.schism.split.core.billing.EntitlementState
import ai.schism.split.core.billing.LiveSplitAllowance
import ai.schism.split.core.billing.PlusRequiredException
import ai.schism.split.core.billing.isPlus
import ai.schism.split.core.net.ClaimItemDto
import ai.schism.split.core.net.CreateClaimSessionRequest
import ai.schism.split.core.net.TaxLineDto
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.expense.data.ExpenseRepository
import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.sms.itemized.claim.ClaimSessionRepository
import ai.schism.split.sms.receipt.ReceiptDraft
import ai.schism.split.sms.receipt.ReceiptLineItem
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * One editable tax/charge line on the bill (GST/CGST/service charge/round-off/discount…): a label
 * and a SIGNED amount in minor units — negative reduces the bill (a discount, a negative round-off).
 * The engine only reports collapsed *aggregates* (see [seedCharges]), so these are seeded from those
 * and are the user-facing source of truth from then on.
 */
data class ChargeLine(val label: String, val amountMinor: Long)

/**
 * Splits a scanned draft's collapsed charge aggregates back into editable lines. [ReceiptDraft.taxMinor]
 * is the NET pot the engine computed (`tax + fees − discount + roundoff`) with fees/discount also
 * reported on their own, so the tax line is what's left once those are peeled back off (it therefore
 * also carries any round-off — the draft doesn't expose that separately). Zero lines are dropped.
 */
internal fun seedCharges(
    draft: ReceiptDraft?,
    taxLabel: String,
    feesLabel: String,
    discountLabel: String,
): List<ChargeLine> {
    draft ?: return emptyList()
    val tax = draft.taxMinor - draft.feesMinor + draft.discountMinor
    return listOfNotNull(
        ChargeLine(taxLabel, tax).takeIf { tax != 0L },
        ChargeLine(feesLabel, draft.feesMinor).takeIf { draft.feesMinor != 0L },
        ChargeLine(discountLabel, -draft.discountMinor).takeIf { draft.discountMinor != 0L },
    )
}

data class ItemizedSplitUiState(
    val loading: Boolean = true,
    /** True when the on-device AI model is downloaded + enabled. */
    val aiActive: Boolean = false,
    val draft: ReceiptDraft? = null,
    val title: String = "",
    val items: List<ReceiptLineItem> = emptyList(),
    /** Editable tax/charge lines, seeded from the scan (see [seedCharges]); empty for a manual bill. */
    val charges: List<ChargeLine> = emptyList(),
    val groups: List<Group> = emptyList(),
    val selectedGroupId: String? = null,
    val paidById: String = "",
    // Item index -> participantId -> weighted share (0 = not having it, 2 = had two of it, …).
    val assignments: Map<Int, Map<String, Long>> = emptyMap(),
    val notes: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    /** Settings › Labs "Claim links (alpha)" flag — gates the "Let everyone claim" entry point. */
    val claimLinksAlpha: Boolean = false,
    val creatingClaimSession: Boolean = false,
    /** Free hosted Live Splits left this UTC month; null until the backend has said. */
    val liveSplitAllowance: LiveSplitAllowance? = null,
    /** True once the backend has verified this account as Plus — hosting is then unlimited. */
    val plus: Boolean = false,
    /** Set when the backend answered a *hosting* attempt with 402; shows the Plus sheet. */
    val plusRequired: LiveSplitAllowance? = null,
) {
    val selectedGroup: Group? get() = groups.firstOrNull { it.id == selectedGroupId }

    /** Net charge pot to distribute across diners: the sum of the (editable) charge lines. */
    val taxMinor: Long get() = charges.sumOf { it.amountMinor }

    /** The bill's items before any charge. */
    val subtotalMinor: Long get() = items.sumOf { it.amountMinor }

    /** What the bill now adds up to, live: items + every charge line. */
    val totalMinor: Long get() = subtotalMinor + taxMinor

    /** The grand total printed on the scanned bill, or 0 when there is none (manual/unreadable bill). */
    val printedTotalMinor: Long get() = draft?.totalMinor ?: 0L

    /**
     * How far [totalMinor] has drifted from the printed total (positive = we're over), or null when
     * they agree or the bill printed no total. Neither number is ever silently overridden — the
     * screen states both.
     */
    val totalMismatchMinor: Long?
        get() = (totalMinor - printedTotalMinor).takeIf { printedTotalMinor > 0L && it != 0L }

    /** True when this draft actually came from the on-device LLM (vs the heuristic fallback). */
    val parsedByAi: Boolean get() = draft?.parsedByAi == true

    /** Live per-participant owed totals in minor units (weighted item shares + proportional tax). */
    val perPersonMinor: Map<String, Long>
        get() {
            val owed = LinkedHashMap<String, Long>()
            items.forEachIndexed { index, item ->
                val active = assignments[index].orEmpty().filterValues { it > 0 }
                val totalShares = active.values.sum()
                if (totalShares == 0L) return@forEachIndexed
                var distributed = 0L
                val entries = active.entries.toList()
                entries.forEachIndexed { i, (pid, share) ->
                    val part = if (i == entries.lastIndex) item.amountMinor - distributed
                    else item.amountMinor * share / totalShares
                    distributed += part
                    owed[pid] = (owed[pid] ?: 0L) + part
                }
            }
            val subtotal = owed.values.sum()
            // != 0, not > 0: the charge pot goes negative once a discount line outweighs the taxes.
            if (taxMinor != 0L && subtotal > 0) {
                var remaining = taxMinor
                val entries = owed.toMap().entries.toList()
                entries.forEachIndexed { i, (pid, sub) ->
                    val share = if (i == entries.lastIndex) remaining else taxMinor * sub / subtotal
                    remaining -= share
                    owed[pid] = (owed[pid] ?: 0L) + share
                }
            }
            return owed
        }
}

/** Add a charge line the scan missed. Blank labels are refused (an unlabelled money row is noise). */
internal fun ItemizedSplitUiState.addCharge(label: String, amountMinor: Long): ItemizedSplitUiState =
    if (label.isBlank()) this
    else copy(charges = charges + ChargeLine(label.trim().take(40), amountMinor), error = null)

/** Correct a misread charge line's label and/or amount. Out-of-range indices and blank labels no-op. */
internal fun ItemizedSplitUiState.updateCharge(
    index: Int,
    label: String,
    amountMinor: Long,
): ItemizedSplitUiState {
    if (index !in charges.indices || label.isBlank()) return this
    val next = charges.toMutableList()
    next[index] = ChargeLine(label.trim().take(40), amountMinor)
    return copy(charges = next, error = null)
}

/** Drop a charge line the parser invented. Out-of-range indices no-op. */
internal fun ItemizedSplitUiState.removeCharge(index: Int): ItemizedSplitUiState =
    if (index !in charges.indices) this
    else copy(charges = charges.filterIndexed { i, _ -> i != index }, error = null)

@HiltViewModel
class ItemizedSplitViewModel @Inject constructor(
    private val pending: PendingReceipt,
    private val groupRepo: GroupRepository,
    private val expenseRepo: ExpenseRepository,
    private val settings: SettingsRepository,
    private val llmParser: ai.schism.split.core.ai.LlmExpenseParser,
    private val claimSessionRepo: ClaimSessionRepository,
    private val entitlements: EntitlementRepository,
    @ApplicationContext appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The group we were launched from (e.g. a group's Add Expense), if any — preselected below. */
    private val preselectGroupId: String? = savedStateHandle["groupId"]

    private val _state = MutableStateFlow(ItemizedSplitUiState())
    val state: StateFlow<ItemizedSplitUiState> = _state.asStateFlow()

    init {
        val draft = pending.draft
        val charges = seedCharges(
            draft = draft,
            taxLabel = appContext.getString(ai.schism.split.R.string.itemized_charge_tax),
            feesLabel = appContext.getString(ai.schism.split.R.string.itemized_charge_fees),
            discountLabel = appContext.getString(ai.schism.split.R.string.itemized_charge_discount),
        )
        _state.update {
            it.copy(
                draft = draft,
                items = draft?.lineItems.orEmpty(),
                charges = charges,
                title = draft?.merchant ?: "Receipt",
            )
        }

        viewModelScope.launch {
            val aiActive = llmParser.isAvailable && settings.aiEnabled.first()
            _state.update { it.copy(aiActive = aiActive) }
        }

        viewModelScope.launch {
            settings.claimLinksAlpha.collect { enabled -> _state.update { it.copy(claimLinksAlpha = enabled) } }
        }

        // Show what's left *before* hosting, so the limit is never a surprise at the last step.
        viewModelScope.launch {
            entitlements.state.collect { entitlement ->
                _state.update {
                    it.copy(
                        plus = entitlement.isPlus,
                        liveSplitAllowance = (entitlement as? EntitlementState.Free)?.allowance,
                    )
                }
            }
        }

        viewModelScope.launch {
            // Only groups this device has joined/created are eligible targets.
            combine(groupRepo.observeGroups(), settings.knownGroupIds) { groups, known ->
                groups.filter { it.id in known }
            }.collect { known ->
                _state.update { s ->
                    val selectedId = s.selectedGroupId?.takeIf { id -> known.any { it.id == id } }
                        ?: preselectGroupId?.takeIf { id -> known.any { it.id == id } }
                        ?: known.firstOrNull()?.id
                    val selected = known.firstOrNull { it.id == selectedId }
                    s.copy(
                        loading = false,
                        groups = known,
                        selectedGroupId = selectedId,
                        paidById = s.paidById.ifBlank { defaultPaidBy(selected) },
                        assignments = s.assignments.ifEmpty { everyoneOnce(s.items, selected) },
                    )
                }
            }
        }
    }

    /** Default: every item is shared 1× by everyone in the selected group. */
    private fun everyoneOnce(items: List<ReceiptLineItem>, group: Group?): Map<Int, Map<String, Long>> {
        val everyone = group?.participants?.associate { it.id to 1L } ?: emptyMap()
        return items.indices.associateWith { everyone }
    }

    private fun defaultPaidBy(group: Group?): String {
        group ?: return ""
        return group.activeParticipantId ?: group.participants.firstOrNull()?.id ?: ""
    }

    fun onGroupChange(groupId: String) {
        _state.update { s ->
            val group = s.groups.firstOrNull { it.id == groupId }
            s.copy(
                selectedGroupId = groupId,
                paidById = defaultPaidBy(group),
                assignments = everyoneOnce(s.items, group),
                error = null,
            )
        }
    }

    fun onTitleChange(value: String) {
        _state.update { it.copy(title = value, error = null) }
    }

    fun onNotesChange(v: String) = _state.update { it.copy(notes = v) }

    /** Adjust a participant's share of an item by [delta] (+1 / −1), clamped at 0. */
    fun adjustShare(itemIndex: Int, participantId: String, delta: Long) {
        _state.update { s ->
            val current = s.assignments[itemIndex].orEmpty()
            val next = ((current[participantId] ?: 0L) + delta).coerceAtLeast(0L)
            s.copy(
                assignments = s.assignments + (itemIndex to (current + (participantId to next))),
                error = null,
            )
        }
    }

    /** Directly set a participant's share of an item to [value] (typed entry), clamped at 0. */
    fun setShare(itemIndex: Int, participantId: String, value: Long) {
        _state.update { s ->
            val current = s.assignments[itemIndex].orEmpty()
            val next = value.coerceAtLeast(0L)
            s.copy(
                assignments = s.assignments + (itemIndex to (current + (participantId to next))),
                error = null,
            )
        }
    }

    /**
     * Edit an item's name/qty/unit-price in place. The line amount is derived as `qty *
     * unitPriceMinor` — never typed directly — so the [ReceiptLineItem] invariant (amount == qty *
     * unitPrice) holds for hand-edited items exactly as it does for scanned ones.
     */
    fun updateItem(index: Int, name: String, qty: Int, unitPriceMinor: Long) {
        _state.update { s ->
            if (index !in s.items.indices) return@update s
            val items = s.items.toMutableList()
            val q = qty.coerceIn(1, 99)
            items[index] = ReceiptLineItem(
                name = name.trim().take(60),
                amountMinor = unitPriceMinor * q,
                qty = q,
                unitPriceMinor = unitPriceMinor,
            )
            s.copy(items = items, error = null)
        }
    }

    /** Add a tax/charge line the scan missed (amount signed: negative reduces the bill). */
    fun addCharge(label: String, amountMinor: Long) = _state.update { it.addCharge(label, amountMinor) }

    /** Correct a misread tax/charge line — both its label and its amount. */
    fun updateCharge(index: Int, label: String, amountMinor: Long) =
        _state.update { it.updateCharge(index, label, amountMinor) }

    /** Drop a tax/charge line the parser invented. */
    fun removeCharge(index: Int) = _state.update { it.removeCharge(index) }

    /** Remove a mis-parsed item; its assignments are dropped and later indices reshuffled. */
    fun removeItem(index: Int) {
        _state.update { s ->
            if (index !in s.items.indices) return@update s
            val items = s.items.toMutableList().also { it.removeAt(index) }
            val reindexed = s.assignments
                .filterKeys { it != index }
                .mapKeys { (k, _) -> if (k > index) k - 1 else k }
            s.copy(items = items, assignments = reindexed, error = null)
        }
    }

    /**
     * Add a missing item by hand (name + qty + unit price, amount derived as `qty * unitPrice`);
     * everyone shares it 1× by default. This is also how a fully manual (no-scan) bill is built up
     * from an empty [ItemizedSplitUiState.items] list.
     */
    fun addItem(name: String, qty: Int, unitPriceMinor: Long) {
        _state.update { s ->
            val q = qty.coerceIn(1, 99)
            val items = s.items + ReceiptLineItem(
                name = name.trim().take(60),
                amountMinor = unitPriceMinor * q,
                qty = q,
                unitPriceMinor = unitPriceMinor,
            )
            val everyone = s.selectedGroup?.participants?.associate { it.id to 1L } ?: emptyMap()
            s.copy(items = items, assignments = s.assignments + (items.lastIndex to everyone), error = null)
        }
    }

    /**
     * Creates a claim session from the current draft's items + tax so everyone in the group can claim
     * what they had, instead of assigning shares here. Gated behind Settings › Labs (see
     * [ItemizedSplitUiState.claimLinksAlpha]). Reports the new session id via [onCreated].
     */
    fun startClaimSession(onCreated: (String) -> Unit) {
        val s = _state.value
        val group = s.selectedGroup
        if (group == null) {
            _state.update { it.copy(error = "Pick a group to split into") }
            return
        }
        if (s.items.isEmpty()) {
            _state.update { it.copy(error = "Add at least one item first") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(creatingClaimSession = true, error = null) }
            val items = s.items.mapIndexed { index, item -> ClaimItemDto(index, item.name, item.qty, item.amountMinor) }
            val request = CreateClaimSessionRequest(
                title = s.title.trim().ifBlank { s.draft?.merchant ?: "Receipt" },
                currency = s.draft?.currency ?: "₹",
                items = items,
                // taxMinor carries the WHOLE (edited) charge pot, so fees/discount/roundoff stay 0
                // — the server's pot is tax + fees − discount + roundoff and must not double-count.
                // `taxes` is the labelled breakdown claimants see on the claim screen.
                taxMinor = s.taxMinor,
                taxes = s.charges.map { TaxLineDto(it.label, it.amountMinor) },
            )
            claimSessionRepo.createSession(group.id, request).fold(
                onSuccess = { session ->
                    _state.update { it.copy(creatingClaimSession = false) }
                    onCreated(session.id)
                },
                onFailure = { e ->
                    // Only the backend's PLUS_REQUIRED opens the sheet; every other failure is just a
                    // failure, and the manual item assignment on this screen keeps working either way.
                    val gated = e as? PlusRequiredException
                    if (gated != null) entitlements.noteAllowance(gated.allowance)
                    _state.update {
                        it.copy(
                            creatingClaimSession = false,
                            plusRequired = gated?.allowance,
                            error = if (gated == null) e.message ?: "Couldn't create claim link" else null,
                        )
                    }
                },
            )
        }
    }

    /** Dismisses the Plus sheet. The draft, its items and every assignment are untouched. */
    fun dismissPlusSheet() {
        _state.update { it.copy(plusRequired = null) }
    }

    fun submit(onDone: () -> Unit) {
        val s = _state.value
        val group = s.selectedGroup
        if (group == null) {
            _state.update { it.copy(error = "Pick a group to split into") }
            return
        }
        // You're adding it, so you paid: default to your participant, else the first one.
        val payerId = s.paidById.ifBlank { group.activeParticipantId ?: group.participants.firstOrNull()?.id.orEmpty() }
        val assigned = s.items.mapIndexed { index, item ->
            AssignedItem(amountMinor = item.amountMinor, shares = s.assignments[index].orEmpty())
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }

            // Stamp the creator as your user id when the active participant in this group is you.
            val userId = settings.userId.first()
            val youParticipant = group.participants.firstOrNull { it.id == group.activeParticipantId }
            val addedBy = userId.takeIf { it.isNotBlank() && youParticipant?.userId == it }

            val participantNames = group.participants.associate { it.id to it.name }
            val breakdown = buildItemBreakdown(s.items, s.assignments, participantNames)
            val userNotes = s.notes.trim()
            val combinedNotes = when {
                breakdown.isBlank() -> userNotes
                userNotes.isBlank() -> breakdown
                else -> "$breakdown\n\n$userNotes"
            }

            val request = buildItemizedExpenseRequest(
                items = assigned,
                group = group,
                paidById = payerId,
                addedBy = addedBy,
                title = s.title.trim().ifBlank { s.draft?.merchant ?: "Receipt" },
                currency = s.draft?.currency ?: "₹",
                dateIso = s.draft?.date,
                taxMinor = s.taxMinor,
                notes = combinedNotes,
            )
            if (request == null) {
                _state.update { it.copy(submitting = false, error = "Assign at least one item to someone") }
                return@launch
            }
            expenseRepo.createExpense(group.id, request, idempotencyKey = UUID.randomUUID().toString()).fold(
                onSuccess = {
                    pending.draft = null
                    _state.update { it.copy(submitting = false) }
                    onDone()
                },
                onFailure = { e ->
                    _state.update { it.copy(submitting = false, error = e.message ?: "Couldn't add to group") }
                },
            )
        }
    }
}
