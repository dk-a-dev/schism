package api

import (
	"encoding/json"
	"net/http"
	"time"
)

// registerUser creates an unverified identity, mints its secret bearer token, and returns both. The
// token is present ONLY on this response — the client stores it and sends it as `Authorization:
// Bearer <token>` thereafter. There is no GET-by-id endpoint on purpose: email/phone are PII and,
// without auth, must not be readable by anyone holding a user id.
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
	u, token, err := h.store.CreateUser(r.Context(), d.Name, d.Email, d.Phone)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, struct {
		ID        string    `json:"id"`
		Name      string    `json:"name"`
		Email     string    `json:"email"`
		Phone     string    `json:"phone"`
		CreatedAt time.Time `json:"createdAt"`
		Token     string    `json:"token"`
	}{u.ID, u.Name, u.Email, u.Phone, u.CreatedAt, token})
}

// me returns the authenticated caller (resolved from the bearer token by withUser). It answers 401
// when unauthenticated — this is how a client verifies "who am I" without exposing PII to id holders.
func (h *Handler) me(w http.ResponseWriter, r *http.Request) {
	u := userFromContext(r.Context())
	if u == nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	writeJSON(w, http.StatusOK, u)
}
