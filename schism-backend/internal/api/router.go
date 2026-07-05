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

	// Public invite landing (https so messengers linkify it) → bounces into the app.
	r.Get("/g/{groupID}", h.inviteLanding)

	r.Route("/v1", func(r chi.Router) {
		r.Use(h.withUser)
		r.Get("/categories", h.listCategories)
		r.Get("/dashboard", h.getPersonalDashboard)
		r.Get("/users/me", h.me)
		r.Post("/users", h.registerUser)
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
			})
		})
	})
	return r
}
