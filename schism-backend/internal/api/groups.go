package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/store"
)

// sanitizeParticipantUserIDs enforces identity: a participant may be linked to a user_id only by that
// user themselves. Any UserID that doesn't match the authenticated caller is dropped (set to nil),
// and when the caller is unauthenticated (self == nil) ALL links are dropped. This is the point of
// the token — groups stay reachable by id, but you can't spoof "this participant is you".
func sanitizeParticipantUserIDs(parts []store.ParticipantInput, self *store.User) {
	for i := range parts {
		if parts[i].UserID == nil {
			continue
		}
		if self == nil || *parts[i].UserID != self.ID {
			parts[i].UserID = nil
		}
	}
}

func (h *Handler) createGroup(w http.ResponseWriter, r *http.Request) {
	var d groupFormDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	if len(strings.TrimSpace(d.Name)) < 2 || len(d.Participants) < 1 {
		writeErr(w, http.StatusBadRequest, "name>=2 chars and >=1 participant required")
		return
	}
	in := d.toInput()
	sanitizeParticipantUserIDs(in.Participants, userFromContext(r.Context()))
	g, err := h.store.CreateGroup(r.Context(), in)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	_ = h.store.LogActivity(r.Context(), g.ID, "GROUP_CREATED", nil, nil, g.Name)
	writeJSON(w, http.StatusCreated, map[string]string{"groupId": g.ID})
}

func (h *Handler) getGroup(w http.ResponseWriter, r *http.Request) {
	g, err := h.store.GetGroup(r.Context(), chi.URLParam(r, "groupID"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if g == nil {
		writeErr(w, http.StatusNotFound, "group not found")
		return
	}
	writeJSON(w, http.StatusOK, g)
}

func (h *Handler) getGroupDetails(w http.ResponseWriter, r *http.Request) {
	h.getGroup(w, r) // details == group + participants in v1
}

func (h *Handler) updateGroup(w http.ResponseWriter, r *http.Request) {
	groupID := chi.URLParam(r, "groupID")
	var d groupFormDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	in := d.toInput()
	sanitizeParticipantUserIDs(in.Participants, userFromContext(r.Context()))
	before, err := h.store.GetGroup(r.Context(), groupID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	g, err := h.store.UpdateGroup(r.Context(), groupID, in)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if g == nil {
		writeErr(w, http.StatusNotFound, "group not found")
		return
	}
	h.logGroupUpdateActivity(r.Context(), before, g)
	writeJSON(w, http.StatusOK, g)
}

// logGroupUpdateActivity diffs the participant set (before -> after) and logs one MEMBER_ADDED or
// MEMBER_REMOVED activity per participant that appeared or disappeared, plus a GROUP_RENAMED activity
// when the group's name changed. Best-effort: never fails the request.
func (h *Handler) logGroupUpdateActivity(ctx context.Context, before, after *store.Group) {
	if before == nil || after == nil {
		return
	}
	if before.Name != after.Name {
		_ = h.store.LogActivity(ctx, after.ID, "GROUP_RENAMED", nil, nil, after.Name)
	}
	oldByID := make(map[string]string, len(before.Participants))
	for _, p := range before.Participants {
		oldByID[p.ID] = p.Name
	}
	newByID := make(map[string]string, len(after.Participants))
	for _, p := range after.Participants {
		newByID[p.ID] = p.Name
	}
	for id, name := range newByID {
		if _, ok := oldByID[id]; !ok {
			_ = h.store.LogActivity(ctx, after.ID, "MEMBER_ADDED", nil, nil, name)
		}
	}
	for id, name := range oldByID {
		if _, ok := newByID[id]; !ok {
			_ = h.store.LogActivity(ctx, after.ID, "MEMBER_REMOVED", nil, nil, name)
		}
	}
}

func (h *Handler) listGroups(w http.ResponseWriter, r *http.Request) {
	idsParam := r.URL.Query().Get("ids")
	if idsParam == "" {
		writeJSON(w, http.StatusOK, []any{})
		return
	}
	ids := strings.Split(idsParam, ",")
	groups, err := h.store.ListGroups(r.Context(), ids)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, groups)
}
