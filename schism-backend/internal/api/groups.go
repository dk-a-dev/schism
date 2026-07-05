package api

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
)

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
	g, err := h.store.CreateGroup(r.Context(), d.toInput())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
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
	var d groupFormDTO
	if err := json.NewDecoder(r.Body).Decode(&d); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid json")
		return
	}
	g, err := h.store.UpdateGroup(r.Context(), chi.URLParam(r, "groupID"), d.toInput())
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
