package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/schism/schism-backend/internal/store"
)

type Handler struct{ store *store.Store }

// NewRouter builds the API router. When logRequests is true, per-request access logging is
// enabled (intended for dev; keep it off in production).
func NewRouter(s *store.Store, logRequests bool) http.Handler {
	h := &Handler{store: s}
	r := chi.NewRouter()
	r.Use(middleware.Recoverer)
	if logRequests {
		r.Use(middleware.RequestID)
		r.Use(middleware.Logger)
	}

	// Friendly root health check.
	r.Get("/ping", h.ping)

	// Public invite landing (https so messengers linkify it) → bounces into the app.
	r.Get("/g/{groupID}", h.inviteLanding)

	// On-device model download (redirects to the ungated model, or proxies a gated one with HF_TOKEN).
	r.Get("/model", h.modelDownload)

	r.Route("/v1", func(r chi.Router) {
		r.Use(h.withUser)
		r.Get("/categories", h.listCategories)
		r.Get("/dashboard", h.getPersonalDashboard)
		r.Get("/users/me", h.me)
		r.Get("/users/me/groups", h.myGroups)
		r.Delete("/users/me", h.deleteMe)
		r.Post("/users", h.registerUser)
		r.Post("/auth/register", h.authRegister)
		r.Post("/auth/login", h.authLogin)
		r.Route("/groups", func(r chi.Router) {
			r.Post("/", h.createGroup)
			r.Get("/", h.listGroups)
			r.Route("/{groupID}", func(r chi.Router) {
				r.Get("/", h.getGroup)
				r.Put("/", h.updateGroup)
				r.Get("/details", h.getGroupDetails)
				r.Get("/balances", h.getBalances)
				r.Get("/activities", h.listActivities)
				r.Get("/stats", h.getStats)
				r.Get("/dashboard", h.getGroupDashboard)
				r.Route("/expenses", func(r chi.Router) {
					r.Get("/", h.listExpenses)
					r.Post("/", h.createExpense)
					r.Get("/{expenseID}", h.getExpense)
					r.Put("/{expenseID}", h.updateExpense)
					r.Delete("/{expenseID}", h.deleteExpense)
				})
				r.Post("/claim-sessions", h.createClaimSession)
			})
		})
		r.Route("/claim-sessions/{sid}", func(r chi.Router) {
			r.Get("/", h.getClaimSession)
			r.Put("/claims", h.putClaims)
			r.Post("/finalize", h.finalizeClaimSession)
			r.Post("/cancel", h.cancelClaimSession)
			r.Patch("/items", h.editClaimItems)
		})
	})
	return r
}
