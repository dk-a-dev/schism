package ai.schism.split.core.db

import ai.schism.split.core.net.ExpenseDto
import ai.schism.split.core.net.GroupDto
import ai.schism.split.expense.data.Expense
import ai.schism.split.expense.data.PaidFor
import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.Participant

// ---- DTO -> entity (cache writes) ----

fun GroupDto.toEntity(): GroupEntity = GroupEntity(
    id = id,
    name = name,
    information = information,
    currency = currency,
    currencyCode = currencyCode,
    createdAt = createdAt,
)

fun GroupDto.participantEntities(): List<ParticipantEntity> =
    participants.map { ParticipantEntity(id = it.id, groupId = id, name = it.name, userId = it.userId) }

fun ExpenseDto.toEntity(): ExpenseEntity = ExpenseEntity(
    id = id,
    groupId = groupId,
    title = title,
    amount = amount,
    categoryId = categoryId,
    expenseDate = expenseDate,
    paidById = paidById,
    splitMode = splitMode,
    isReimbursement = isReimbursement,
    notes = notes,
    createdAt = createdAt,
    addedBy = addedBy,
)

fun ExpenseDto.paidForEntities(): List<PaidForEntity> =
    paidFor.map { PaidForEntity(expenseId = id, participantId = it.participantId, shares = it.shares) }

fun ExpenseDto.toWithPaidFor(): ExpenseWithPaidFor =
    ExpenseWithPaidFor(expense = toEntity(), paidFor = paidForEntities())

// ---- entity -> domain (reads) ----

fun GroupWithParticipants.toDomain(): Group = Group(
    id = group.id,
    name = group.name,
    information = group.information,
    currency = group.currency,
    currencyCode = group.currencyCode,
    participants = participants.map { Participant(it.id, it.groupId, it.name, it.userId) },
    isFavorite = group.isFavorite,
    activeParticipantId = group.activeParticipantId,
)

fun ExpenseWithPaidFor.toDomain(): Expense = Expense(
    id = expense.id,
    groupId = expense.groupId,
    title = expense.title,
    amount = expense.amount,
    categoryId = expense.categoryId,
    expenseDate = expense.expenseDate,
    paidById = expense.paidById,
    splitMode = expense.splitMode,
    isReimbursement = expense.isReimbursement,
    notes = expense.notes,
    addedBy = expense.addedBy,
    paidFor = paidFor.map { PaidFor(it.participantId, it.shares) },
)
