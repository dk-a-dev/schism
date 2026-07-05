package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/split"
)

func (h *Handler) getBalances(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	expenses, err := h.store.SplitExpenses(r.Context(), groupID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	balances := split.GetBalances(expenses)
	reimb := split.GetSuggestedReimbursements(balances)
	public := split.GetPublicBalances(reimb)
	if reimb == nil {
		reimb = []split.Reimbursement{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"balances":       public,
		"reimbursements": reimb,
	})
}
