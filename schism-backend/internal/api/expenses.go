package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/split"
	"github.com/schism/schism-backend/internal/store"
)

// actor returns a pointer to the participant id for activity logging, or nil when empty
// (e.g. legacy expenses without an addedBy).
func actor(participantID string) *string {
	if participantID == "" {
		return nil
	}
	return &participantID
}

func toSplitExpense(in store.ExpenseInput) split.Expense {
	pf := make([]split.PaidFor, len(in.PaidFor))
	for i, p := range in.PaidFor {
		pf[i] = split.PaidFor{ParticipantID: p.ParticipantID, Shares: p.Shares}
	}
	return split.Expense{Amount: in.Amount, PaidByID: in.PaidByID, PaidFor: pf, SplitMode: split.SplitMode(in.SplitMode)}
}

func (h *Handler) validateExpenseParticipants(w http.ResponseWriter, r *http.Request, groupID string, in store.ExpenseInput) bool {
	group, err := h.store.GetGroup(r.Context(), groupID)
	if err != nil {
		writeInternalError(w, r, err)
		return false
	}
	if group == nil {
		writeErr(w, http.StatusNotFound, "group not found")
		return false
	}
	allowed := make(map[string]struct{}, len(group.Participants))
	for _, participant := range group.Participants {
		allowed[participant.ID] = struct{}{}
	}
	if _, ok := allowed[in.PaidByID]; !ok {
		writeErr(w, http.StatusBadRequest, "paidById must belong to this group")
		return false
	}
	for _, share := range in.PaidFor {
		if _, ok := allowed[share.ParticipantID]; !ok {
			writeErr(w, http.StatusBadRequest, "all paidFor participants must belong to this group")
			return false
		}
	}
	return true
}

func (h *Handler) createExpense(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	var d expenseFormDTO
	if !decodeJSON(w, r, &d) {
		return
	}
	in := d.toInput()
	in.AddedBy = participantFromContext(r.Context())
	if err := split.ValidateExpense(toSplitExpense(in)); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	if !h.validateExpenseParticipants(w, r, groupID, in) {
		return
	}
	e, err := h.store.CreateExpense(r.Context(), groupID, in, r.Header.Get("Idempotency-Key"))
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	eid := e.ID
	_ = h.store.LogActivity(r.Context(), groupID, "CREATE_EXPENSE", actor(e.AddedBy), &eid, e.Title)
	writeJSON(w, http.StatusCreated, e)
}

func (h *Handler) listExpenses(w http.ResponseWriter, r *http.Request) {
	list, err := h.store.ListExpenses(r.Context(), chi.URLParam(r, "groupID"))
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, list)
}

func (h *Handler) getExpense(w http.ResponseWriter, r *http.Request) {
	e, err := h.store.GetExpense(r.Context(), chi.URLParam(r, "groupID"), chi.URLParam(r, "expenseID"))
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	if e == nil {
		writeErr(w, http.StatusNotFound, "expense not found")
		return
	}
	writeJSON(w, http.StatusOK, e)
}

func (h *Handler) updateExpense(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	expenseID := chi.URLParam(r, "expenseID")
	var d expenseFormDTO
	if !decodeJSON(w, r, &d) {
		return
	}
	in := d.toInput()
	in.AddedBy = participantFromContext(r.Context())
	if err := split.ValidateExpense(toSplitExpense(in)); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	if !h.validateExpenseParticipants(w, r, groupID, in) {
		return
	}
	e, err := h.store.UpdateExpense(r.Context(), groupID, expenseID, in)
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	if e == nil {
		writeErr(w, http.StatusNotFound, "expense not found")
		return
	}
	eid := e.ID
	_ = h.store.LogActivity(r.Context(), groupID, "UPDATE_EXPENSE", actor(e.AddedBy), &eid, e.Title)
	writeJSON(w, http.StatusOK, e)
}

func (h *Handler) deleteExpense(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	expenseID := chi.URLParam(r, "expenseID")
	// Capture the title before deletion so the activity log can describe what was removed.
	e, err := h.store.GetExpense(r.Context(), groupID, expenseID)
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	if e == nil {
		writeErr(w, http.StatusNotFound, "expense not found")
		return
	}
	ok, err := h.store.DeleteExpense(r.Context(), groupID, expenseID)
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	if !ok {
		writeErr(w, http.StatusNotFound, "expense not found")
		return
	}
	_ = h.store.LogActivity(r.Context(), groupID, "DELETE_EXPENSE", actor(participantFromContext(r.Context())), &expenseID, e.Title)
	w.WriteHeader(http.StatusNoContent)
}
