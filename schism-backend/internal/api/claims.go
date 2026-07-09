package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/store"
)

// pluralize renders "N noun" or "N nouns" depending on n, for short activity-log detail strings.
func pluralize(n int, noun string) string {
	if n == 1 {
		return fmt.Sprintf("1 %s", noun)
	}
	return fmt.Sprintf("%d %ss", n, noun)
}

// claimSessionResponse is a claim session plus the live "owesPreview" (what each participant would owe
// given the CURRENT claims, no unclaimed-item resolutions).
type claimSessionResponse struct {
	*store.ClaimSession
	OwesPreview map[string]int64 `json:"owesPreview"`
	// ReadyParticipantIds mirrors ClaimSession.Ready as an explicit response field (never null).
	ReadyParticipantIds []string `json:"readyParticipantIds"`
}

// callerParticipant resolves the authenticated caller to their participant id in the given group.
// It writes the response and returns ("", false) when the caller is unauthenticated (401) or not a
// member of the group (403). A caller may act only as their own participant.
func (h *Handler) callerParticipant(w http.ResponseWriter, r *http.Request, groupID string) (string, bool) {
	u := userFromContext(r.Context())
	if u == nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized")
		return "", false
	}
	pid, err := h.store.ParticipantForUserInGroup(r.Context(), u.ID, groupID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return "", false
	}
	if pid == "" {
		writeErr(w, http.StatusForbidden, "not a member of this group")
		return "", false
	}
	return pid, true
}

func allParticipantIDs(g *store.Group) []string {
	ids := make([]string, len(g.Participants))
	for i, p := range g.Participants {
		ids[i] = p.ID
	}
	return ids
}

func (h *Handler) createClaimSession(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	g, err := h.store.GetGroup(r.Context(), groupID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if g == nil {
		writeErr(w, http.StatusNotFound, "group not found")
		return
	}
	creator, ok := h.callerParticipant(w, r, groupID)
	if !ok {
		return
	}

	var d createClaimSessionDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	cs, err := h.store.CreateClaimSession(r.Context(), store.ClaimSessionInput{
		GroupID: groupID, CreatorParticipantID: creator, Title: d.Title, Currency: d.Currency,
		Items:         d.toStoreItems(),
		TaxMinor:      d.TaxMinor,
		FeesMinor:     d.FeesMinor,
		DiscountMinor: d.DiscountMinor,
		RoundoffMinor: d.RoundoffMinor,
		Taxes:         d.toStoreTaxes(),
	})
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = h.store.LogActivity(r.Context(), groupID, "CLAIM_SESSION_CREATED", actor(creator), nil, cs.Title)
	h.writeSession(w, r, http.StatusCreated, &cs)
}

func (h *Handler) getClaimSession(w http.ResponseWriter, r *http.Request) {
	sid := chi.URLParam(r, "sid")
	cs, err := h.store.GetClaimSession(r.Context(), sid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if cs == nil {
		writeErr(w, http.StatusNotFound, "claim session not found")
		return
	}
	if _, ok := h.callerParticipant(w, r, cs.GroupID); !ok {
		return
	}
	h.writeSession(w, r, http.StatusOK, cs)
}

// writeSession loads the session's group, computes owesPreview over the current claims, and writes
// the combined response.
func (h *Handler) writeSession(w http.ResponseWriter, r *http.Request, status int, cs *store.ClaimSession) {
	g, err := h.store.GetGroup(r.Context(), cs.GroupID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	var preview map[string]int64
	if g != nil {
		preview = store.ComputeClaimSplit(cs.Items, cs.Claims, cs.TaxMinor, cs.FeesMinor,
			cs.DiscountMinor, cs.RoundoffMinor, nil, allParticipantIDs(g), cs.CreatorParticipantID)
	}
	if preview == nil {
		preview = map[string]int64{}
	}
	ready := cs.Ready
	if ready == nil {
		ready = []string{}
	}
	writeJSON(w, status, claimSessionResponse{ClaimSession: cs, OwesPreview: preview, ReadyParticipantIds: ready})
}

func (h *Handler) putClaims(w http.ResponseWriter, r *http.Request) {
	sid := chi.URLParam(r, "sid")
	cs, err := h.store.GetClaimSession(r.Context(), sid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if cs == nil {
		writeErr(w, http.StatusNotFound, "claim session not found")
		return
	}
	pid, ok := h.callerParticipant(w, r, cs.GroupID)
	if !ok {
		return
	}
	var d putClaimsDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	weights := map[int]float64{}
	for _, wt := range d.Weights {
		weights[wt.ItemIdx] = wt.Weight
	}
	err = h.store.UpsertClaims(r.Context(), sid, pid, d.ExpectedVersion, weights)
	if errors.Is(err, store.ErrClaimLocked) {
		writeErr(w, http.StatusConflict, "LOCKED")
		return
	}
	if errors.Is(err, store.ErrClaimStale) {
		writeErr(w, http.StatusConflict, "VERSION_STALE")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	claimed := 0
	for _, weight := range weights {
		if weight > 0 {
			claimed++
		}
	}
	_ = h.store.LogActivity(r.Context(), cs.GroupID, "CLAIM_SUBMITTED", actor(pid), nil, "claimed "+pluralize(claimed, "item"))
	updated, err := h.store.GetClaimSession(r.Context(), sid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	h.writeSession(w, r, http.StatusOK, updated)
}

func (h *Handler) finalizeClaimSession(w http.ResponseWriter, r *http.Request) {
	sid := chi.URLParam(r, "sid")
	cs, err := h.store.GetClaimSession(r.Context(), sid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if cs == nil {
		writeErr(w, http.StatusNotFound, "claim session not found")
		return
	}
	pid, ok := h.callerParticipant(w, r, cs.GroupID)
	if !ok {
		return
	}
	if pid != cs.CreatorParticipantID {
		writeErr(w, http.StatusForbidden, "only the creator can finalize")
		return
	}
	var d finalizeDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	eid, err := h.store.FinalizeClaimSession(r.Context(), sid, d.ExpectedVersion, d.toResolutions())
	if errors.Is(err, store.ErrClaimLocked) {
		writeErr(w, http.StatusConflict, "LOCKED")
		return
	}
	if errors.Is(err, store.ErrClaimStale) {
		writeErr(w, http.StatusConflict, "VERSION_STALE")
		return
	}
	if errors.Is(err, store.ErrUnresolvedItems) {
		writeErr(w, http.StatusConflict, "UNRESOLVED_ITEMS")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	// Only log when this call actually performed the finalize (cs was loaded before finalize ran, so
	// "open" here means this request is the one that transitioned it, not an idempotent replay of an
	// already-finalized session).
	if cs.Status == "open" {
		_ = h.store.LogActivity(r.Context(), cs.GroupID, "CLAIM_SESSION_FINALIZED", actor(cs.CreatorParticipantID), nil, cs.Title)
		_ = h.store.LogActivity(r.Context(), cs.GroupID, "CREATE_EXPENSE", actor(cs.CreatorParticipantID), &eid, cs.Title)
	}
	writeJSON(w, http.StatusOK, map[string]string{"expenseId": eid})
}

func (h *Handler) cancelClaimSession(w http.ResponseWriter, r *http.Request) {
	sid := chi.URLParam(r, "sid")
	cs, err := h.store.GetClaimSession(r.Context(), sid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if cs == nil {
		writeErr(w, http.StatusNotFound, "claim session not found")
		return
	}
	pid, ok := h.callerParticipant(w, r, cs.GroupID)
	if !ok {
		return
	}
	if pid != cs.CreatorParticipantID {
		writeErr(w, http.StatusForbidden, "only the creator can cancel")
		return
	}
	if err := h.store.CancelClaimSession(r.Context(), sid); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	// CancelClaimSession is a no-op unless cs was still "open" (loaded above, before cancel ran), so
	// this guard keeps a repeat cancel call from logging a second CLAIM_SESSION_CANCELLED activity.
	if cs.Status == "open" {
		_ = h.store.LogActivity(r.Context(), cs.GroupID, "CLAIM_SESSION_CANCELLED", actor(pid), nil, cs.Title)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) setReady(w http.ResponseWriter, r *http.Request) {
	sid := chi.URLParam(r, "sid")
	cs, err := h.store.GetClaimSession(r.Context(), sid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if cs == nil {
		writeErr(w, http.StatusNotFound, "claim session not found")
		return
	}
	pid, ok := h.callerParticipant(w, r, cs.GroupID)
	if !ok {
		return
	}
	var d setReadyDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	err = h.store.SetReady(r.Context(), sid, pid, d.Ready)
	if errors.Is(err, store.ErrClaimLocked) {
		writeErr(w, http.StatusConflict, "LOCKED")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	updated, err := h.store.GetClaimSession(r.Context(), sid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	h.writeSession(w, r, http.StatusOK, updated)
}

func (h *Handler) editClaimItems(w http.ResponseWriter, r *http.Request) {
	sid := chi.URLParam(r, "sid")
	cs, err := h.store.GetClaimSession(r.Context(), sid)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if cs == nil {
		writeErr(w, http.StatusNotFound, "claim session not found")
		return
	}
	pid, ok := h.callerParticipant(w, r, cs.GroupID)
	if !ok {
		return
	}
	if pid != cs.CreatorParticipantID {
		writeErr(w, http.StatusForbidden, "only the creator can edit items")
		return
	}
	var d editItemsDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	items := d.toStoreItems()
	v, err := h.store.EditItems(r.Context(), sid, items)
	if errors.Is(err, store.ErrClaimLocked) {
		writeErr(w, http.StatusConflict, "LOCKED")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = h.store.LogActivity(r.Context(), cs.GroupID, "CLAIM_ITEMS_EDITED", actor(pid), nil, "edited "+pluralize(len(items), "item"))
	writeJSON(w, http.StatusOK, map[string]int{"version": v})
}
