package api

import (
	"context"
	"encoding/base64"
	"errors"
	"log"
	"math"
	"net/http"
	"strconv"
	"time"

	"github.com/schism/schism-backend/internal/receiptai"
)

// maxReceiptImageBytes caps the decoded image. Groq tops out at 20 MB and Gemini higher, but 8 MB is
// already a generous phone photo, and a smaller ceiling means a smaller thing to hold in memory.
const maxReceiptImageBytes = 8 << 20

// maxReceiptBodyBytes is the request ceiling: base64 inflates by 4/3, plus room for the envelope.
const maxReceiptBodyBytes = maxReceiptImageBytes*4/3 + 4096

// receiptExtractTimeout bounds a provider call. It sits inside the server's 30s WriteTimeout so a
// slow model produces a clean 502 rather than a truncated response.
const receiptExtractTimeout = 20 * time.Second

// extractReceiptDTO is the request. The image arrives base64-encoded in JSON (rather than
// multipart) so the endpoint matches every other route's content type and decoding path.
//
// The image is never written down: it lives in this request's memory, goes to the provider, and is
// garbage. No handler here logs the body, and no error path echoes it.
type extractReceiptDTO struct {
	GroupID     string `json:"groupId"`
	MimeType    string `json:"mimeType"`
	ImageBase64 string `json:"imageBase64"`
}

// rateLimitedDTO tells a refused caller exactly when they may try again.
type rateLimitedDTO struct {
	Error             string    `json:"error"`
	RetryAfterSeconds int       `json:"retryAfterSeconds"`
	NextAllowedAt     time.Time `json:"nextAllowedAt"`
}

// extractReceipt runs one cloud extraction for the caller in a group. Registered only when a
// provider is configured, so a deployment with the feature off answers 404 for this path.
func (h *Handler) extractReceipt(w http.ResponseWriter, r *http.Request) {
	var d extractReceiptDTO
	if !decodeJSONLimit(w, r, &d, maxReceiptBodyBytes) {
		return
	}
	if !receiptai.SupportedMIME(d.MimeType) {
		writeErr(w, http.StatusUnsupportedMediaType, "image_must_be_jpeg_png_or_webp")
		return
	}
	// Size is checked before anything else costs money: no provider call, no limiter slot spent.
	image, err := base64.StdEncoding.DecodeString(d.ImageBase64)
	if err != nil || len(image) == 0 {
		writeErr(w, http.StatusBadRequest, "invalid_image")
		return
	}
	if len(image) > maxReceiptImageBytes {
		writeErr(w, http.StatusRequestEntityTooLarge, "image_too_large")
		return
	}

	// memberParticipant is the authorization gate: unknown group is 404, non-member is 403. It also
	// rejects an unauthenticated caller, so user is non-nil below.
	if _, ok := h.memberParticipant(w, r, d.GroupID); !ok {
		return
	}
	user := userFromContext(r.Context())

	nextAt, granted, err := h.store.ClaimReceiptExtraction(r.Context(), user.ID, time.Now())
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	if !granted {
		retryIn := time.Until(nextAt)
		if retryIn < 0 {
			retryIn = 0
		}
		seconds := int(math.Ceil(retryIn.Seconds()))
		w.Header().Set("Retry-After", strconv.Itoa(seconds))
		writeJSON(w, http.StatusTooManyRequests, rateLimitedDTO{
			Error:             "receipt_extract_rate_limited",
			RetryAfterSeconds: seconds,
			NextAllowedAt:     nextAt.UTC(),
		})
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), receiptExtractTimeout)
	defer cancel()
	draft, err := h.extractor.Extract(ctx, image, d.MimeType)
	if err != nil {
		// Only the provider name and the failure class are logged — never the image, the prompt,
		// the model's text, or the credential.
		log.Printf("request_id=%q receipt_extract_failed provider=%q reason=%q",
			requestID(r), h.extractor.Name(), extractFailureReason(err))
		if errors.Is(err, receiptai.ErrRejected) {
			writeErr(w, http.StatusUnprocessableEntity, "receipt_not_readable")
			return
		}
		writeErr(w, http.StatusBadGateway, "receipt_extraction_unavailable")
		return
	}
	writeJSON(w, http.StatusOK, draft)
}

// extractFailureReason collapses a provider error to a fixed label. Provider error strings are
// already free of the image and the key, but a fixed label keeps it that way by construction.
func extractFailureReason(err error) string {
	if errors.Is(err, receiptai.ErrRejected) {
		return "rejected"
	}
	return "unavailable"
}
