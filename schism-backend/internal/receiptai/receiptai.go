// Package receiptai turns a photographed receipt into a structured Draft using a cloud vision model.
//
// The image is a pass-through: it is handed to the provider and dropped. Nothing in this package
// writes it to disk, to the database, or to a log line, and no error it returns quotes the image or
// the provider credential — errors carry only the provider name and an HTTP status.
//
// The Provider abstraction is deliberately caller-agnostic: it knows about an image, a prompt and a
// Draft, and nothing about HTTP handlers, users or groups. The Android app talks to the same two
// vendors directly when a user brings their own key, so nothing here may assume the Schism backend
// is the only thing doing extraction.
package receiptai

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Default model ids, both overridable via GEMINI_MODEL / GROQ_MODEL because vision line-ups change
// faster than releases do.
//
// These two are the ones actually verified against a live key on a receipt image. Do not "upgrade"
// them from a docs page or a ListModels response without calling them first — availability is
// per-account and both obvious-looking choices are dead:
//
//   - gemini-2.5-flash      404 "no longer available to new users" (ListModels still returns it)
//   - llama-4-scout / maverick   404 "does not exist" on a current Groq account
//
// Bigger is also not automatically better here: gemini-3.5-flash returned malformed JSON on the
// same bill that the lite models parsed exactly, so the lite tier is the default on merit, not
// only on cost.
const (
	DefaultGeminiModel = "gemini-3.5-flash-lite"
	DefaultGroqModel   = "qwen/qwen3.6-27b"
)

// Errors callers map to HTTP statuses. ErrRejected means "this image is not going to work" (retrying
// is pointless); ErrUnavailable means "try again later".
var (
	ErrUnavailable = errors.New("receipt extraction unavailable")
	ErrRejected    = errors.New("receipt could not be read")
)

// Provider is one cloud vision backend. Implementations must return a validated Draft or an error;
// they never return a half-trusted draft.
type Provider interface {
	Name() string
	Extract(ctx context.Context, image []byte, mimeType string) (Draft, error)
}

// Draft mirrors the Android ReceiptDraft contract. Every amount is an integer in minor currency
// units (paise/cents) — never a float, at any layer.
type Draft struct {
	Merchant string `json:"merchant"`
	// Date is ISO yyyy-MM-dd, or "" when the bill's date was not legible.
	Date     string `json:"date"`
	Currency string `json:"currency"`
	Items    []Item `json:"items"`
	// SubtotalMinor is the items before charges, TaxMinor the net of ChargeLines, and
	// SubtotalMinor + TaxMinor == TotalMinor (within tolerance).
	SubtotalMinor int64        `json:"subtotalMinor"`
	TaxMinor      int64        `json:"taxMinor"`
	TotalMinor    int64        `json:"totalMinor"`
	ChargeLines   []ChargeLine `json:"chargeLines"`
}

// Item is one purchased line. AmountMinor is the line total for Qty, not the unit price.
type Item struct {
	Name        string `json:"name"`
	Qty         int    `json:"qty"`
	AmountMinor int64  `json:"amountMinor"`
}

// ChargeLine is one tax/fee/discount/round-off row as printed. AmountMinor is SIGNED in the
// direction it moves the bill: taxes and fees positive, discounts negative, round-off either.
type ChargeLine struct {
	Label       string `json:"label"`
	AmountMinor int64  `json:"amountMinor"`
	Kind        string `json:"kind"`
}

var chargeKinds = map[string]bool{"TAX": true, "FEE": true, "DISCOUNT": true, "ROUNDOFF": true}

// supportedMIME is the intersection of what Gemini and Groq both accept, so switching provider can
// never turn a previously-fine upload into a provider-side 400.
var supportedMIME = map[string]bool{"image/jpeg": true, "image/png": true, "image/webp": true}

// SupportedMIME reports whether an uploaded image type can be sent to any provider.
func SupportedMIME(mimeType string) bool {
	return supportedMIME[strings.ToLower(strings.TrimSpace(mimeType))]
}

// prompt is the whole contract the model is held to. It is deliberately explicit about integers:
// the Go decode into int64 rejects "12.34" outright, so a model that writes decimals produces an
// error rather than a silently truncated bill.
// The rules below are not generic prompt-writing. Each one names a mistake that was measured on
// real bills while benchmarking the on-device parser against SROIE (625 Malaysian receipts) and
// CORD (762 Indonesian) — see tools/receipt-eval. Those were the actual top failure classes, so
// they are the ones worth spending prompt tokens on. Removing a rule here should mean the
// corresponding failure has been re-measured, not that the prompt looked long.
const prompt = `You are reading a photographed restaurant or retail bill. Reply with ONLY a JSON object in this exact shape — no prose, no markdown, no code fence:

{"merchant":string,"date":string,"currency":string,"items":[{"name":string,"qty":integer,"amountMinor":integer}],"subtotalMinor":integer,"taxMinor":integer,"totalMinor":integer,"chargeLines":[{"label":string,"amountMinor":integer,"kind":string}]}

NUMBERS
- Every amount is an INTEGER in minor units: 12.34 becomes 1234, 1,550.00 becomes 155000. Never write a decimal point inside a number.
- Work out which separator is which from the bill as a whole. "48.000" on a bill whose other amounts are "12.500" and "3.000" is forty-eight thousand, not forty-eight. Decide once per bill and apply it consistently.
- A number inside a percentage is never an amount. "GST 6%" contributes 6 to nothing; only the currency figure beside it is money.

ITEMS
- An item is something the customer bought. Column headers, section titles, tax rows, subtotals, totals, tax-registration numbers, table/bill/invoice numbers, phone numbers, dates, times, cashier and server names, and "thank you" lines are never items.
- amountMinor is the line total for the whole quantity, not the unit price. If the line reads "2 x 280.00" or "1 @ 699/ea", the quantity is 2 or 1 and the amount is the line total printed at the right.
- qty is at least 1. Use 1 when no quantity is printed.
- A long item name may wrap onto the next line, and an indented modifier under an item ("Extra spicy", "No onion") belongs to the item above it — join them into one name rather than emitting a nameless row.

THE TOTAL — this is the field most often got wrong, so read carefully
- totalMinor is the single amount the customer actually pays.
- "Total Inclusive of GST", "Total incl. tax", "Grand Total" ARE the total. They are not tax rows, even though they contain a tax word.
- A tax-summary block near the bottom (a small table of tax code, taxable amount and tax amount, sometimes with its own "TOTAL" row) is NOT the bill total. Its figures are components. Ignore that block when choosing the total.
- "Cash", "Paid", "Tendered" and "Amount Received" are what the customer handed over, not the bill. If a "Change" or "Balance" row is present, the total is tendered minus change — never the tendered figure itself.
- A negative or savings figure ("Total Savings -2.98", "You Saved") is never the total.
- When several candidate totals are printed, take the LAST one, which is the payable figure after rounding.

CHARGES
- chargeLines are the bill's tax, service-charge, fee, discount and round-off rows, each labelled as printed.
- kind is exactly one of TAX, FEE, DISCOUNT, ROUNDOFF.
- amountMinor is signed in the direction it moves the bill: taxes and fees positive, discounts NEGATIVE, round-off as printed (either sign).
- If a combined row (e.g. "GST 5%") is printed alongside the split rows it summarises (e.g. "CGST 2.5%" and "SGST 2.5%"), keep only the combined row so nothing is counted twice.
- taxMinor is the sum of every chargeLines amountMinor.

IT MUST ADD UP
- subtotalMinor is the sum of the items; subtotalMinor + taxMinor must equal totalMinor.
- Check this before answering. If it does not balance, you have misread a figure — re-read the bill rather than adjusting a number to force the sum.

DATE, CURRENCY, MERCHANT
- date is yyyy-MM-dd, or "" if not legible. Read month names ("05 MAR 2018"). For an all-numeric date, prefer day-first unless that gives an impossible month; never take a bill, table or invoice number that happens to look like a date.
- currency is the symbol or code printed on the bill ("₹", "RM", "$", "EUR"). Use "" when the bill prints no symbol at all — do not guess one from the language or the merchant's country.
- merchant is the trading name a customer would recognise, normally the largest text at the top. Prefer the brand over a legal entity printed beside it, and never take a person's name, a greeting, or a slogan.

HONESTY
- Never invent a line, an amount or a date. Omit what you cannot read: "" for a string, and leave an unreadable bill's items empty.
- If the image is not a bill at all, return the shape with an empty items array and zero amounts.`

// tolerance is the slack allowed when reconciling items and charges against the printed total: one
// major unit, or 1% of the bill, whichever is larger. Real receipts round; a model that lands
// further out than this has misread a number, and a wrong draft costs more than a refused one.
func tolerance(totalMinor int64) int64 {
	if onePercent := totalMinor / 100; onePercent > 100 {
		return onePercent
	}
	return 100
}

func abs(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}

// decodeDraft parses the model's answer and validates it. Anything that cannot be a real bill —
// prose, floats, negative money, lines that do not add up — comes back as ErrRejected.
func decodeDraft(raw string) (Draft, error) {
	text := strings.TrimSpace(raw)
	// Vision models occasionally wrap JSON in a markdown fence even when asked for JSON only.
	if fenced := strings.TrimPrefix(text, "```"); fenced != text {
		fenced = strings.TrimPrefix(fenced, "json")
		text = strings.TrimSpace(strings.TrimSuffix(strings.TrimSpace(fenced), "```"))
	}
	var d Draft
	if err := json.Unmarshal([]byte(text), &d); err != nil {
		// The model's raw text may echo the bill; only the failure mode is reported.
		return Draft{}, fmt.Errorf("%w: response was not the requested JSON", ErrRejected)
	}
	if err := d.validate(); err != nil {
		return Draft{}, err
	}
	return d, nil
}

// validate normalises a decoded draft in place and refuses one that cannot be trusted.
func (d *Draft) validate() error {
	d.Merchant = strings.TrimSpace(d.Merchant)
	d.Currency = strings.TrimSpace(d.Currency)
	d.Date = strings.TrimSpace(d.Date)
	if _, err := time.Parse("2006-01-02", d.Date); d.Date != "" && err != nil {
		// A mangled date is dropped rather than fatal: the user picks one, and the money is intact.
		d.Date = ""
	}
	if d.TotalMinor <= 0 {
		return fmt.Errorf("%w: total is not positive", ErrRejected)
	}
	if d.SubtotalMinor < 0 {
		return fmt.Errorf("%w: subtotal is negative", ErrRejected)
	}
	if len(d.Items) == 0 {
		return fmt.Errorf("%w: no line items", ErrRejected)
	}
	var itemSum int64
	for i := range d.Items {
		it := &d.Items[i]
		it.Name = strings.TrimSpace(it.Name)
		if it.Name == "" || it.AmountMinor < 0 {
			return fmt.Errorf("%w: unusable line item", ErrRejected)
		}
		if it.Qty <= 0 {
			it.Qty = 1
		}
		itemSum += it.AmountMinor
	}
	var chargeSum int64
	for i := range d.ChargeLines {
		c := &d.ChargeLines[i]
		c.Label = strings.TrimSpace(c.Label)
		c.Kind = strings.ToUpper(strings.TrimSpace(c.Kind))
		if !chargeKinds[c.Kind] {
			return fmt.Errorf("%w: unknown charge kind", ErrRejected)
		}
		chargeSum += c.AmountMinor
	}
	if len(d.ChargeLines) > 0 {
		if d.TaxMinor == 0 {
			d.TaxMinor = chargeSum
		}
		if d.TaxMinor != chargeSum {
			return fmt.Errorf("%w: charge lines do not sum to the tax total", ErrRejected)
		}
	}
	if d.SubtotalMinor == 0 {
		d.SubtotalMinor = itemSum
	}
	slack := tolerance(d.TotalMinor)
	if abs(d.SubtotalMinor-itemSum) > slack {
		return fmt.Errorf("%w: items do not sum to the subtotal", ErrRejected)
	}
	if abs(d.SubtotalMinor+d.TaxMinor-d.TotalMinor) > slack {
		return fmt.Errorf("%w: subtotal plus charges does not reconcile with the total", ErrRejected)
	}
	return nil
}

// postJSON sends body to endpoint and returns the response payload, mapping transport and status
// failures onto ErrUnavailable/ErrRejected.
//
// Errors name only the provider and the status: the request body carries the image and the headers
// carry the API key, and neither may ever reach a caller's log.
func postJSON(ctx context.Context, client *http.Client, name, endpoint string, headers map[string]string, body any) ([]byte, error) {
	encoded, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(encoded))
	if err != nil {
		return nil, fmt.Errorf("%w: %s request could not be built", ErrUnavailable, name)
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		// url.Error would repeat the endpoint, which for some providers carries the key.
		return nil, fmt.Errorf("%w: %s request failed", ErrUnavailable, name)
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	switch resp.StatusCode {
	case http.StatusOK:
		return payload, nil
	case http.StatusBadRequest, http.StatusRequestEntityTooLarge,
		http.StatusUnsupportedMediaType, http.StatusUnprocessableEntity:
		// The image itself was refused; retrying the same photo will not help.
		return nil, fmt.Errorf("%w: %s status %d", ErrRejected, name, resp.StatusCode)
	default:
		// 401/403/404 are our misconfiguration and 429/5xx are the provider's bad day. Neither is
		// the caller's fault, so both read as "unavailable" rather than blaming their photo.
		return nil, fmt.Errorf("%w: %s status %d", ErrUnavailable, name, resp.StatusCode)
	}
}

// Select returns the provider a deployment has configured: Gemini when its key is present,
// otherwise Groq, or nil when neither key is set (cloud extraction stays off).
func Select(geminiKey, geminiModel, groqKey, groqModel string) Provider {
	switch {
	case strings.TrimSpace(geminiKey) != "":
		return NewGemini(geminiKey, geminiModel)
	case strings.TrimSpace(groqKey) != "":
		return NewGroq(groqKey, groqModel)
	default:
		return nil
	}
}
