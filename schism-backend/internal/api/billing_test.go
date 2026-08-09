package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/schism/schism-backend/internal/billing"
	"github.com/schism/schism-backend/internal/id"
	"github.com/schism/schism-backend/internal/store"
	"github.com/stretchr/testify/require"
)

// fakeVerifier stands in for Google Play. Tests set what Verify should answer for a token; anything
// unregistered is "not found", the same permanent rejection Play gives for a bogus token.
type fakeVerifier struct {
	mu           sync.Mutex
	results      map[string]billing.VerifiedPurchase
	errs         map[string]error
	acknowledged []string
	ackErr       error
	ackCalls     int
}

func newFakeVerifier() *fakeVerifier {
	return &fakeVerifier{results: map[string]billing.VerifiedPurchase{}, errs: map[string]error{}}
}

func (f *fakeVerifier) set(token string, v billing.VerifiedPurchase) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.results[token] = v
}

func (f *fakeVerifier) fail(token string, err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.errs[token] = err
}

func (f *fakeVerifier) Verify(_ context.Context, packageName, productID, purchaseToken string) (billing.VerifiedPurchase, error) {
	if err := billing.Check(packageName, productID); err != nil {
		return billing.VerifiedPurchase{}, err
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if err, ok := f.errs[purchaseToken]; ok {
		return billing.VerifiedPurchase{}, err
	}
	v, ok := f.results[purchaseToken]
	if !ok {
		return billing.VerifiedPurchase{}, billing.ErrNotFound
	}
	return v, nil
}

func (f *fakeVerifier) failAck(err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.ackErr = err
}

// acks returns the tokens acknowledged so far and the total number of attempts.
func (f *fakeVerifier) acks() ([]string, int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.acknowledged...), f.ackCalls
}

func (f *fakeVerifier) Acknowledge(_ context.Context, _, purchaseToken string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.ackCalls++
	if f.ackErr != nil {
		return f.ackErr
	}
	f.acknowledged = append(f.acknowledged, purchaseToken)
	return nil
}

var testBillingKey = bytes.Repeat([]byte{4}, 32)

// newMonetizedServer builds a server with the given monetization posture, returning it alongside the
// store so tests can assert or seed server-owned state directly.
func newMonetizedServer(t *testing.T, m Monetization) (*httptest.Server, *store.Store) {
	t.Helper()
	url := testURL(t)
	require.NoError(t, store.RunMigrations(url))
	pool, err := store.NewPool(context.Background(), url)
	require.NoError(t, err)
	t.Cleanup(pool.Close)
	s := store.NewStore(pool)
	srv := httptest.NewServer(NewRouterWithMonetization(s, false, m))
	t.Cleanup(srv.Close)
	return srv, s
}

// purchasesOn is the fully enabled posture: Plus gate on, purchases on, fake Play.
func purchasesOn(v billing.Verifier) Monetization {
	return Monetization{
		PlusEnabled: true, PurchasesEnabled: true, AdsEnabled: true,
		BillingTokenKey: testBillingKey, Verifier: v,
	}
}

func activePurchase(expiresIn time.Duration) billing.VerifiedPurchase {
	return billing.VerifiedPurchase{
		ProductID: billing.ProductID, State: billing.StatePurchased,
		ExpiresAt: time.Now().UTC().Add(expiresIn), AutoRenewing: true,
	}
}

func decodeEntitlement(t *testing.T, resp *http.Response) entitlementDTO {
	t.Helper()
	var got entitlementDTO
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&got))
	return got
}

func TestMonetizationConfigDefaultsToEverythingOff(t *testing.T) {
	srv := newTestServer(t)
	_, token := registerUserToken(t, srv.URL, "Dev", "cfg-"+id.New()+"@example.com", "")
	resp := authRequest(t, http.MethodGet, srv.URL+"/v1/monetization/config", token, "")
	require.Equal(t, http.StatusOK, resp.StatusCode)
	var got monetizationConfigDTO
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&got))
	require.False(t, got.PlusEnabled)
	require.False(t, got.AdsEnabled)
	require.False(t, got.PurchasesEnabled)
	require.Equal(t, store.FreeLiveSplitsPerMonth, got.FreeLiveSplits)
}

func TestMonetizationConfigReflectsDeployment(t *testing.T) {
	srv, _ := newMonetizedServer(t, purchasesOn(newFakeVerifier()))
	_, token := registerUserToken(t, srv.URL, "Dev", "cfg2-"+id.New()+"@example.com", "")
	resp := authRequest(t, http.MethodGet, srv.URL+"/v1/monetization/config", token, "")
	var got monetizationConfigDTO
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&got))
	require.True(t, got.PlusEnabled)
	require.True(t, got.AdsEnabled)
	require.True(t, got.PurchasesEnabled)
}

func TestBillingEndpointsRequireAuthentication(t *testing.T) {
	srv, _ := newMonetizedServer(t, purchasesOn(newFakeVerifier()))
	for _, c := range []struct{ method, path, body string }{
		{http.MethodGet, "/v1/entitlement", ""},
		{http.MethodGet, "/v1/monetization/config", ""},
		{http.MethodPost, "/v1/billing/verify", `{"productId":"schism_plus","purchaseToken":"t"}`},
		{http.MethodPost, "/v1/billing/restore", `{}`},
	} {
		resp := authRequest(t, c.method, srv.URL+c.path, "", c.body)
		require.Equal(t, http.StatusUnauthorized, resp.StatusCode, c.path)
	}
}

func TestEntitlementStartsFreeWithFullAllowance(t *testing.T) {
	srv, _ := newMonetizedServer(t, purchasesOn(newFakeVerifier()))
	_, token := registerUserToken(t, srv.URL, "Dev", "ent-"+id.New()+"@example.com", "")
	resp := authRequest(t, http.MethodGet, srv.URL+"/v1/entitlement", token, "")
	require.Equal(t, http.StatusOK, resp.StatusCode)
	got := decodeEntitlement(t, resp)
	require.False(t, got.Active)
	require.Equal(t, 0, got.FreeLiveSplits.Used)
	require.Equal(t, store.FreeLiveSplitsPerMonth, got.FreeLiveSplits.Limit)
	require.True(t, got.FreeLiveSplits.ResetsAt.After(time.Now().UTC()))
}

func TestVerifyPurchaseGrantsPlusAndAcknowledges(t *testing.T) {
	verifier := newFakeVerifier()
	srv, _ := newMonetizedServer(t, purchasesOn(verifier))
	_, token := registerUserToken(t, srv.URL, "Dev", "buy-"+id.New()+"@example.com", "")
	playToken := "play-token-1-" + id.New()
	verifier.set(playToken, activePurchase(30*24*time.Hour))

	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", token,
		`{"productId":"schism_plus","purchaseToken":"`+playToken+`"}`)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	got := decodeEntitlement(t, resp)
	require.True(t, got.Active)
	require.Equal(t, billing.ProductID, got.ProductID)
	require.True(t, got.AutoRenewing)
	acked, before := verifier.acks()
	require.Equal(t, []string{playToken}, acked)

	// A purchase Play already acknowledged is not acknowledged again.
	already := activePurchase(30 * 24 * time.Hour)
	already.Acknowledged = true
	second := "play-token-2-" + id.New()
	verifier.set(second, already)
	_, token2 := registerUserToken(t, srv.URL, "Dev2", "buy2-"+id.New()+"@example.com", "")
	resp2 := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", token2,
		`{"productId":"schism_plus","purchaseToken":"`+second+`"}`)
	require.Equal(t, http.StatusOK, resp2.StatusCode)
	_, after := verifier.acks()
	require.Equal(t, before, after)
}

// TestVerifyPurchaseRejectsUngrantableStates: pending, expired, and refunded purchases are recorded
// but never make the account Plus.
func TestVerifyPurchaseRejectsUngrantableStates(t *testing.T) {
	verifier := newFakeVerifier()
	srv, _ := newMonetizedServer(t, purchasesOn(verifier))

	for _, state := range []billing.State{billing.StatePending, billing.StateExpired, billing.StateRefunded} {
		t.Run(string(state), func(t *testing.T) {
			_, token := registerUserToken(t, srv.URL, "Dev", "st-"+id.New()+"@example.com", "")
			playToken := "play-" + id.New()
			verifier.set(playToken, billing.VerifiedPurchase{
				ProductID: billing.ProductID, State: state,
				ExpiresAt: time.Now().UTC().Add(30 * 24 * time.Hour),
			})
			resp := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", token,
				`{"productId":"schism_plus","purchaseToken":"`+playToken+`"}`)
			require.Equal(t, http.StatusOK, resp.StatusCode)
			require.False(t, decodeEntitlement(t, resp).Active)
		})
	}
}

func TestVerifyPurchaseRejectsForeignProductAndUnknownToken(t *testing.T) {
	verifier := newFakeVerifier()
	srv, _ := newMonetizedServer(t, purchasesOn(verifier))
	_, token := registerUserToken(t, srv.URL, "Dev", "bad-"+id.New()+"@example.com", "")

	for _, body := range []string{
		`{"productId":"schism_gold","purchaseToken":"play-x"}`,
		`{"productId":"","purchaseToken":"play-x"}`,
		`{"productId":"schism_plus","purchaseToken":"never-issued"}`,
	} {
		resp := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", token, body)
		require.Equal(t, http.StatusBadRequest, resp.StatusCode, body)
	}

	// An empty token never reaches the verifier at all.
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", token,
		`{"productId":"schism_plus","purchaseToken":""}`)
	require.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

func TestVerifyPurchaseSurfacesTransientGoogleFailureAsBadGateway(t *testing.T) {
	verifier := newFakeVerifier()
	srv, _ := newMonetizedServer(t, purchasesOn(verifier))
	_, token := registerUserToken(t, srv.URL, "Dev", "flaky-"+id.New()+"@example.com", "")
	verifier.fail("play-flaky", billing.ErrUnavailable)

	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", token,
		`{"productId":"schism_plus","purchaseToken":"play-flaky"}`)
	require.Equal(t, http.StatusBadGateway, resp.StatusCode)
	var body map[string]string
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&body))
	require.Equal(t, "purchase_verification_unavailable", body["error"])
}

// TestVerifyPurchaseReplayToAnotherUserIsRejected: one Play token, one Schism account.
func TestVerifyPurchaseReplayToAnotherUserIsRejected(t *testing.T) {
	verifier := newFakeVerifier()
	srv, _ := newMonetizedServer(t, purchasesOn(verifier))
	shared := "play-shared-" + id.New()
	verifier.set(shared, activePurchase(30*24*time.Hour))
	body := `{"productId":"schism_plus","purchaseToken":"` + shared + `"}`

	_, first := registerUserToken(t, srv.URL, "First", "first-"+id.New()+"@example.com", "")
	require.Equal(t, http.StatusOK,
		authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", first, body).StatusCode)

	_, second := registerUserToken(t, srv.URL, "Second", "second-"+id.New()+"@example.com", "")
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", second, body)
	require.Equal(t, http.StatusConflict, resp.StatusCode)

	ent := authRequest(t, http.MethodGet, srv.URL+"/v1/entitlement", second, "")
	require.False(t, decodeEntitlement(t, ent).Active)
}

func TestBillingDisabledRefusesVerifyAndRestore(t *testing.T) {
	// Plus gating on, purchases off — the paywall exists but nothing can be bought yet.
	srv, _ := newMonetizedServer(t, Monetization{PlusEnabled: true})
	_, token := registerUserToken(t, srv.URL, "Dev", "off-"+id.New()+"@example.com", "")

	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", token,
		`{"productId":"schism_plus","purchaseToken":"play-x"}`)
	require.Equal(t, http.StatusServiceUnavailable, resp.StatusCode)
	resp = authRequest(t, http.MethodPost, srv.URL+"/v1/billing/restore", token, `{}`)
	require.Equal(t, http.StatusServiceUnavailable, resp.StatusCode)

	// Entitlement still answers, so the client can show its free allowance.
	ent := authRequest(t, http.MethodGet, srv.URL+"/v1/entitlement", token, "")
	require.Equal(t, http.StatusOK, ent.StatusCode)
}

// TestRestoreReVerifiesStoredPurchases covers reinstall/restore and the expiry path: the stored
// record is re-checked against Play, so a lapsed subscription drops entitlement.
func TestRestoreReVerifiesStoredPurchases(t *testing.T) {
	verifier := newFakeVerifier()
	srv, _ := newMonetizedServer(t, purchasesOn(verifier))
	_, token := registerUserToken(t, srv.URL, "Dev", "restore-"+id.New()+"@example.com", "")
	restoreToken := "play-restore-" + id.New()
	verifier.set(restoreToken, activePurchase(30*24*time.Hour))

	require.Equal(t, http.StatusOK, authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", token,
		`{"productId":"schism_plus","purchaseToken":"`+restoreToken+`"}`).StatusCode)

	restored := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/restore", token, `{}`)
	require.Equal(t, http.StatusOK, restored.StatusCode)
	require.True(t, decodeEntitlement(t, restored).Active)

	// Play now reports it lapsed; restore must reflect that.
	verifier.set(restoreToken, billing.VerifiedPurchase{
		ProductID: billing.ProductID, State: billing.StateExpired,
		ExpiresAt: time.Now().UTC().Add(-time.Hour),
	})
	lapsed := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/restore", token, `{}`)
	require.False(t, decodeEntitlement(t, lapsed).Active)

	// A transient Google failure on a later restore must NOT revoke anything further.
	verifier.fail(restoreToken, billing.ErrUnavailable)
	transient := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/restore", token, `{}`)
	require.Equal(t, http.StatusOK, transient.StatusCode)
}

// TestPurchaseTokenNeverAppearsInResponsesOrLogs is the privacy guarantee: the token the client sent
// must not come back out, and must not be written to the process log by any billing path.
func TestPurchaseTokenNeverAppearsInResponsesOrLogs(t *testing.T) {
	verifier := newFakeVerifier()
	srv, _ := newMonetizedServer(t, purchasesOn(verifier))
	_, token := registerUserToken(t, srv.URL, "Dev", "secret-"+id.New()+"@example.com", "")
	secret := "play-token-do-not-log-me-" + id.New()
	verifier.set(secret, activePurchase(30*24*time.Hour))
	verifier.failAck(billing.ErrUnavailable) // force the acknowledge retry/log path

	var logs bytes.Buffer
	previousLogOutput := log.Writer()
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(previousLogOutput) })

	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/billing/verify", token,
		`{"productId":"schism_plus","purchaseToken":"`+secret+`"}`)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	body, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	require.NotContains(t, string(body), secret)

	ent := authRequest(t, http.MethodGet, srv.URL+"/v1/entitlement", token, "")
	entBody, err := io.ReadAll(ent.Body)
	require.NoError(t, err)
	require.NotContains(t, string(entBody), secret)
	require.NotContains(t, logs.String(), secret)
	require.Contains(t, logs.String(), "acknowledge_failed", "the failure is still recorded, by record id")

	// Entitlement is still granted even though acknowledgement could not be confirmed.
	require.True(t, decodeEntitlement(t, authRequest(t, http.MethodGet, srv.URL+"/v1/entitlement", token, "")).Active)
}
