package api

import (
	"context"
	"net/http"
	"strings"

	"github.com/schism/schism-backend/internal/store"
)

type ctxKey int

const userKey ctxKey = iota

// userFromContext returns the authenticated caller, or nil when the request carried no valid token.
func userFromContext(ctx context.Context) *store.User {
	u, _ := ctx.Value(userKey).(*store.User)
	return u
}

// withUser resolves an optional `Authorization: Bearer <token>` to a user and stashes it in the
// request context. It never rejects: groups/expenses stay reachable by id (the invite model), so an
// absent or invalid token simply leaves no user in context. Endpoints that need identity check for it.
func (h *Handler) withUser(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw := r.Header.Get("Authorization")
		token := strings.TrimPrefix(raw, "Bearer ")
		if token != "" && token != raw {
			if u, err := h.store.UserByToken(r.Context(), token); err == nil && u != nil {
				r = r.WithContext(context.WithValue(r.Context(), userKey, u))
			}
		}
		next.ServeHTTP(w, r)
	})
}
