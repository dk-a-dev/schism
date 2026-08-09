package receiptai

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

// A well-formed answer: two items, CGST + a discount, all reconciling.
const goodDraftJSON = `{
  "merchant":"Gulab Dhaba","date":"2026-03-05","currency":"₹",
  "items":[{"name":"Paneer Tikka","qty":2,"amountMinor":38000},
           {"name":"Butter Naan","qty":3,"amountMinor":12000}],
  "subtotalMinor":50000,"taxMinor":1500,"totalMinor":51500,
  "chargeLines":[{"label":"CGST 2.5%","amountMinor":1250,"kind":"TAX"},
                 {"label":"Service Charge","amountMinor":2250,"kind":"FEE"},
                 {"label":"Offer","amountMinor":-2000,"kind":"DISCOUNT"}]
}`

// providerCase adapts one implementation so the shared tables can drive both. wrap puts the model's
// answer inside that vendor's response envelope.
type providerCase struct {
	name string
	make func(base, key string) Provider
	wrap func(modelOutput string) string
}

func providerCases() []providerCase {
	return []providerCase{
		{
			name: "gemini",
			make: func(base, key string) Provider {
				g := NewGemini(key, "")
				g.BaseURL = base
				return g
			},
			wrap: func(out string) string {
				payload, _ := json.Marshal(map[string]any{
					"candidates": []any{map[string]any{
						"content": map[string]any{"parts": []any{map[string]any{"text": out}}},
					}},
				})
				return string(payload)
			},
		},
		{
			name: "groq",
			make: func(base, key string) Provider {
				g := NewGroq(key, "")
				g.BaseURL = base
				return g
			},
			wrap: func(out string) string {
				payload, _ := json.Marshal(map[string]any{
					"choices": []any{map[string]any{"message": map[string]any{"content": out}}},
				})
				return string(payload)
			},
		},
	}
}

// fakeProvider serves one canned body with one status, and records the last request it saw.
func fakeProvider(t *testing.T, status int, body string) (*httptest.Server, *[]byte) {
	t.Helper()
	var seen []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen, _ = io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}))
	t.Cleanup(srv.Close)
	return srv, &seen
}

func TestExtractDecodesProviderJSON(t *testing.T) {
	for _, pc := range providerCases() {
		t.Run(pc.name, func(t *testing.T) {
			srv, _ := fakeProvider(t, http.StatusOK, pc.wrap(goodDraftJSON))
			got, err := pc.make(srv.URL, "k").Extract(context.Background(), []byte("jpeg-bytes"), "image/jpeg")
			require.NoError(t, err)
			require.Equal(t, "Gulab Dhaba", got.Merchant)
			require.Equal(t, "2026-03-05", got.Date)
			require.Equal(t, "₹", got.Currency)
			require.Len(t, got.Items, 2)
			require.Equal(t, Item{Name: "Paneer Tikka", Qty: 2, AmountMinor: 38000}, got.Items[0])
			require.Equal(t, int64(50000), got.SubtotalMinor)
			require.Equal(t, int64(1500), got.TaxMinor)
			require.Equal(t, int64(51500), got.TotalMinor)
			require.Len(t, got.ChargeLines, 3)
			require.Equal(t, "DISCOUNT", got.ChargeLines[2].Kind)
			require.Equal(t, int64(-2000), got.ChargeLines[2].AmountMinor)
		})
	}
}

// The request must actually carry the image, the model id, and a structured-output instruction.
func TestExtractSendsImageAndStructuredOutputRequest(t *testing.T) {
	for _, pc := range providerCases() {
		t.Run(pc.name, func(t *testing.T) {
			srv, seen := fakeProvider(t, http.StatusOK, pc.wrap(goodDraftJSON))
			p := pc.make(srv.URL, "k")
			_, err := p.Extract(context.Background(), []byte("jpeg-bytes"), "image/png")
			require.NoError(t, err)
			body := string(*seen)
			// "jpeg-bytes" base64-encoded; proves the image reached the wire intact.
			require.Contains(t, body, "anBlZy1ieXRlcw==")
			require.Contains(t, body, "image/png")
			require.Contains(t, body, "json")
			if pc.name == "gemini" {
				require.Contains(t, body, "responseSchema")
			} else {
				require.Contains(t, body, "json_object")
				require.Contains(t, body, DefaultGroqModel)
			}
		})
	}
}

func TestExtractRejectsUnusableResponses(t *testing.T) {
	// Every case must produce ErrRejected: a wrong draft is worse than a refused one.
	cases := []struct {
		name   string
		output string
	}{
		{"prose instead of json", "Sure! Here is the receipt you asked about."},
		{"truncated json", `{"merchant":"X","items":[`},
		{"floats not minor units", `{"merchant":"X","currency":"$","items":[{"name":"A","qty":1,"amountMinor":12.34}],"subtotalMinor":1234,"taxMinor":0,"totalMinor":1234,"chargeLines":[]}`},
		{"negative total", `{"merchant":"X","currency":"$","items":[{"name":"A","qty":1,"amountMinor":100}],"subtotalMinor":100,"taxMinor":0,"totalMinor":-100,"chargeLines":[]}`},
		{"zero total", `{"merchant":"X","currency":"$","items":[{"name":"A","qty":1,"amountMinor":100}],"subtotalMinor":100,"taxMinor":0,"totalMinor":0,"chargeLines":[]}`},
		{"no items", `{"merchant":"X","currency":"$","items":[],"subtotalMinor":0,"taxMinor":0,"totalMinor":5000,"chargeLines":[]}`},
		{"nameless item", `{"merchant":"X","currency":"$","items":[{"name":"  ","qty":1,"amountMinor":5000}],"subtotalMinor":5000,"taxMinor":0,"totalMinor":5000,"chargeLines":[]}`},
		{"negative item", `{"merchant":"X","currency":"$","items":[{"name":"A","qty":1,"amountMinor":-5000}],"subtotalMinor":-5000,"taxMinor":0,"totalMinor":5000,"chargeLines":[]}`},
		{"items do not sum to subtotal", `{"merchant":"X","currency":"$","items":[{"name":"A","qty":1,"amountMinor":100}],"subtotalMinor":90000,"taxMinor":0,"totalMinor":90000,"chargeLines":[]}`},
		{"total does not reconcile", `{"merchant":"X","currency":"$","items":[{"name":"A","qty":1,"amountMinor":10000}],"subtotalMinor":10000,"taxMinor":0,"totalMinor":99000,"chargeLines":[]}`},
		{"charges contradict tax total", `{"merchant":"X","currency":"$","items":[{"name":"A","qty":1,"amountMinor":10000}],"subtotalMinor":10000,"taxMinor":500,"totalMinor":10500,"chargeLines":[{"label":"GST","amountMinor":900,"kind":"TAX"}]}`},
		{"unknown charge kind", `{"merchant":"X","currency":"$","items":[{"name":"A","qty":1,"amountMinor":10000}],"subtotalMinor":10000,"taxMinor":500,"totalMinor":10500,"chargeLines":[{"label":"???","amountMinor":500,"kind":"SURPRISE"}]}`},
		{"hostile: draft nested in a string", `"{\"totalMinor\":100000}"`},
		{"hostile: array not object", `[{"totalMinor":100000}]`},
		{"empty answer", ``},
	}
	for _, pc := range providerCases() {
		for _, tc := range cases {
			t.Run(pc.name+"/"+tc.name, func(t *testing.T) {
				srv, _ := fakeProvider(t, http.StatusOK, pc.wrap(tc.output))
				_, err := pc.make(srv.URL, "k").Extract(context.Background(), []byte("img"), "image/jpeg")
				require.ErrorIs(t, err, ErrRejected)
			})
		}
	}
}

func TestExtractAcceptsFencedJSONAndNormalises(t *testing.T) {
	// A markdown fence, a mangled date, a zero qty, and an omitted subtotal are all recoverable.
	output := "```json\n" + `{"merchant":" Cafe ","date":"05/03/2026","currency":"$",
	  "items":[{"name":" Latte ","qty":0,"amountMinor":30000}],
	  "subtotalMinor":0,"taxMinor":0,"totalMinor":31500,
	  "chargeLines":[{"label":"VAT","amountMinor":1500,"kind":"tax"}]}` + "\n```"
	srv, _ := fakeProvider(t, http.StatusOK, providerCases()[0].wrap(output))
	got, err := providerCases()[0].make(srv.URL, "k").Extract(context.Background(), []byte("img"), "image/jpeg")
	require.NoError(t, err)
	require.Equal(t, "Cafe", got.Merchant)
	require.Equal(t, "", got.Date, "an unparsable date is dropped, not fatal")
	require.Equal(t, 1, got.Items[0].Qty, "qty 0 normalises to 1")
	require.Equal(t, "Latte", got.Items[0].Name)
	require.Equal(t, int64(30000), got.SubtotalMinor, "an omitted subtotal is derived from the items")
	require.Equal(t, int64(1500), got.TaxMinor, "an omitted tax total is derived from the charge lines")
	require.Equal(t, "TAX", got.ChargeLines[0].Kind)
}

func TestExtractMapsProviderStatuses(t *testing.T) {
	cases := []struct {
		status int
		want   error
	}{
		{http.StatusBadRequest, ErrRejected},
		{http.StatusRequestEntityTooLarge, ErrRejected},
		{http.StatusUnsupportedMediaType, ErrRejected},
		{http.StatusUnprocessableEntity, ErrRejected},
		{http.StatusUnauthorized, ErrUnavailable},
		{http.StatusForbidden, ErrUnavailable},
		{http.StatusNotFound, ErrUnavailable},
		{http.StatusTooManyRequests, ErrUnavailable},
		{http.StatusInternalServerError, ErrUnavailable},
		{http.StatusBadGateway, ErrUnavailable},
		{http.StatusServiceUnavailable, ErrUnavailable},
	}
	for _, pc := range providerCases() {
		for _, tc := range cases {
			t.Run(pc.name+"/"+http.StatusText(tc.status), func(t *testing.T) {
				srv, _ := fakeProvider(t, tc.status, `{"error":{"message":"nope"}}`)
				_, err := pc.make(srv.URL, "k").Extract(context.Background(), []byte("img"), "image/jpeg")
				require.ErrorIs(t, err, tc.want)
			})
		}
	}
}

// No error may ever quote the credential — these strings get logged.
func TestExtractNeverLeaksAPIKey(t *testing.T) {
	const key = "sk-super-secret-key-value"
	for _, pc := range providerCases() {
		for _, status := range []int{http.StatusUnauthorized, http.StatusInternalServerError, http.StatusBadRequest} {
			t.Run(pc.name+"/"+http.StatusText(status), func(t *testing.T) {
				srv, _ := fakeProvider(t, status, `{"error":"bad key `+key+`"}`)
				_, err := pc.make(srv.URL, key).Extract(context.Background(), []byte("img"), "image/jpeg")
				require.Error(t, err)
				require.NotContains(t, err.Error(), key)
			})
		}
		t.Run(pc.name+"/transport failure", func(t *testing.T) {
			srv, _ := fakeProvider(t, http.StatusOK, "")
			p := pc.make(srv.URL, key)
			srv.Close() // force a connection error
			_, err := p.Extract(context.Background(), []byte("img"), "image/jpeg")
			require.ErrorIs(t, err, ErrUnavailable)
			require.NotContains(t, err.Error(), key)
		})
	}
}

func TestExtractRejectsUnsupportedMIMEWithoutCallingProvider(t *testing.T) {
	for _, pc := range providerCases() {
		t.Run(pc.name, func(t *testing.T) {
			called := false
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				called = true
			}))
			t.Cleanup(srv.Close)
			_, err := pc.make(srv.URL, "k").Extract(context.Background(), []byte("img"), "image/gif")
			require.ErrorIs(t, err, ErrRejected)
			require.False(t, called, "an unsupported type must not cost a provider call")
		})
	}
}

func TestExtractHonoursContextCancellation(t *testing.T) {
	for _, pc := range providerCases() {
		t.Run(pc.name, func(t *testing.T) {
			srv, _ := fakeProvider(t, http.StatusOK, pc.wrap(goodDraftJSON))
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			_, err := pc.make(srv.URL, "k").Extract(ctx, []byte("img"), "image/jpeg")
			require.ErrorIs(t, err, ErrUnavailable)
		})
	}
}

func TestSelectPrefersGeminiAndDefaultsModels(t *testing.T) {
	cases := []struct {
		name                    string
		geminiKey, geminiModel  string
		groqKey, groqModel      string
		wantProvider, wantModel string
	}{
		{name: "nothing configured"},
		{name: "gemini only", geminiKey: "g", wantProvider: "gemini", wantModel: DefaultGeminiModel},
		{name: "groq only", groqKey: "q", wantProvider: "groq", wantModel: DefaultGroqModel},
		{name: "both: chain, gemini first", geminiKey: "g", groqKey: "q", wantProvider: "gemini+groq"},
		{name: "model override", groqKey: "q", groqModel: "meta-llama/llama-4-maverick-17b-128e-instruct",
			wantProvider: "groq", wantModel: "meta-llama/llama-4-maverick-17b-128e-instruct"},
		{name: "blank key is unconfigured", geminiKey: "  ", groqKey: "q", wantProvider: "groq", wantModel: DefaultGroqModel},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			p := Select(tc.geminiKey, tc.geminiModel, tc.groqKey, tc.groqModel)
			if tc.wantProvider == "" {
				require.Nil(t, p)
				return
			}
			require.NotNil(t, p)
			require.Equal(t, tc.wantProvider, p.Name())
			switch v := p.(type) {
			case *Gemini:
				require.Equal(t, tc.wantModel, v.Model)
			case *Groq:
				require.Equal(t, tc.wantModel, v.Model)
			case Chain:
				// Order is the contract: Gemini is tried first, Groq only covers for it.
				require.IsType(t, &Gemini{}, v[0])
				require.IsType(t, &Groq{}, v[1])
			default:
				t.Fatalf("unexpected provider type %T", p)
			}
		})
	}
}

func TestSupportedMIME(t *testing.T) {
	for _, tc := range []struct {
		in   string
		want bool
	}{
		{"image/jpeg", true},
		{"IMAGE/PNG", true},
		{" image/webp ", true},
		{"image/gif", false},
		{"image/heic", false},
		{"application/pdf", false},
		{"", false},
	} {
		require.Equal(t, tc.want, SupportedMIME(tc.in), tc.in)
	}
}

// The prompt must keep saying "JSON": Groq's json_object mode requires the word in the prompt.
func TestPromptMentionsJSONForGroqObjectMode(t *testing.T) {
	require.True(t, strings.Contains(prompt, "JSON"))
}

// stubProvider records that it was called and returns whatever it was told to.
type stubProvider struct {
	name   string
	draft  Draft
	err    error
	called *int
}

func (s stubProvider) Name() string { return s.name }
func (s stubProvider) Extract(context.Context, []byte, string) (Draft, error) {
	*s.called++
	return s.draft, s.err
}

func TestChainFallsBackOnlyWhenRetryingCanHelp(t *testing.T) {
	good := Draft{Merchant: "Cafe", TotalMinor: 1000, SubtotalMinor: 1000,
		Items: []Item{{Name: "Tea", Qty: 1, AmountMinor: 1000}}}

	cases := []struct {
		name             string
		firstErr         error
		wantSecondCalled int
		wantErrIs        error
	}{
		{
			// The whole point of the chain: Gemini rate-limited or broken, Groq covers.
			name:     "unavailable falls through",
			firstErr: ErrUnavailable, wantSecondCalled: 1,
		},
		{
			// The image is not readable. Paying a second provider to fail on the same photo
			// costs money and latency to reach the same answer.
			name:     "rejected is terminal",
			firstErr: ErrRejected, wantSecondCalled: 0, wantErrIs: ErrRejected,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			first, second := 0, 0
			c := Chain{
				stubProvider{name: "first", err: tc.firstErr, called: &first},
				stubProvider{name: "second", draft: good, called: &second},
			}
			got, err := c.Extract(context.Background(), []byte("x"), "image/jpeg")
			require.Equal(t, 1, first, "first provider must always be tried")
			require.Equal(t, tc.wantSecondCalled, second)
			if tc.wantErrIs != nil {
				require.ErrorIs(t, err, tc.wantErrIs)
				return
			}
			require.NoError(t, err)
			require.Equal(t, "Cafe", got.Merchant)
		})
	}
}

func TestChainStopsWhenContextIsDone(t *testing.T) {
	first, second := 0, 0
	c := Chain{
		stubProvider{name: "first", err: ErrUnavailable, called: &first},
		stubProvider{name: "second", called: &second},
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := c.Extract(ctx, []byte("x"), "image/jpeg")
	require.Error(t, err)
	require.Equal(t, 0, second, "a dead deadline will not be healthier at the next provider")
}

func TestChainNameListsProvidersInOrder(t *testing.T) {
	require.Equal(t, "gemini+groq", Chain{NewGemini("k", ""), NewGroq("k", "")}.Name())
}
