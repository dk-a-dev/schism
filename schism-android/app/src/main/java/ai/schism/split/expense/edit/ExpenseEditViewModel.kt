package ai.schism.split.expense.edit

import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.CategoryDto
import ai.schism.split.core.net.ExpenseRequest
import ai.schism.split.core.net.PaidForDto
import ai.schism.split.expense.data.ExpenseRepository
import ai.schism.split.expense.edit.voice.parseSpokenExpense
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.groups.data.Participant
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---- Pure, trivially unit-testable core (no Android / network dependencies) ----

/** How the expense total is divided among the selected participants. */
enum class SplitMode { EVENLY, BY_SHARES, BY_PERCENTAGE, BY_AMOUNT }

/**
 * One participant's selection + per-mode inputs, already parsed to integers:
 *  - [weight]              is used by [SplitMode.BY_SHARES]      (raw share count),
 *  - [percentBasisPoints]  is used by [SplitMode.BY_PERCENTAGE] (e.g. 33.33% -> 3333),
 *  - [amountMinor]         is used by [SplitMode.BY_AMOUNT]      (minor units).
 * Only the field relevant to the active mode is read; the rest are ignored.
 */
data class ParticipantInput(
    val participantId: String,
    val selected: Boolean = false,
    val weight: Long = 1L,
    val percentBasisPoints: Long = 0L,
    val amountMinor: Long = 0L,
)

/** A fully-parsed expense form. Money is Long minor units end-to-end. */
data class ExpenseForm(
    val title: String,
    val amountMinor: Long,
    val categoryId: Int,
    val expenseDate: String?,
    val paidById: String,
    val splitMode: SplitMode,
    val isReimbursement: Boolean,
    val notes: String,
    val participants: List<ParticipantInput>,
)

/** The largest amount the backend accepts, in minor units. */
private const val MAX_AMOUNT_MINOR = 1_000_000_000L

private fun fail(message: String): Result<ExpenseRequest> =
    Result.failure(IllegalArgumentException(message))

/**
 * Validates [form] the way the backend does and encodes the split into [PaidForDto] shares. Returns
 * a descriptive failure instead of throwing, so callers can surface the message without a try/catch.
 */
fun buildExpenseRequest(form: ExpenseForm): Result<ExpenseRequest> {
    if (form.title.isBlank()) return fail("Enter a title")
    if (form.amountMinor <= 0L) return fail("Enter an amount greater than zero")
    if (form.amountMinor > MAX_AMOUNT_MINOR) return fail("Amount is too large")

    val selected = form.participants.filter { it.selected }
    if (selected.isEmpty()) return fail("Select at least one participant")

    // Cross-total checks that only make sense once we know every selected participant.
    when (form.splitMode) {
        SplitMode.BY_PERCENTAGE -> {
            val sum = selected.sumOf { it.percentBasisPoints }
            if (sum != 10_000L) return fail("Percentages must add up to 100%")
        }
        SplitMode.BY_AMOUNT -> {
            val sum = selected.sumOf { it.amountMinor }
            if (sum != form.amountMinor) return fail("Split amounts must add up to the total")
        }
        else -> Unit
    }

    val paidFor = selected.map { p ->
        val shares = when (form.splitMode) {
            SplitMode.EVENLY -> 1L
            SplitMode.BY_SHARES -> p.weight
            SplitMode.BY_PERCENTAGE -> p.percentBasisPoints
            SplitMode.BY_AMOUNT -> p.amountMinor
        }
        if (shares <= 0L) return fail("Each selected participant needs a value greater than zero")
        PaidForDto(participantId = p.participantId, shares = shares)
    }

    return Result.success(
        ExpenseRequest(
            title = form.title.trim(),
            amount = form.amountMinor,
            categoryId = form.categoryId,
            expenseDate = form.expenseDate,
            paidById = form.paidById,
            splitMode = form.splitMode.name,
            isReimbursement = form.isReimbursement,
            notes = form.notes.trim(),
            paidFor = paidFor,
        ),
    )
}

/**
 * Parses a decimal string like "42", "42.5" or "0.05" into minor units (4200 / 4250 / 5). Returns
 * null for anything that isn't a non-negative decimal with at most two fractional digits. Because
 * two decimals == cents, this doubles as a percent -> basis-points parser (e.g. "33.33" -> 3333).
 */
fun parseAmountToMinor(input: String): Long? {
    val trimmed = input.trim()
    if (!Regex("""\d+(\.\d{1,2})?""").matches(trimmed)) return null
    val parts = trimmed.split(".")
    val whole = parts[0].toLongOrNull() ?: return null
    val frac = if (parts.size > 1) parts[1].padEnd(2, '0').toLong() else 0L
    return whole * 100 + frac
}

// ---- ViewModel ----

/** A per-participant row as edited in the UI; numeric inputs are kept as text to preserve typing. */
data class ParticipantRow(
    val participantId: String,
    val name: String,
    val selected: Boolean = true,
    val weightText: String = "1",
    val percentText: String = "",
    val amountText: String = "",
)

data class ExpenseEditUiState(
    val loading: Boolean = true,
    val isEdit: Boolean = false,
    val title: String = "",
    val amountText: String = "",
    val categoryId: Int = 0,
    val expenseDate: String? = null,
    val paidById: String = "",
    val splitMode: SplitMode = SplitMode.EVENLY,
    val isReimbursement: Boolean = false,
    val notes: String = "",
    val participants: List<Participant> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val rows: List<ParticipantRow> = emptyList(),
    val submitting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ExpenseEditViewModel @Inject constructor(
    private val groupRepo: GroupRepository,
    private val expenseRepo: ExpenseRepository,
    private val api: ApiService,
    private val llmParser: ai.schism.split.core.ai.LlmExpenseParser,
    private val smsRepo: ai.schism.split.sms.data.SmsRepository,
    private val pendingReceipt: ai.schism.split.sms.itemized.PendingReceipt,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle["groupId"])
    val expenseId: String? = savedStateHandle["expenseId"]
    // When set, this expense is being created from an SMS transaction: prefill from it, and mark the
    // transaction pushed once saved.
    private val transactionId: String? = savedStateHandle["transactionId"]

    // Who is acting: the device's active participant in this group. Stamped onto new expenses so the
    // group can attribute and gate edits; on an edit we keep the original creator instead.
    private var youParticipantId: String? = null
    private var existingAddedBy: String = ""

    private val _state = MutableStateFlow(ExpenseEditUiState(isEdit = expenseId != null))
    val state: StateFlow<ExpenseEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Categories are best-effort; the form is still usable if the fetch fails.
            runCatching { api.listCategories() }
                .onSuccess { cats -> _state.update { it.copy(categories = cats) } }

            // Edit mode: prefill the scalar fields from the existing expense before building rows.
            val existing = expenseId?.let { runCatching { api.getExpense(groupId, it) }.getOrNull() }
            if (existing != null) {
                _state.update {
                    it.copy(
                        title = existing.title,
                        amountText = minorToPlain(existing.amount),
                        categoryId = existing.categoryId,
                        paidById = existing.paidById,
                        splitMode = runCatching { SplitMode.valueOf(existing.splitMode) }
                            .getOrDefault(SplitMode.EVENLY),
                        isReimbursement = existing.isReimbursement,
                        notes = existing.notes,
                        expenseDate = existing.expenseDate,
                    )
                }
                existingAddedBy = existing.addedBy
            }
            // Create-from-transaction: prefill title/amount/date from the SMS transaction.
            if (existing == null && transactionId != null) {
                smsRepo.getById(transactionId)?.let { txn ->
                    val iso = java.time.Instant.ofEpochMilli(txn.timestamp)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                    _state.update {
                        it.copy(
                            title = it.title.ifBlank { txn.merchant },
                            amountText = it.amountText.ifBlank { minorToPlain(txn.amountMinor) },
                            expenseDate = it.expenseDate ?: iso,
                        )
                    }
                }
            }

            val paidForById = existing?.paidFor?.associateBy { it.participantId } ?: emptyMap()

            // Participants come from the group cache; reconcile rows without clobbering edits.
            groupRepo.observeGroup(groupId).collect { group ->
                if (group == null) {
                    _state.update { it.copy(loading = false) }
                    return@collect
                }
                youParticipantId = group.activeParticipantId
                _state.update { s ->
                    val byId = s.rows.associateBy { it.participantId }
                    val rows = group.participants.map { p ->
                        byId[p.id] ?: newRow(p.id, p.name, s.splitMode, s.isEdit, paidForById[p.id])
                    }
                    val paidBy = s.paidById.ifBlank {
                        group.activeParticipantId ?: group.participants.firstOrNull()?.id ?: ""
                    }
                    s.copy(loading = false, participants = group.participants, rows = rows, paidById = paidBy)
                }
            }
        }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value, error = null) }
    fun onAmountChange(value: String) = _state.update { it.copy(amountText = value, error = null) }
    fun onCategoryChange(id: Int) = _state.update { it.copy(categoryId = id) }
    fun onDateChange(iso: String) = _state.update { it.copy(expenseDate = iso, error = null) }
    fun onPaidByChange(id: String) = _state.update { it.copy(paidById = id) }
    fun onSplitModeChange(mode: SplitMode) = _state.update { it.copy(splitMode = mode, error = null) }
    fun onReimbursementChange(value: Boolean) = _state.update { it.copy(isReimbursement = value) }
    fun onNotesChange(value: String) = _state.update { it.copy(notes = value) }
    fun onToggleParticipant(id: String) = updateRow(id) { it.copy(selected = !it.selected) }
    fun onWeightChange(id: String, value: String) = updateRow(id) { it.copy(weightText = value) }
    fun onPercentChange(id: String, value: String) = updateRow(id) { it.copy(percentText = value) }
    fun onParticipantAmountChange(id: String, value: String) = updateRow(id) { it.copy(amountText = value) }

    private val _voicePreview = MutableStateFlow<ai.schism.split.expense.edit.voice.SpokenExpenseDraft?>(null)

    /**
     * The structured draft parsed from the last spoken sentence, awaiting the user's confirmation in
     * [applyVoicePreview] / [dismissVoicePreview]. Null when nothing is pending.
     */
    val voicePreview: StateFlow<ai.schism.split.expense.edit.voice.SpokenExpenseDraft?> = _voicePreview.asStateFlow()

    /**
     * Parse a spoken sentence (e.g. "paid 800 for dinner split with Riya and Sam") into a
     * [ai.schism.split.expense.edit.voice.SpokenExpenseDraft] and stage it in [voicePreview] for the
     * user to confirm — nothing is written to the form until [applyVoicePreview] is called.
     */
    fun previewVoice(text: String) {
        viewModelScope.launch {
            val s = _state.value
            val you = youParticipantId ?: s.paidById.ifBlank { null }
            // Prefer the on-device LLM when a model is loaded; fall back to the regex parser.
            _voicePreview.value = llmParser.parseSpoken(text, s.participants, you)
                ?: parseSpokenExpense(text, s.participants, you)
        }
    }

    /** Apply the staged [voicePreview] draft to the form and clear it. Never submits. */
    fun applyVoicePreview() {
        val draft = _voicePreview.value ?: return
        val you = youParticipantId ?: _state.value.paidById.ifBlank { null }
        applyDraft(draft, you)
        _voicePreview.value = null
    }

    /** Discard the staged [voicePreview] draft without applying it. */
    fun dismissVoicePreview() { _voicePreview.value = null }

    private fun applyDraft(draft: ai.schism.split.expense.edit.voice.SpokenExpenseDraft, you: String?) {
        _state.update { cur ->
            var next = cur
            draft.title?.let { next = next.copy(title = it) }
            draft.amountMinor?.let { next = next.copy(amountText = minorToPlain(it)) }
            draft.payerParticipantId?.let { next = next.copy(paidById = it) }
            val rows = when {
                draft.isPersonal -> next.rows.map { it.copy(selected = it.participantId == you) }
                draft.paidForParticipantIds != null -> {
                    val ids = draft.paidForParticipantIds.toSet()
                    next.rows.map { it.copy(selected = it.participantId in ids) }
                }
                else -> next.rows
            }
            next.copy(rows = rows, error = null)
        }
    }

    private fun updateRow(id: String, transform: (ParticipantRow) -> ParticipantRow) =
        _state.update { s ->
            s.copy(rows = s.rows.map { if (it.participantId == id) transform(it) else it }, error = null)
        }

    /** Build + validate the request (no network), then create or update on success. */
    fun submit(onSaved: () -> Unit) {
        buildExpenseRequest(currentForm()).fold(
            onSuccess = { built ->
                // Stamp the creator: "you" on a new expense, the original creator on an edit.
                val request = built.copy(addedBy = if (expenseId == null) youParticipantId else existingAddedBy)
                viewModelScope.launch {
                    _state.update { it.copy(submitting = true, error = null) }
                    val result = if (expenseId == null) {
                        expenseRepo.createExpense(groupId, request).onSuccess { expense ->
                            // If this came from an SMS transaction, mark it pushed to the group.
                            if (transactionId != null && expense != null) {
                                smsRepo.markPushed(transactionId, groupId, expense.id)
                            }
                        }.map { }
                    } else {
                        expenseRepo.updateExpense(groupId, expenseId, request)
                    }
                    result
                        .onSuccess {
                            _state.update { it.copy(submitting = false) }
                            onSaved()
                        }
                        .onFailure { e ->
                            _state.update { it.copy(submitting = false, error = e.message ?: "Couldn't save expense") }
                        }
                }
            },
            onFailure = { e -> _state.update { it.copy(error = e.message) } },
        )
    }

    /** The receipt draft from a just-completed bill scan, if any (consumed once by the caller). */
    fun peekPendingReceipt(): ai.schism.split.sms.receipt.ReceiptDraft? = pendingReceipt.draft

    /** Apply a scanned bill's total as this expense: title + amount (locale-safe) and clear the pending draft. */
    fun useScannedTotal(draft: ai.schism.split.sms.receipt.ReceiptDraft) {
        onTitleChange(draft.merchant)
        onAmountChange(minorToPlain(draft.totalMinor))
        pendingReceipt.draft = null
    }

    /** Locale-safe minor-units-to-plain-text conversion for display (e.g. in confirmation dialogs). */
    fun amountText(minor: Long): String = minorToPlain(minor)

    /** Delete this expense (edit mode only — the editor is only reachable by its creator). */
    fun delete(onDeleted: () -> Unit) {
        val id = expenseId ?: return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            expenseRepo.deleteExpense(groupId, id)
                .onSuccess {
                    _state.update { it.copy(submitting = false) }
                    onDeleted()
                }
                .onFailure { e ->
                    _state.update { it.copy(submitting = false, error = e.message ?: "Couldn't delete") }
                }
        }
    }

    private fun currentForm(): ExpenseForm {
        val s = _state.value
        return ExpenseForm(
            title = s.title,
            amountMinor = parseAmountToMinor(s.amountText) ?: 0L,
            categoryId = s.categoryId,
            expenseDate = s.expenseDate,
            paidById = s.paidById,
            splitMode = s.splitMode,
            isReimbursement = s.isReimbursement,
            notes = s.notes,
            participants = s.rows.map { r ->
                ParticipantInput(
                    participantId = r.participantId,
                    selected = r.selected,
                    weight = r.weightText.trim().toLongOrNull() ?: 0L,
                    percentBasisPoints = parseAmountToMinor(r.percentText) ?: 0L,
                    amountMinor = parseAmountToMinor(r.amountText) ?: 0L,
                )
            },
        )
    }
}

/** Seeds a fresh row, prefilling per-mode text from an existing [PaidForDto] when editing. */
private fun newRow(
    id: String,
    name: String,
    mode: SplitMode,
    isEdit: Boolean,
    paidFor: PaidForDto?,
): ParticipantRow = ParticipantRow(
    participantId = id,
    name = name,
    // Create mode selects everyone by default; edit mode selects only the original split members.
    selected = if (isEdit) paidFor != null else true,
    weightText = if (mode == SplitMode.BY_SHARES && paidFor != null) paidFor.shares.toString() else "1",
    percentText = if (mode == SplitMode.BY_PERCENTAGE && paidFor != null) bpsToPlain(paidFor.shares) else "",
    amountText = if (mode == SplitMode.BY_AMOUNT && paidFor != null) minorToPlain(paidFor.shares) else "",
)

/** 4200 -> "42.00". Plain decimal for editing (no symbol, no grouping). */
private fun minorToPlain(amount: Long): String {
    val whole = amount / 100
    val frac = (amount % 100).toString().padStart(2, '0')
    return "$whole.$frac"
}

/** 3333 -> "33.33", 5000 -> "50". */
private fun bpsToPlain(bps: Long): String {
    val whole = bps / 100
    val frac = (bps % 100).toInt()
    return if (frac == 0) "$whole" else "$whole.${frac.toString().padStart(2, '0')}"
}
