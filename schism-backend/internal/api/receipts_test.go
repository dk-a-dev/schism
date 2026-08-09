package api

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"

	"github.com/schism/schism-backend/internal/id"
	"github.com/schism/schism-backend/internal/receiptai"
	"github.com/schism/schism-backend/internal/store"
	"github.com/stretchr/testify/require"
)

// fakeExtractor stands in for Gemini/Groq. No test in this package touches the network.
type fakeExtractor struct {
	mu    sync.Mutex
	draft receiptai.Draft
	err   error
	// images records what was handed over, so a test can prove the bytes arrived intact.
	images [][]byte
	calls  int
}

func (f *fakeExtractor) Name() string { return "fake" }

func (f *fakeExtractor) Extract(_ context.Context, image []byte, _ string) (receiptai.Draft, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	f.images = append(f.images, image)
	return f.draft, f.err
}

func (f *fakeExtractor) callCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

func sampleDraft() receiptai.Draft {
	return receiptai.Draft{
		Merchant: "Gulab Dhaba", Date: "2026-03-05", Currency: "₹",
		Items:         []receiptai.Item{{Name: "Paneer Tikka", Qty: 2, AmountMinor: 38000}},
		SubtotalMinor: 38000, TaxMinor: 1900, TotalMinor: 39900,
		ChargeLines: []receiptai.ChargeLine{{Label: "GST 5%", AmountMinor: 1900, Kind: "TAX"}},
	}
}

// newExtractServer builds a server whose receipt extraction is served by ext (nil = feature off).
func newExtractServer(t *testing.T, ext receiptai.Provider) *httptest.Server {
	t.Helper()
	url := testURL(t)
	require.NoError(t, store.RunMigrations(url))
	pool, err := store.NewPool(context.Background(), url)
	require.NoError(t, err)
	t.Cleanup(pool.Close)
	srv := httptest.NewServer(NewRouterWithFeatures(store.NewStore(pool), false, Monetization{}, ext))
	t.Cleanup(srv.Close)
	return srv
}

// extractBody builds a request body carrying image as base64.
func extractBody(groupID, mimeType string, image []byte) string {
	return fmt.Sprintf(`{"groupId":%q,"mimeType":%q,"imageBase64":%q}`,
		groupID, mimeType, base64.StdEncoding.EncodeToString(image))
}

func TestExtractReceiptReturnsDraft(t *testing.T) {
	ext := &fakeExtractor{draft: sampleDraft()}
	srv := newExtractServer(t, ext)
	g := createGroupFixture(t, srv.URL)

	image := []byte("pretend-jpeg-bytes")
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", g.Token,
		extractBody(g.ID, "image/jpeg", image))
	require.Equal(t, http.StatusOK, resp.StatusCode)

	var got receiptai.Draft
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&got))
	require.Equal(t, sampleDraft(), got)
	require.Equal(t, [][]byte{image}, ext.images, "the provider receives the exact bytes uploaded")
}

// With no provider configured the route is never registered, so the path simply does not exist.
func TestExtractReceiptIsNotFoundWhenFeatureOff(t *testing.T) {
	srv := newExtractServer(t, nil)
	g := createGroupFixture(t, srv.URL)
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", g.Token,
		extractBody(g.ID, "image/jpeg", []byte("img")))
	require.Equal(t, http.StatusNotFound, resp.StatusCode)
}

func TestExtractReceiptRequiresAuthAndMembership(t *testing.T) {
	ext := &fakeExtractor{draft: sampleDraft()}
	srv := newExtractServer(t, ext)
	g := createGroupFixture(t, srv.URL)
	_, outsiderToken := registerUserToken(t, srv.URL, "Nosy", "nosy-"+id.New()+"@example.com", "")

	cases := []struct {
		name    string
		token   string
		groupID string
		want    int
	}{
		{"no token", "", g.ID, http.StatusUnauthorized},
		{"unknown group", g.Token, "does-not-ex", http.StatusNotFound},
		{"not a member", outsiderToken, g.ID, http.StatusForbidden},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			resp := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", tc.token,
				extractBody(tc.groupID, "image/jpeg", []byte("img")))
			require.Equal(t, tc.want, resp.StatusCode)
		})
	}
	require.Zero(t, ext.callCount(), "an unauthorized caller must never reach the provider")
}

func TestExtractReceiptRejectsBadUploads(t *testing.T) {
	ext := &fakeExtractor{draft: sampleDraft()}
	srv := newExtractServer(t, ext)
	g := createGroupFixture(t, srv.URL)

	oversize := bytes.Repeat([]byte{0xAB}, maxReceiptImageBytes+1)
	huge := bytes.Repeat([]byte{0xCD}, maxReceiptBodyBytes) // exceeds the body ceiling once encoded

	cases := []struct {
		name string
		body string
		want int
	}{
		{"image over the 8 MB cap", extractBody(g.ID, "image/jpeg", oversize), http.StatusRequestEntityTooLarge},
		{"body over the request ceiling", extractBody(g.ID, "image/jpeg", huge), http.StatusRequestEntityTooLarge},
		{"unsupported type", extractBody(g.ID, "image/gif", []byte("img")), http.StatusUnsupportedMediaType},
		{"pdf", extractBody(g.ID, "application/pdf", []byte("img")), http.StatusUnsupportedMediaType},
		{"missing mime type", extractBody(g.ID, "", []byte("img")), http.StatusUnsupportedMediaType},
		{"not base64", `{"groupId":"` + g.ID + `","mimeType":"image/jpeg","imageBase64":"!!!not base64!!!"}`, http.StatusBadRequest},
		{"empty image", extractBody(g.ID, "image/jpeg", nil), http.StatusBadRequest},
		{"unknown field", `{"groupId":"` + g.ID + `","mimeType":"image/jpeg","imageBase64":"aW1n","extra":1}`, http.StatusBadRequest},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			resp := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", g.Token, tc.body)
			require.Equal(t, tc.want, resp.StatusCode)
		})
	}
	require.Zero(t, ext.callCount(), "a refused upload must never cost a provider call")
}

// Oversize is rejected before the limiter is touched, so a bad upload cannot burn the hourly slot.
func TestExtractReceiptOversizeDoesNotSpendTheHourlySlot(t *testing.T) {
	ext := &fakeExtractor{draft: sampleDraft()}
	srv := newExtractServer(t, ext)
	g := createGroupFixture(t, srv.URL)

	oversize := bytes.Repeat([]byte{0xAB}, maxReceiptImageBytes+1)
	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", g.Token,
		extractBody(g.ID, "image/jpeg", oversize))
	require.Equal(t, http.StatusRequestEntityTooLarge, resp.StatusCode)

	resp2 := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", g.Token,
		extractBody(g.ID, "image/jpeg", []byte("img")))
	require.Equal(t, http.StatusOK, resp2.StatusCode)
}

// The second call within the hour is refused with 429, Retry-After, and a machine-readable body.
func TestExtractReceiptRateLimitsToOnePerGroupPerHour(t *testing.T) {
	ext := &fakeExtractor{draft: sampleDraft()}
	srv := newExtractServer(t, ext)
	g := createGroupFixture(t, srv.URL)
	body := extractBody(g.ID, "image/jpeg", []byte("img"))

	first := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", g.Token, body)
	require.Equal(t, http.StatusOK, first.StatusCode)

	second := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", g.Token, body)
	require.Equal(t, http.StatusTooManyRequests, second.StatusCode)

	retryAfter, err := strconv.Atoi(second.Header.Get("Retry-After"))
	require.NoError(t, err, "429 must carry a Retry-After header")
	require.Positive(t, retryAfter)
	require.LessOrEqual(t, retryAfter, int(store.ReceiptExtractWindow.Seconds()))

	var refused rateLimitedDTO
	require.NoError(t, json.NewDecoder(second.Body).Decode(&refused))
	require.Equal(t, "receipt_extract_rate_limited", refused.Error)
	require.Equal(t, retryAfter, refused.RetryAfterSeconds)
	require.False(t, refused.NextAllowedAt.IsZero())

	require.Equal(t, 1, ext.callCount(), "the refused call must not reach the provider")
}

func TestExtractReceiptMapsProviderFailures(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want int
	}{
		{"unreadable receipt", fmt.Errorf("%w: junk", receiptai.ErrRejected), http.StatusUnprocessableEntity},
		{"provider down", fmt.Errorf("%w: 503", receiptai.ErrUnavailable), http.StatusBadGateway},
		{"unclassified failure", fmt.Errorf("something else"), http.StatusBadGateway},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := newExtractServer(t, &fakeExtractor{err: tc.err})
			g := createGroupFixture(t, srv.URL)
			resp := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", g.Token,
				extractBody(g.ID, "image/jpeg", []byte("img")))
			require.Equal(t, tc.want, resp.StatusCode)

			var errBody map[string]string
			require.NoError(t, json.NewDecoder(resp.Body).Decode(&errBody))
			require.NotEmpty(t, errBody["error"])
		})
	}
}

// The hard rule: no image bytes in any log line, ever — on the happy path or any failure path, and
// even with per-request access logging switched on.
//
// This captures the standard logger, which is where every line this package writes goes. chi's
// access log is a separate stream it bound to os.Stdout at init and cannot be intercepted here; it
// logs only method, path, status and duration, and never touches the body.
func TestExtractReceiptNeverLogsTheImage(t *testing.T) {
	url := testURL(t)
	require.NoError(t, store.RunMigrations(url))
	pool, err := store.NewPool(context.Background(), url)
	require.NoError(t, err)
	t.Cleanup(pool.Close)

	cases := []struct {
		name string
		ext  *fakeExtractor
	}{
		{"success", &fakeExtractor{draft: sampleDraft()}},
		{"rejected", &fakeExtractor{err: fmt.Errorf("%w: junk", receiptai.ErrRejected)}},
		{"unavailable", &fakeExtractor{err: fmt.Errorf("%w: 503", receiptai.ErrUnavailable)}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			// logRequests=true is the noisiest posture the server can be run in.
			srv := httptest.NewServer(NewRouterWithFeatures(store.NewStore(pool), true, Monetization{}, tc.ext))
			t.Cleanup(srv.Close)
			g := createGroupFixture(t, srv.URL)

			// A marker that is recognisable both raw and base64-encoded.
			image := []byte("SECRETRECEIPTPIXELS-" + id.New())
			encoded := base64.StdEncoding.EncodeToString(image)

			var logs bytes.Buffer
			log.SetOutput(&logs)
			t.Cleanup(func() { log.SetOutput(os.Stderr) })

			resp := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", g.Token,
				extractBody(g.ID, "image/jpeg", image))
			require.NotEqual(t, http.StatusNotFound, resp.StatusCode)

			written := logs.String()
			require.NotContains(t, written, encoded, "base64 image bytes reached the log")
			require.NotContains(t, written, string(image), "raw image bytes reached the log")
			require.NotContains(t, written, "imageBase64", "the request body reached the log")
		})
	}
}

// The failure log line carries the provider and a fixed reason, and nothing else.
func TestExtractReceiptFailureLogIsMinimal(t *testing.T) {
	srv := newExtractServer(t, &fakeExtractor{err: fmt.Errorf("%w: groq status 500 key=sk-leak", receiptai.ErrUnavailable)})
	g := createGroupFixture(t, srv.URL)

	var logs bytes.Buffer
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(os.Stderr) })

	resp := authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract", g.Token,
		extractBody(g.ID, "image/jpeg", []byte("img")))
	require.Equal(t, http.StatusBadGateway, resp.StatusCode)

	written := logs.String()
	require.Contains(t, written, "receipt_extract_failed")
	require.Contains(t, written, `reason="unavailable"`)
	require.NotContains(t, written, "sk-leak", "the provider's error text must not be logged verbatim")
}

// The limit is per group: a second group is extractable in the same hour.
func TestExtractReceiptLimitIsPerGroup(t *testing.T) {
	ext := &fakeExtractor{draft: sampleDraft()}
	srv := newExtractServer(t, ext)
	g := createGroupFixture(t, srv.URL)
	other := createGroupFixture(t, srv.URL)

	require.Equal(t, http.StatusOK, authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract",
		g.Token, extractBody(g.ID, "image/jpeg", []byte("img"))).StatusCode)
	require.Equal(t, http.StatusOK, authRequest(t, http.MethodPost, srv.URL+"/v1/receipts/extract",
		other.Token, extractBody(other.ID, "image/jpeg", []byte("img"))).StatusCode)
}

func TestExtractReceiptRequiresJSONContentType(t *testing.T) {
	srv := newExtractServer(t, &fakeExtractor{draft: sampleDraft()})
	g := createGroupFixture(t, srv.URL)

	req, err := http.NewRequest(http.MethodPost, srv.URL+"/v1/receipts/extract",
		strings.NewReader(extractBody(g.ID, "image/jpeg", []byte("img"))))
	require.NoError(t, err)
	req.Header.Set("Content-Type", "multipart/form-data")
	req.Header.Set("Authorization", "Bearer "+g.Token)
	resp, err := http.DefaultClient.Do(req)
	require.NoError(t, err)
	require.Equal(t, http.StatusUnsupportedMediaType, resp.StatusCode)
}
