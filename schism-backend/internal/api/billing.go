package api

import (
	"context"
	"errors"
	"log"
	"net/http"
	"time"

	"github.com/schism/schism-backend/internal/billing"
	"github.com/schism/schism-backend/internal/store"
)

// Monetization is the deployment's billing/ads posture. Its zero value is the safe default: every
// switch off, no verifier, so a router built without it gates nothing and sells nothing.
type Monetization struct {
	PlusEnabled      bool
	AdsEnabled       bool
	PurchasesEnabled bool
	// BillingTokenKey is the 32-byte AES-256-GCM key protecting stored purchase tokens.
	BillingTokenKey []byte
	Verifier        billing.Verifier
}

// purchaseRefreshInterval is how stale a stored purchase may be before /v1/entitlement re-checks it
// with Play. Records past their recorded expiry are re-checked regardless.
const purchaseRefreshInterval = 6 * time.Hour

func (h *Handler) monetizationConfig(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, monetizationConfigDTO{
		PlusEnabled:      h.monetization.PlusEnabled,
		AdsEnabled:       h.monetization.AdsEnabled,
		PurchasesEnabled: h.monetization.PurchasesEnabled,
		FreeLiveSplits:   store.FreeLiveSplitsPerMonth,
		// Mirrors exactly the condition the router uses to register /v1/receipts/extract.
		ReceiptCloudEnabled: h.extractor != nil,
	})
}

// getEntitlement reports the caller's server-owned Plus state plus their remaining free allowance.
// It opportunistically refreshes stale purchase records so a cancellation or lapse is picked up
// without a client push.
func (h *Handler) getEntitlement(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r.Context())
	now := time.Now().UTC()
	h.refreshPurchases(r, user.ID, now)
	h.writeEntitlement(w, r, user.ID, now)
}

// verifyPurchase accepts a Play purchase token, verifies it server-side, and records the resulting
// entitlement. Nothing the client sends about state, expiry, or product is trusted.
func (h *Handler) verifyPurchase(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r.Context())
	var d verifyPurchaseDTO
	if !decodeJSON(w, r, &d) {
		return
	}
	if d.PurchaseToken == "" || len(d.PurchaseToken) > 4096 {
		writeErr(w, http.StatusBadRequest, "invalid_purchase_token")
		return
	}
	if !h.monetization.PurchasesEnabled || h.monetization.Verifier == nil {
		writeErr(w, http.StatusServiceUnavailable, "purchases_disabled")
		return
	}
	now := time.Now().UTC()
	if !h.recordPurchase(w, r, user.ID, d.ProductID, d.PurchaseToken, now) {
		return
	}
	h.writeEntitlement(w, r, user.ID, now)
}

// restorePurchases re-verifies every purchase already on file for the caller. This is what a
// reinstall or a Play account switch calls; it needs no request body because the tokens are ours.
func (h *Handler) restorePurchases(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r.Context())
	if !h.monetization.PurchasesEnabled || h.monetization.Verifier == nil {
		writeErr(w, http.StatusServiceUnavailable, "purchases_disabled")
		return
	}
	now := time.Now().UTC()
	// A negative staleAfter re-checks every record the user holds, however recently verified —
	// restore is an explicit user action, not an opportunistic refresh.
	h.refreshStale(r.Context(), r, user.ID, now, -time.Minute)
	h.writeEntitlement(w, r, user.ID, now)
}

// recordPurchase verifies one token and persists the outcome, writing the error response itself when
// verification refuses. It returns false when it has already written a response.
func (h *Handler) recordPurchase(w http.ResponseWriter, r *http.Request, userID, productID, token string, now time.Time) bool {
	verified, err := h.monetization.Verifier.Verify(r.Context(), billing.PackageName, productID, token)
	switch {
	case errors.Is(err, billing.ErrProductMismatch), errors.Is(err, billing.ErrNotFound):
		// Sanitized: never echo the token or Google's message back to the caller.
		writeErr(w, http.StatusBadRequest, "purchase_not_verified")
		return false
	case errors.Is(err, billing.ErrUnavailable):
		writeErr(w, http.StatusBadGateway, "purchase_verification_unavailable")
		return false
	case err != nil:
		writeInternalError(w, r, err)
		return false
	}
	rec, err := h.store.UpsertPurchase(r.Context(), h.monetization.BillingTokenKey, userID, token, store.PurchaseRecord{
		ProductID:    verified.ProductID,
		State:        string(verified.State),
		ExpiresAt:    verified.ExpiresAt,
		AutoRenewing: verified.AutoRenewing,
		Acknowledged: verified.Acknowledged,
	})
	if errors.Is(err, store.ErrPurchaseOwnedByAnotherUser) {
		writeErr(w, http.StatusConflict, "purchase_already_linked")
		return false
	}
	if err != nil {
		writeInternalError(w, r, err)
		return false
	}
	if verified.State == billing.StatePurchased && !verified.Acknowledged {
		h.acknowledge(r, rec.ID, verified.ProductID, token)
	}
	return true
}

// acknowledge confirms the purchase with Play, retrying a bounded number of times with exponential
// backoff. Failure is logged by record id only and never blocks the entitlement response, because
// Play allows three days to acknowledge.
func (h *Handler) acknowledge(r *http.Request, recordID, productID, token string) {
	const attempts = 3
	var err error
	for attempt, delay := 0, 50*time.Millisecond; attempt < attempts; attempt, delay = attempt+1, delay*2 {
		if err = h.monetization.Verifier.Acknowledge(r.Context(), productID, token); err == nil {
			if markErr := h.store.MarkAcknowledged(r.Context(), recordID); markErr != nil {
				log.Printf("request_id=%q purchase_id=%q acknowledge_persist_failed", requestID(r), recordID)
			}
			return
		}
		if attempt == attempts-1 {
			break
		}
		select {
		case <-r.Context().Done():
			return
		case <-time.After(delay):
		}
	}
	log.Printf("request_id=%q purchase_id=%q acknowledge_failed_type=%T", requestID(r), recordID, err)
}

// refreshPurchases re-verifies records older than purchaseRefreshInterval or past their expiry.
func (h *Handler) refreshPurchases(r *http.Request, userID string, now time.Time) {
	if !h.monetization.PurchasesEnabled || h.monetization.Verifier == nil {
		return
	}
	h.refreshStale(r.Context(), r, userID, now, purchaseRefreshInterval)
}

// refreshStale re-verifies the user's stale purchase records in place. Every failure is swallowed
// after logging: a transient Google outage must never revoke an entitlement we already granted.
func (h *Handler) refreshStale(ctx context.Context, r *http.Request, userID string, now time.Time, staleAfter time.Duration) {
	records, tokens, err := h.store.StalePurchases(ctx, h.monetization.BillingTokenKey, userID, now, staleAfter)
	if err != nil {
		log.Printf("request_id=%q purchase_refresh_load_failed_type=%T", requestID(r), err)
		return
	}
	for i, rec := range records {
		verified, err := h.monetization.Verifier.Verify(ctx, billing.PackageName, rec.ProductID, tokens[i])
		if err != nil {
			log.Printf("request_id=%q purchase_id=%q refresh_failed_type=%T", requestID(r), rec.ID, err)
			continue
		}
		if _, err := h.store.UpsertPurchase(ctx, h.monetization.BillingTokenKey, userID, tokens[i], store.PurchaseRecord{
			ProductID:    verified.ProductID,
			State:        string(verified.State),
			ExpiresAt:    verified.ExpiresAt,
			AutoRenewing: verified.AutoRenewing,
			Acknowledged: verified.Acknowledged,
		}); err != nil {
			log.Printf("request_id=%q purchase_id=%q refresh_persist_failed_type=%T", requestID(r), rec.ID, err)
		}
	}
}

func (h *Handler) writeEntitlement(w http.ResponseWriter, r *http.Request, userID string, now time.Time) {
	entitlement, err := h.store.EntitlementStatus(r.Context(), userID, now)
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	usage, err := h.store.LiveSplitUsage(r.Context(), userID, now)
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, entitlementDTO{
		Active:         entitlement.Active,
		ProductID:      entitlement.ProductID,
		ExpiresAt:      entitlement.ExpiresAt,
		AutoRenewing:   entitlement.AutoRenewing,
		FreeLiveSplits: plusRequiredDTO{Used: usage.Used, Limit: usage.Limit, ResetsAt: usage.ResetsAt},
	})
}
