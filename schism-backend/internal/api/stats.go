package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

func (h *Handler) getStats(w http.ResponseWriter, r *http.Request) {
	expenses, err := h.store.ListExpenses(r.Context(), chi.URLParam(r, "groupID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	var totalSpent int64
	for _, e := range expenses {
		if !e.IsReimbursement {
			totalSpent += e.Amount
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"totalGroupSpending": totalSpent,
		"expenseCount":       len(expenses),
	})
}
