package receiptai

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"time"
)

const geminiBase = "https://generativelanguage.googleapis.com"

// geminiSchema is the responseSchema Gemini enforces on its own output. Kept as raw JSON rather
// than nested Go maps: it is a wire document, and the literal reads like the contract it is.
const geminiSchema = `{
  "type":"OBJECT",
  "properties":{
    "merchant":{"type":"STRING"},
    "date":{"type":"STRING"},
    "currency":{"type":"STRING"},
    "items":{"type":"ARRAY","items":{"type":"OBJECT",
      "properties":{"name":{"type":"STRING"},"qty":{"type":"INTEGER"},"amountMinor":{"type":"INTEGER"}},
      "required":["name","qty","amountMinor"]}},
    "subtotalMinor":{"type":"INTEGER"},
    "taxMinor":{"type":"INTEGER"},
    "totalMinor":{"type":"INTEGER"},
    "chargeLines":{"type":"ARRAY","items":{"type":"OBJECT",
      "properties":{"label":{"type":"STRING"},"amountMinor":{"type":"INTEGER"},
                    "kind":{"type":"STRING","enum":["TAX","FEE","DISCOUNT","ROUNDOFF"]}},
      "required":["label","amountMinor","kind"]}}
  },
  "required":["merchant","date","currency","items","subtotalMinor","taxMinor","totalMinor","chargeLines"]
}`

// Gemini extracts receipts with Google's generativeLanguage API.
//
// ponytail: two struct literals and net/http, not google.golang.org/genai — one endpoint does not
// justify that dependency tree. Swap in the SDK if we ever need files, caching or streaming.
type Gemini struct {
	Model  string
	APIKey string
	HTTP   *http.Client
	// BaseURL overrides the API host; tests point it at an httptest server.
	BaseURL string
}

func NewGemini(apiKey, model string) *Gemini {
	if model == "" {
		model = DefaultGeminiModel
	}
	return &Gemini{Model: model, APIKey: apiKey, HTTP: &http.Client{Timeout: 30 * time.Second}}
}

func (g *Gemini) Name() string { return "gemini" }

type geminiInlineData struct {
	MimeType string `json:"mime_type"`
	Data     string `json:"data"`
}

type geminiPart struct {
	Text       string            `json:"text,omitempty"`
	InlineData *geminiInlineData `json:"inline_data,omitempty"`
}

type geminiContent struct {
	Parts []geminiPart `json:"parts"`
}

type geminiGenerationConfig struct {
	ResponseMIMEType string          `json:"responseMimeType"`
	ResponseSchema   json.RawMessage `json:"responseSchema"`
	Temperature      float64         `json:"temperature"`
}

type geminiRequest struct {
	Contents         []geminiContent        `json:"contents"`
	GenerationConfig geminiGenerationConfig `json:"generationConfig"`
}

func (g *Gemini) Extract(ctx context.Context, image []byte, mimeType string) (Draft, error) {
	if !SupportedMIME(mimeType) {
		return Draft{}, fmt.Errorf("%w: unsupported image type", ErrRejected)
	}
	body := geminiRequest{
		Contents: []geminiContent{{Parts: []geminiPart{
			{Text: prompt},
			{InlineData: &geminiInlineData{MimeType: mimeType, Data: base64.StdEncoding.EncodeToString(image)}},
		}}},
		GenerationConfig: geminiGenerationConfig{
			ResponseMIMEType: "application/json",
			ResponseSchema:   json.RawMessage(geminiSchema),
		},
	}
	base := g.BaseURL
	if base == "" {
		base = geminiBase
	}
	endpoint := fmt.Sprintf("%s/v1beta/models/%s:generateContent", base, url.PathEscape(g.Model))
	// The key goes in a header, not the query string, so it cannot end up in an access log.
	payload, err := postJSON(ctx, g.HTTP, g.Name(), endpoint, map[string]string{"x-goog-api-key": g.APIKey}, body)
	if err != nil {
		return Draft{}, err
	}
	var out struct {
		Candidates []struct {
			Content struct {
				Parts []struct {
					Text string `json:"text"`
				} `json:"parts"`
			} `json:"content"`
		} `json:"candidates"`
	}
	if err := json.Unmarshal(payload, &out); err != nil {
		return Draft{}, fmt.Errorf("%w: gemini response envelope", ErrUnavailable)
	}
	if len(out.Candidates) == 0 || len(out.Candidates[0].Content.Parts) == 0 {
		// A blocked or empty candidate means the model would not answer for this image.
		return Draft{}, fmt.Errorf("%w: gemini returned no candidate", ErrRejected)
	}
	return decodeDraft(out.Candidates[0].Content.Parts[0].Text)
}
