package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

func (h *Handler) listActivities(w http.ResponseWriter, r *http.Request) {
	acts, err := h.store.ListActivities(r.Context(), chi.URLParam(r, "groupID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, acts)
}
