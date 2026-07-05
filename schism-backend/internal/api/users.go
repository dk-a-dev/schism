package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/schism/schism-backend/internal/store"
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
// authResponse is the shape returned by register/login: the user plus a fresh bearer token.
type authResponse struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Email string `json:"email"`
	Token string `json:"token"`
}

func (h *Handler) authRegister(w http.ResponseWriter, r *http.Request) {
	var d struct {
		Name     string `json:"name"`
		Email    string `json:"email"`
		Password string `json:"password"`
		Phone    string `json:"phone"`
	}
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	if !strings.Contains(d.Email, "@") {
		writeErr(w, http.StatusBadRequest, "enter a valid email")
		return
	}
	if len(d.Password) < 6 {
		writeErr(w, http.StatusBadRequest, "password must be at least 6 characters")
		return
	}
	u, token, err := h.store.RegisterUser(r.Context(), strings.TrimSpace(d.Name), d.Email, d.Password, d.Phone)
	if errors.Is(err, store.ErrEmailTaken) {
		writeErr(w, http.StatusConflict, "that email is already registered")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, authResponse{u.ID, u.Name, u.Email, token})
}

func (h *Handler) authLogin(w http.ResponseWriter, r *http.Request) {
	var d struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	u, token, err := h.store.LoginUser(r.Context(), d.Email, d.Password)
	if errors.Is(err, store.ErrInvalidLogin) {
		writeErr(w, http.StatusUnauthorized, "invalid email or password")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, authResponse{u.ID, u.Name, u.Email, token})
}

func (h *Handler) me(w http.ResponseWriter, r *http.Request) {
	u := userFromContext(r.Context())
	if u == nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	writeJSON(w, http.StatusOK, u)
}

// myGroups lists ids of groups the caller belongs to (participants linked to their account —
// including ones claimed by phone), so a fresh login/device can restore its groups.
func (h *Handler) myGroups(w http.ResponseWriter, r *http.Request) {
	u := userFromContext(r.Context())
	if u == nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	ids, err := h.store.GroupIDsForUser(r.Context(), u.ID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string][]string{"groupIds": ids})
}

func (h *Handler) deleteMe(w http.ResponseWriter, r *http.Request) {
	u := userFromContext(r.Context())
	if u == nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if err := h.store.DeleteUser(r.Context(), u.ID); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
