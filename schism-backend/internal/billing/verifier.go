// Package billing verifies Google Play subscription purchases. The Verifier seam exists so the API
// layer never links against a Google client in tests, and so the Play transport can be swapped
// without touching entitlement logic.
package billing

import (
	"context"
	"errors"
	"time"
)

// State is the entitlement-relevant outcome of verifying a purchase. Only StatePurchased grants Plus.
type State string

const (
	StatePending   State = "PENDING"
	StatePurchased State = "PURCHASED"
	StateExpired   State = "EXPIRED"
	StateRefunded  State = "REFUNDED"
)

// PackageName is the only Android package whose purchases are accepted.
const PackageName = "ai.schism.split"

// ProductID is the only subscription product that grants Plus.
const ProductID = "schism_plus"

// VerifiedPurchase is what Google says about a purchase token. Every field is server-derived; no part
// of it may come from a client request body.
type VerifiedPurchase struct {
	ProductID    string
	State        State
	ExpiresAt    time.Time
	AutoRenewing bool
	Acknowledged bool
}

// Verifier talks to the purchase authority. Acknowledge confirms receipt of a purchase so Play does
// not auto-refund it.
type Verifier interface {
	Verify(ctx context.Context, packageName, productID, purchaseToken string) (VerifiedPurchase, error)
	Acknowledge(ctx context.Context, productID, purchaseToken string) error
}

// ErrNotFound means the authority has no such purchase — a permanent rejection.
var ErrNotFound = errors.New("purchase not found")

// ErrUnavailable means the authority could not be reached or failed transiently. Callers must keep
// any existing entitlement rather than revoking it.
var ErrUnavailable = errors.New("purchase verification unavailable")

// ErrProductMismatch means the token is real but is not the Schism Plus subscription.
var ErrProductMismatch = errors.New("purchase product mismatch")

// Check rejects the requests that must never reach the authority at all: a package or product other
// than Schism Plus. It is the single place both the real and fake verifiers agree on.
func Check(packageName, productID string) error {
	if packageName != PackageName || productID != ProductID {
		return ErrProductMismatch
	}
	return nil
}
