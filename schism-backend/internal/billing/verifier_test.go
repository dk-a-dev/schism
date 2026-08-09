package billing

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestCheckAcceptsOnlySchismPlus(t *testing.T) {
	require.NoError(t, Check("ai.schism.split", "schism_plus"))
	require.ErrorIs(t, Check("com.attacker.app", "schism_plus"), ErrProductMismatch)
	require.ErrorIs(t, Check("ai.schism.split", "schism_gold"), ErrProductMismatch)
	require.ErrorIs(t, Check("", ""), ErrProductMismatch)
}

func TestSubscriptionStateMapping(t *testing.T) {
	for raw, want := range map[string]State{
		"SUBSCRIPTION_STATE_ACTIVE":          StatePurchased,
		"SUBSCRIPTION_STATE_CANCELED":        StatePurchased,
		"SUBSCRIPTION_STATE_IN_GRACE_PERIOD": StatePurchased,
		"SUBSCRIPTION_STATE_PENDING":         StatePending,
		"SUBSCRIPTION_STATE_ON_HOLD":         StateExpired,
		"SUBSCRIPTION_STATE_PAUSED":          StateExpired,
		"SUBSCRIPTION_STATE_EXPIRED":         StateExpired,
		"SOMETHING_GOOGLE_ADDED_LATER":       StateExpired,
		"":                                   StateExpired,
	} {
		require.Equal(t, want, subscriptionState(raw), "state %q", raw)
	}
}

// newTestGoogle builds a Google verifier signed by a throwaway key and pointed at fake token and
// Play endpoints, so no test ever needs real credentials or network.
func newTestGoogle(t *testing.T, playHandler http.HandlerFunc) *Google {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	der, err := x509.MarshalPKCS8PrivateKey(key)
	require.NoError(t, err)
	pemKey := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))

	tokenSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"fake-access","expires_in":3600}`))
	}))
	t.Cleanup(tokenSrv.Close)
	playSrv := httptest.NewServer(playHandler)
	t.Cleanup(playSrv.Close)

	sa, err := json.Marshal(map[string]string{
		"client_email": "svc@schism.iam.gserviceaccount.com",
		"private_key":  pemKey,
		"token_uri":    tokenSrv.URL,
	})
	require.NoError(t, err)
	g, err := NewGoogle("ai.schism.split", string(sa))
	require.NoError(t, err)
	g.BaseURL = playSrv.URL
	return g
}

func TestNewGoogleRejectsBadServiceAccounts(t *testing.T) {
	for _, bad := range []string{"", "{", `{"client_email":"a@b"}`, `{"client_email":"a@b","private_key":"nope"}`} {
		_, err := NewGoogle("ai.schism.split", bad)
		require.Error(t, err, "input %q", bad)
	}
}

func TestGoogleVerifyMapsPlayResponses(t *testing.T) {
	expiry := time.Now().UTC().Add(30 * 24 * time.Hour).Truncate(time.Second)
	body := `{
	  "subscriptionState":"SUBSCRIPTION_STATE_CANCELED",
	  "acknowledgementState":"ACKNOWLEDGEMENT_STATE_PENDING",
	  "lineItems":[{"productId":"schism_plus","expiryTime":"` + expiry.Format(time.RFC3339) + `",
	                "autoRenewingPlan":{"autoRenewEnabled":false}}]
	}`
	var gotAuth, gotPath string
	g := newTestGoogle(t, func(w http.ResponseWriter, r *http.Request) {
		gotAuth, gotPath = r.Header.Get("Authorization"), r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(body))
	})

	got, err := g.Verify(context.Background(), PackageName, ProductID, "tok-123")
	require.NoError(t, err)
	require.Equal(t, StatePurchased, got.State, "cancelled stays purchased until expiry")
	require.Equal(t, expiry, got.ExpiresAt)
	require.False(t, got.AutoRenewing)
	require.False(t, got.Acknowledged)
	require.Equal(t, "Bearer fake-access", gotAuth)
	require.Contains(t, gotPath, "/applications/ai.schism.split/purchases/subscriptionsv2/tokens/tok-123")
}

func TestGoogleVerifyRejectsForeignPackageBeforeCallingPlay(t *testing.T) {
	called := false
	g := newTestGoogle(t, func(w http.ResponseWriter, r *http.Request) { called = true })

	_, err := g.Verify(context.Background(), "com.attacker.app", ProductID, "tok")
	require.ErrorIs(t, err, ErrProductMismatch)
	_, err = g.Verify(context.Background(), PackageName, "schism_gold", "tok")
	require.ErrorIs(t, err, ErrProductMismatch)
	require.False(t, called, "a mismatched package/product must never reach Play")
}

func TestGoogleVerifyMissingLineItemIsProductMismatch(t *testing.T) {
	g := newTestGoogle(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"subscriptionState":"SUBSCRIPTION_STATE_ACTIVE","lineItems":[{"productId":"other"}]}`))
	})
	_, err := g.Verify(context.Background(), PackageName, ProductID, "tok")
	require.ErrorIs(t, err, ErrProductMismatch)
}

func TestGoogleVerifyNotFoundAndTransientFailures(t *testing.T) {
	notFound := newTestGoogle(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	})
	_, err := notFound.Verify(context.Background(), PackageName, ProductID, "tok")
	require.ErrorIs(t, err, ErrNotFound)

	flaky := newTestGoogle(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	})
	_, err = flaky.Verify(context.Background(), PackageName, ProductID, "secret-token")
	require.ErrorIs(t, err, ErrUnavailable)
	require.NotContains(t, err.Error(), "secret-token", "errors must never carry the purchase token")

	garbage := newTestGoogle(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`not json`))
	})
	_, err = garbage.Verify(context.Background(), PackageName, ProductID, "secret-token")
	require.ErrorIs(t, err, ErrUnavailable)
	require.NotContains(t, err.Error(), "secret-token")
}

func TestGoogleAcknowledgePostsToPlay(t *testing.T) {
	var gotPath, gotMethod string
	g := newTestGoogle(t, func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotMethod = r.URL.Path, r.Method
		w.WriteHeader(http.StatusOK)
	})
	require.NoError(t, g.Acknowledge(context.Background(), ProductID, "tok-9"))
	require.Equal(t, http.MethodPost, gotMethod)
	require.True(t, strings.HasSuffix(gotPath, "/purchases/subscriptions/schism_plus/tokens/tok-9:acknowledge"), gotPath)

	failing := newTestGoogle(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	})
	err := failing.Acknowledge(context.Background(), ProductID, "secret-token")
	require.ErrorIs(t, err, ErrUnavailable)
	require.NotContains(t, err.Error(), "secret-token")
}

func TestGoogleAccessTokenIsCachedAcrossCalls(t *testing.T) {
	g := newTestGoogle(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"subscriptionState":"SUBSCRIPTION_STATE_ACTIVE","lineItems":[{"productId":"schism_plus"}]}`))
	})
	first, err := g.accessToken(context.Background())
	require.NoError(t, err)
	// Break the token endpoint; a cached token means the second call still succeeds.
	g.tokenURL = "http://127.0.0.1:1/token"
	second, err := g.accessToken(context.Background())
	require.NoError(t, err)
	require.Equal(t, first, second)
}
