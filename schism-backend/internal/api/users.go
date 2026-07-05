package api

import (
	"encoding/json"
	"net/http"

	"github.com/go-chi/chi/v5"
)

func (h *Handler) registerUser(w http.ResponseWriter, r *http.Request) {
	var d struct {
		Name  string `json:"name"`
		Email string `json:"email"`
		Phone string `json:"phone"`
	}
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	u, err := h.store.UpsertUser(r.Context(), d.Name, d.Email, d.Phone)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, u)
}

func (h *Handler) getUser(w http.ResponseWriter, r *http.Request) {
	u, err := h.store.GetUser(r.Context(), chi.URLParam(r, "userID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if u == nil {
		writeErr(w, http.StatusNotFound, "user not found")
		return
	}
	writeJSON(w, http.StatusOK, u)
}
