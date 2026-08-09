package billing

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	androidPublisherScope = "https://www.googleapis.com/auth/androidpublisher"
	androidPublisherBase  = "https://androidpublisher.googleapis.com"
)

// Google verifies purchases against the Play Developer API using a service-account key.
//
// ponytail: this speaks the two REST calls we need with net/http and a hand-rolled RS256 assertion
// rather than pulling in google.golang.org/api (a ~100-package tree) for two endpoints. Swap in the
// official client if we ever need more of the API surface.
type Google struct {
	PackageName string
	HTTP        *http.Client
	// BaseURL overrides the Play API host; tests point it at an httptest server.
	BaseURL string

	email      string
	privateKey *rsa.PrivateKey
	tokenURL   string

	mu       sync.Mutex
	token    string
	tokenExp time.Time
}

type serviceAccount struct {
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
}

// NewGoogle builds a verifier from a service-account JSON key.
func NewGoogle(packageName, serviceAccountJSON string) (*Google, error) {
	var sa serviceAccount
	if err := json.Unmarshal([]byte(serviceAccountJSON), &sa); err != nil {
		return nil, errors.New("PLAY_SERVICE_ACCOUNT_JSON is not valid JSON")
	}
	if sa.ClientEmail == "" || sa.PrivateKey == "" {
		return nil, errors.New("PLAY_SERVICE_ACCOUNT_JSON needs client_email and private_key")
	}
	key, err := parseRSAKey(sa.PrivateKey)
	if err != nil {
		return nil, err
	}
	tokenURL := sa.TokenURI
	if tokenURL == "" {
		tokenURL = "https://oauth2.googleapis.com/token"
	}
	return &Google{
		PackageName: packageName,
		HTTP:        &http.Client{Timeout: 10 * time.Second},
		email:       sa.ClientEmail,
		privateKey:  key,
		tokenURL:    tokenURL,
	}, nil
}

func parseRSAKey(pemKey string) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(pemKey))
	if block == nil {
		return nil, errors.New("PLAY_SERVICE_ACCOUNT_JSON private_key is not PEM")
	}
	if key, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
		return key, nil
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, errors.New("PLAY_SERVICE_ACCOUNT_JSON private_key is not an RSA key")
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("PLAY_SERVICE_ACCOUNT_JSON private_key is not an RSA key")
	}
	return key, nil
}

func (g *Google) base() string {
	if g.BaseURL != "" {
		return g.BaseURL
	}
	return androidPublisherBase
}

// accessToken returns a cached service-account access token, minting a new one when it is within a
// minute of expiry.
func (g *Google) accessToken(ctx context.Context) (string, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.token != "" && time.Now().Before(g.tokenExp.Add(-time.Minute)) {
		return g.token, nil
	}
	assertion, err := g.assertion(time.Now())
	if err != nil {
		return "", err
	}
	form := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {assertion},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, g.tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := g.HTTP.Do(req)
	if err != nil {
		return "", fmt.Errorf("%w: token endpoint", ErrUnavailable)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("%w: token endpoint status %d", ErrUnavailable, resp.StatusCode)
	}
	var out struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &out); err != nil || out.AccessToken == "" {
		return "", fmt.Errorf("%w: token endpoint response", ErrUnavailable)
	}
	g.token = out.AccessToken
	g.tokenExp = time.Now().Add(time.Duration(out.ExpiresIn) * time.Second)
	return g.token, nil
}

// assertion builds the signed JWT that exchanges for an access token.
func (g *Google) assertion(now time.Time) (string, error) {
	header := base64URL([]byte(`{"alg":"RS256","typ":"JWT"}`))
	claims, err := json.Marshal(map[string]any{
		"iss":   g.email,
		"scope": androidPublisherScope,
		"aud":   g.tokenURL,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})
	if err != nil {
		return "", err
	}
	signing := header + "." + base64URL(claims)
	digest := sha256.Sum256([]byte(signing))
	sig, err := rsa.SignPKCS1v15(rand.Reader, g.privateKey, crypto.SHA256, digest[:])
	if err != nil {
		return "", err
	}
	return signing + "." + base64URL(sig), nil
}

func base64URL(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// subscriptionV2 is the subset of SubscriptionPurchaseV2 that decides entitlement.
type subscriptionV2 struct {
	SubscriptionState    string `json:"subscriptionState"`
	AcknowledgementState string `json:"acknowledgementState"`
	LineItems            []struct {
		ProductID        string `json:"productId"`
		ExpiryTime       string `json:"expiryTime"`
		AutoRenewingPlan *struct {
			AutoRenewEnabled bool `json:"autoRenewEnabled"`
		} `json:"autoRenewingPlan"`
	} `json:"lineItems"`
}

func (g *Google) Verify(ctx context.Context, packageName, productID, purchaseToken string) (VerifiedPurchase, error) {
	if err := Check(packageName, productID); err != nil {
		return VerifiedPurchase{}, err
	}
	if g.PackageName != "" && packageName != g.PackageName {
		return VerifiedPurchase{}, ErrProductMismatch
	}
	token, err := g.accessToken(ctx)
	if err != nil {
		return VerifiedPurchase{}, err
	}
	endpoint := fmt.Sprintf("%s/androidpublisher/v3/applications/%s/purchases/subscriptionsv2/tokens/%s",
		g.base(), url.PathEscape(packageName), url.PathEscape(purchaseToken))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return VerifiedPurchase{}, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := g.HTTP.Do(req)
	if err != nil {
		// The token must not appear in the error: callers log these.
		return VerifiedPurchase{}, fmt.Errorf("%w: subscriptions.get", ErrUnavailable)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	switch {
	case resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone:
		return VerifiedPurchase{}, ErrNotFound
	case resp.StatusCode != http.StatusOK:
		return VerifiedPurchase{}, fmt.Errorf("%w: subscriptions.get status %d", ErrUnavailable, resp.StatusCode)
	}
	var sub subscriptionV2
	if err := json.Unmarshal(body, &sub); err != nil {
		return VerifiedPurchase{}, fmt.Errorf("%w: subscriptions.get response", ErrUnavailable)
	}
	return sub.toVerified(productID)
}

func (s subscriptionV2) toVerified(productID string) (VerifiedPurchase, error) {
	var line = struct {
		expiry       time.Time
		autoRenewing bool
		found        bool
	}{}
	for _, item := range s.LineItems {
		if item.ProductID != productID {
			continue
		}
		line.found = true
		if item.ExpiryTime != "" {
			parsed, err := time.Parse(time.RFC3339, item.ExpiryTime)
			if err != nil {
				return VerifiedPurchase{}, fmt.Errorf("%w: unparsable expiryTime", ErrUnavailable)
			}
			line.expiry = parsed.UTC()
		}
		line.autoRenewing = item.AutoRenewingPlan != nil && item.AutoRenewingPlan.AutoRenewEnabled
		break
	}
	if !line.found {
		return VerifiedPurchase{}, ErrProductMismatch
	}
	return VerifiedPurchase{
		ProductID: productID,
		// A cancelled-but-unexpired subscription still reports ACTIVE/CANCELED with a future expiry,
		// so entitlement follows expiry rather than the renewal flag.
		// ponytail: refunds arrive through the separate voided-purchases feed, not this call, so
		// StateRefunded is only ever set by that feed (not yet wired) — an expired/on-hold refund
		// still drops entitlement here via StateExpired.
		State:        subscriptionState(s.SubscriptionState),
		ExpiresAt:    line.expiry,
		AutoRenewing: line.autoRenewing,
		Acknowledged: s.AcknowledgementState == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
	}, nil
}

func subscriptionState(raw string) State {
	switch raw {
	case "SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_CANCELED", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD":
		return StatePurchased
	case "SUBSCRIPTION_STATE_PENDING", "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED":
		return StatePending
	default:
		// ON_HOLD, PAUSED, EXPIRED and anything unrecognised must not grant Plus.
		return StateExpired
	}
}

func (g *Google) Acknowledge(ctx context.Context, productID, purchaseToken string) error {
	token, err := g.accessToken(ctx)
	if err != nil {
		return err
	}
	endpoint := fmt.Sprintf("%s/androidpublisher/v3/applications/%s/purchases/subscriptions/%s/tokens/%s:acknowledge",
		g.base(), url.PathEscape(g.PackageName), url.PathEscape(productID), url.PathEscape(purchaseToken))
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader([]byte("{}")))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := g.HTTP.Do(req)
	if err != nil {
		return fmt.Errorf("%w: subscriptions.acknowledge", ErrUnavailable)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("%w: subscriptions.acknowledge status %d", ErrUnavailable, resp.StatusCode)
	}
	return nil
}
