package api

import (
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/schism/schism-backend/internal/store"
	"golang.org/x/time/rate"
)

type Handler struct {
	store           *store.Store
	registerLimiter *keyedLimiter
	loginLimiter    *keyedLimiter
	monetization    Monetization
}

// NewRouter builds the API router with monetization off — no paywall, no purchase surface, no ads.
// When logRequests is true, per-request access logging is enabled (intended for dev; keep it off in
// production).
func NewRouter(s *store.Store, logRequests bool, publicHandlers ...http.Handler) http.Handler {
	return NewRouterWithMonetization(s, logRequests, Monetization{}, publicHandlers...)
}

// NewRouterWithMonetization is NewRouter plus the deployment's billing/ads posture.
func NewRouterWithMonetization(s *store.Store, logRequests bool, m Monetization, publicHandlers ...http.Handler) http.Handler {
	h := &Handler{
		store:           s,
		registerLimiter: newKeyedLimiter(rate.Every(20*time.Second), 3, 15*time.Minute),
		loginLimiter:    newKeyedLimiter(rate.Every(12*time.Second), 5, 15*time.Minute),
		monetization:    m,
	}
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(securityHeaders)
	r.Use(recoverPanics)
	if logRequests {
		r.Use(middleware.Logger)
	}
	if len(publicHandlers) > 0 && publicHandlers[0] != nil {
		public := publicHandlers[0]
		for _, path := range []string{"/", "/privacy", "/terms", "/support", "/account-deletion"} {
			r.Handle(path, public)
		}
		r.Handle("/assets/site/*", public)
	}

	r.Get("/health", h.health)

	// Friendly root health check.
	r.Get("/ping", h.ping)

	// Public invite landing (https so messengers linkify it) → bounces into the app.
	r.Get("/g/{groupID}", h.inviteLanding)
	r.Get("/i/{token}", h.participantInviteLanding)
	r.Get("/i/g/{token}", h.groupInviteLanding)

	// Public claim-session landing → bounces into the app's claim screen.
	r.Get("/c/{sid}", h.claimLanding)

	// On-device model download (redirects to the ungated model, or proxies a gated one with HF_TOKEN).
	r.Get("/model", h.modelDownload)

	r.Route("/v1", func(r chi.Router) {
		r.Post("/auth/register", h.authRegister)
		r.Post("/auth/login", h.authLogin)
		r.Get("/models/ocr/manifest", h.ocrModelManifest)
		r.Get("/models/ocr/{version}/{file}", h.ocrModelArtifact)
		r.Head("/models/ocr/{version}/{file}", h.ocrModelArtifact)
		r.Group(func(r chi.Router) {
			r.Use(h.withUser)
			r.Use(h.requireUser)
			r.Get("/categories", h.listCategories)
			r.Get("/dashboard", h.getPersonalDashboard)
			r.Get("/users/me", h.me)
			r.Get("/users/me/groups", h.myGroups)
			r.Delete("/users/me", h.deleteMe)
			r.Post("/auth/logout", h.authLogout)
			r.Get("/entitlement", h.getEntitlement)
			r.Get("/monetization/config", h.monetizationConfig)
			r.Post("/billing/verify", h.verifyPurchase)
			r.Post("/billing/restore", h.restorePurchases)
			r.Route("/groups", func(r chi.Router) {
				r.Post("/", h.createGroup)
				r.Get("/", h.listGroups)
				r.Route("/{groupID}", func(r chi.Router) {
					r.Use(h.requireGroupMember)
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
					r.Post("/participants/{participantID}/invite", h.createParticipantInvite)
					r.Post("/invite-link", h.createGroupInvite)
					r.Delete("/invite-link", h.revokeGroupInvite)
				})
			})
			r.Get("/invites/{token}", h.previewParticipantInvite)
			r.Post("/invites/{token}/redeem", h.redeemParticipantInvite)
			r.Get("/group-invites/{token}", h.previewGroupInvite)
			r.Post("/group-invites/{token}/redeem", h.redeemGroupInvite)
			r.Route("/claim-sessions/{sid}", func(r chi.Router) {
				r.Get("/", h.getClaimSession)
				r.Put("/claims", h.putClaims)
				r.Post("/finalize", h.finalizeClaimSession)
				r.Post("/cancel", h.cancelClaimSession)
				r.Put("/ready", h.setReady)
				r.Patch("/items", h.editClaimItems)
			})
		})
	})
	return r
}
