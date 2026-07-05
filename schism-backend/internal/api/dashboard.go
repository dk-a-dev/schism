package api

import (
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/analytics"
)

// getGroupDashboard returns rich insights for one group. Optional ?participant=<id> attaches the
// requesting participant's personal slice.
func (h *Handler) getGroupDashboard(w http.ResponseWriter, r *http.Request) {
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
	expenses, err := h.store.ListExpenses(r.Context(), groupID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	cats, err := h.store.ListCategories(r.Context())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	dash := analytics.BuildGroupDashboard(*g, expenses, cats, r.URL.Query().Get("participant"))
	writeJSON(w, http.StatusOK, dash)
}

// getPersonalDashboard aggregates the requesting person's position across many groups.
// Query: ?groupIds=a,b,c&participant=<name|id>
func (h *Handler) getPersonalDashboard(w http.ResponseWriter, r *http.Request) {
	identity := strings.TrimSpace(r.URL.Query().Get("participant"))
	if identity == "" {
		writeErr(w, http.StatusBadRequest, "participant query param is required")
		return
	}
	idsParam := strings.TrimSpace(r.URL.Query().Get("groupIds"))
	if idsParam == "" {
		writeJSON(w, http.StatusOK, analytics.BuildPersonalDashboard(identity, nil))
		return
	}
	var ges []analytics.GroupExpenses
	for _, gid := range strings.Split(idsParam, ",") {
		gid = strings.TrimSpace(gid)
		if gid == "" {
			continue
		}
		g, err := h.store.GetGroup(r.Context(), gid)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		if g == nil {
			continue
		}
		expenses, err := h.store.ListExpenses(r.Context(), gid)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		ges = append(ges, analytics.GroupExpenses{Group: *g, Expenses: expenses})
	}
	writeJSON(w, http.StatusOK, analytics.BuildPersonalDashboard(identity, ges))
}
