package api

import (
	"context"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/store"
)

func (h *Handler) createGroup(w http.ResponseWriter, r *http.Request) {
	var d groupFormDTO
	if !decodeJSON(w, r, &d) {
		return
	}
	if len(strings.TrimSpace(d.Name)) < 2 || len(d.Participants) < 1 {
		writeErr(w, http.StatusBadRequest, "name>=2 chars and >=1 participant required")
		return
	}
	in := d.toInput()
	user := userFromContext(r.Context())
	g, err := h.store.CreateGroupForUser(r.Context(), in, user.ID)
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	creator, _ := h.store.ParticipantForUserInGroup(r.Context(), user.ID, g.ID)
	_ = h.store.LogActivity(r.Context(), g.ID, "GROUP_CREATED", actor(creator), nil, g.Name)
	writeJSON(w, http.StatusCreated, map[string]string{"groupId": g.ID})
}

func (h *Handler) getGroup(w http.ResponseWriter, r *http.Request) {
	g, err := h.store.GetGroup(r.Context(), chi.URLParam(r, "groupID"))
	if err != nil {
		writeInternalError(w, r, err)
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
	if !decodeJSON(w, r, &d) {
		return
	}
	in := d.toInput()
	if len(strings.TrimSpace(d.Name)) < 2 || len(d.Participants) < 1 {
		writeErr(w, http.StatusBadRequest, "name>=2 chars and >=1 participant required")
		return
	}
	actorID := participantFromContext(r.Context())
	callerPresent := false
	for _, participant := range in.Participants {
		if participant.ID != nil && *participant.ID == actorID {
			callerPresent = true
			break
		}
	}
	if !callerPresent {
		writeErr(w, http.StatusBadRequest, "cannot remove your own participant")
		return
	}
	before, err := h.store.GetGroup(r.Context(), groupID)
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	g, err := h.store.UpdateGroup(r.Context(), groupID, in)
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	if g == nil {
		writeErr(w, http.StatusNotFound, "group not found")
		return
	}
	h.logGroupUpdateActivity(r.Context(), before, g, actorID)
	writeJSON(w, http.StatusOK, g)
}

// logGroupUpdateActivity diffs the participant set (before -> after) and logs one MEMBER_ADDED or
// MEMBER_REMOVED activity per participant that appeared or disappeared, plus a GROUP_RENAMED activity
// when the group's name changed. Best-effort: never fails the request.
func (h *Handler) logGroupUpdateActivity(ctx context.Context, before, after *store.Group, actorID string) {
	if before == nil || after == nil {
		return
	}
	if before.Name != after.Name {
		_ = h.store.LogActivity(ctx, after.ID, "GROUP_RENAMED", actor(actorID), nil, after.Name)
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
			_ = h.store.LogActivity(ctx, after.ID, "MEMBER_ADDED", actor(actorID), nil, name)
		}
	}
	for id, name := range oldByID {
		if _, ok := newByID[id]; !ok {
			_ = h.store.LogActivity(ctx, after.ID, "MEMBER_REMOVED", actor(actorID), nil, name)
		}
	}
}

func (h *Handler) listGroups(w http.ResponseWriter, r *http.Request) {
	var requested []string
	if idsParam := strings.TrimSpace(r.URL.Query().Get("ids")); idsParam != "" {
		for _, groupID := range strings.Split(idsParam, ",") {
			groupID = strings.TrimSpace(groupID)
			if groupID != "" {
				requested = append(requested, groupID)
			}
			if len(requested) == 100 {
				break
			}
		}
	}
	user := userFromContext(r.Context())
	ids, err := h.store.AuthorizedGroupIDs(r.Context(), user.ID, requested)
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	groups, err := h.store.ListGroups(r.Context(), ids)
	if err != nil {
		writeInternalError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, groups)
}
