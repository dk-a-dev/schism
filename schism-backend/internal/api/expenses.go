package api

import (
	"encoding/json"
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

func (h *Handler) createExpense(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	var d expenseFormDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	in := d.toInput()
	if err := split.ValidateExpense(toSplitExpense(in)); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	g, err := h.store.GetGroup(r.Context(), groupID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if g == nil {
		writeErr(w, http.StatusNotFound, "group not found")
		return
	}
	e, err := h.store.CreateExpense(r.Context(), groupID, in, r.Header.Get("Idempotency-Key"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	eid := e.ID
	_ = h.store.LogActivity(r.Context(), groupID, "CREATE_EXPENSE", actor(e.AddedBy), &eid, e.Title)
	writeJSON(w, http.StatusCreated, e)
}

func (h *Handler) listExpenses(w http.ResponseWriter, r *http.Request) {
	list, err := h.store.ListExpenses(r.Context(), chi.URLParam(r, "groupID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, list)
}

func (h *Handler) getExpense(w http.ResponseWriter, r *http.Request) {
	e, err := h.store.GetExpense(r.Context(), chi.URLParam(r, "groupID"), chi.URLParam(r, "expenseID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
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
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	in := d.toInput()
	if err := split.ValidateExpense(toSplitExpense(in)); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	e, err := h.store.UpdateExpense(r.Context(), groupID, expenseID, in)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
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
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if e == nil {
		writeErr(w, http.StatusNotFound, "expense not found")
		return
	}
	ok, err := h.store.DeleteExpense(r.Context(), groupID, expenseID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if !ok {
		writeErr(w, http.StatusNotFound, "expense not found")
		return
	}
	_ = h.store.LogActivity(r.Context(), groupID, "DELETE_EXPENSE", actor(e.AddedBy), &expenseID, e.Title)
	w.WriteHeader(http.StatusNoContent)
}
