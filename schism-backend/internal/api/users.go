package api

import (
	"encoding/json"
	"net/http"
)

// registerUser creates an unverified identity from the onboarding fields and returns it. There is no
// GET-by-id endpoint on purpose: email/phone are PII and, without auth, must not be readable by
// anyone holding a user id.
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
	u, err := h.store.CreateUser(r.Context(), d.Name, d.Email, d.Phone)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, u)
}
