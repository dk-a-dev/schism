package api

import (
	"errors"
	"net/http"
	"net/mail"
	"strings"
	"unicode/utf8"

	"github.com/schism/schism-backend/internal/store"
)

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
	if !h.registerLimiter.Allow(limiterKey(clientIP(r))) {
		writeRateLimited(w)
		return
	}
	var d struct {
		Name     string `json:"name"`
		Email    string `json:"email"`
		Password string `json:"password"`
		Phone    string `json:"phone"`
	}
	if !decodeJSON(w, r, &d) {
		return
	}
	d.Name = strings.TrimSpace(d.Name)
	d.Email = strings.ToLower(strings.TrimSpace(d.Email))
	parsedEmail, emailErr := mail.ParseAddress(d.Email)
	if emailErr != nil || parsedEmail.Address != d.Email || len(d.Email) > 120 {
		writeErr(w, http.StatusBadRequest, "enter a valid email")
		return
	}
	if utf8.RuneCountInString(d.Name) < 1 || utf8.RuneCountInString(d.Name) > 100 {
		writeErr(w, http.StatusBadRequest, "name must be between 1 and 100 characters")
		return
	}
	if len(d.Password) < 8 || len(d.Password) > 128 {
		writeErr(w, http.StatusBadRequest, "password must be between 8 and 128 characters")
		return
	}
	if len(d.Phone) > 32 {
		writeErr(w, http.StatusBadRequest, "phone must not exceed 32 characters")
		return
	}
	u, token, err := h.store.RegisterUser(r.Context(), d.Name, d.Email, d.Password, d.Phone)
	if errors.Is(err, store.ErrEmailTaken) {
		writeErr(w, http.StatusConflict, "that email is already registered")
		return
	}
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, authResponse{u.ID, u.Name, u.Email, token})
}

func (h *Handler) authLogin(w http.ResponseWriter, r *http.Request) {
	var d struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if !decodeJSON(w, r, &d) {
		return
	}
	d.Email = strings.ToLower(strings.TrimSpace(d.Email))
	if len(d.Email) == 0 || len(d.Email) > 120 || len(d.Password) == 0 || len(d.Password) > 128 {
		writeErr(w, http.StatusBadRequest, "invalid_credentials")
		return
	}
	if !h.loginLimiter.Allow(limiterKey(clientIP(r), d.Email)) {
		writeRateLimited(w)
		return
	}
	u, token, err := h.store.LoginUser(r.Context(), d.Email, d.Password)
	if errors.Is(err, store.ErrInvalidLogin) {
		writeErr(w, http.StatusUnauthorized, "invalid email or password")
		return
	}
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, authResponse{u.ID, u.Name, u.Email, token})
}

// authLogout ends the caller's CURRENT session only (deletes just the tokens row matching the
// bearer token on this request) — other devices/logins for the same account stay signed in.
func (h *Handler) authLogout(w http.ResponseWriter, r *http.Request) {
	u := userFromContext(r.Context())
	if u == nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if err := h.store.DeleteToken(r.Context(), rawTokenFromRequest(r)); err != nil {
		writeInternalError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
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
		writeInternalError(w, r, err)
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
		writeInternalError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
