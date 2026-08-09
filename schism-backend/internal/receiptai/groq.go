package receiptai

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

const groqBase = "https://api.groq.com"

// Groq extracts receipts with Groq's OpenAI-compatible chat completions API.
//
// Compromise worth knowing: Groq's vision models accept response_format json_object but not
// json_schema (schema-enforced structured output is limited to their text-only models), so unlike
// Gemini the shape here is enforced by the prompt plus decodeDraft's validation rather than by the
// provider. That is exactly why validation refuses anything that does not reconcile.
type Groq struct {
	Model  string
	APIKey string
	HTTP   *http.Client
	// BaseURL overrides the API host; tests point it at an httptest server.
	BaseURL string
}

func NewGroq(apiKey, model string) *Groq {
	if model == "" {
		model = DefaultGroqModel
	}
	return &Groq{Model: model, APIKey: apiKey, HTTP: &http.Client{Timeout: 30 * time.Second}}
}

func (g *Groq) Name() string { return "groq" }

type groqImageURL struct {
	URL string `json:"url"`
}

type groqContent struct {
	Type     string        `json:"type"`
	Text     string        `json:"text,omitempty"`
	ImageURL *groqImageURL `json:"image_url,omitempty"`
}

type groqMessage struct {
	Role    string        `json:"role"`
	Content []groqContent `json:"content"`
}

type groqFormat struct {
	Type string `json:"type"`
}

type groqRequest struct {
	Model           string        `json:"model"`
	Messages        []groqMessage `json:"messages"`
	ResponseFormat  groqFormat    `json:"response_format"`
	Temperature     float64       `json:"temperature"`
	MaxCompletion   int           `json:"max_completion_tokens"`
	ReasoningEffort string        `json:"reasoning_effort"`
}

const (
	// Groq's default is 1024, which the reasoning trace alone can exhaust.
	groqMaxCompletionTokens = 4096

	// This one line is the difference between working and a hard 400.
	//
	// Qwen 3.6 is a reasoning model, and `reasoning_format` defaults to `raw` — which Groq
	// documents as incompatible with JSON mode. Every request without this came back
	// `400 json_validate_failed` with an EMPTY `failed_generation`, which reads like a prompt
	// problem and is not one. Verified against a live key: `reasoning_format: "hidden"` does
	// NOT fix it (still 400); only disabling reasoning does.
	//
	// It is also much cheaper — 312 completion tokens for a 4-item bill instead of a reasoning
	// trace, which matters because Groq's free tier caps at 8,000 tokens per minute and one
	// receipt image is already ~3,500 of them.
	//
	// ponytail: "none" is Qwen-3.6-only (GPT-OSS models take low/medium/high). That is fine
	// while Qwen is the only vision model Groq serves; revisit if GROQ_MODEL points elsewhere.
	groqReasoningEffort = "none"
)

func (g *Groq) Extract(ctx context.Context, image []byte, mimeType string) (Draft, error) {
	if !SupportedMIME(mimeType) {
		return Draft{}, fmt.Errorf("%w: unsupported image type", ErrRejected)
	}
	body := groqRequest{
		Model: g.Model,
		Messages: []groqMessage{{
			Role: "user",
			Content: []groqContent{
				{Type: "text", Text: prompt},
				{Type: "image_url", ImageURL: &groqImageURL{
					URL: "data:" + mimeType + ";base64," + base64.StdEncoding.EncodeToString(image),
				}},
			},
		}},
		ResponseFormat:  groqFormat{Type: "json_object"},
		MaxCompletion:   groqMaxCompletionTokens,
		ReasoningEffort: groqReasoningEffort,
	}
	base := g.BaseURL
	if base == "" {
		base = groqBase
	}
	payload, err := postJSON(ctx, g.HTTP, g.Name(), base+"/openai/v1/chat/completions",
		map[string]string{"Authorization": "Bearer " + g.APIKey}, body)
	if err != nil {
		return Draft{}, err
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(payload, &out); err != nil {
		return Draft{}, fmt.Errorf("%w: groq response envelope", ErrUnavailable)
	}
	if len(out.Choices) == 0 {
		return Draft{}, fmt.Errorf("%w: groq returned no choice", ErrRejected)
	}
	return decodeDraft(out.Choices[0].Message.Content)
}
