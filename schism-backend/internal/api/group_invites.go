package api

import (
	"fmt"
	"html"
	"net/http"

	"github.com/go-chi/chi/v5"
)

func (h *Handler) createGroupInvite(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r.Context())
	raw, expiresAt, err := h.store.CreateGroupInvite(r.Context(), chi.URLParam(r, "groupID"), user.ID)
	if err != nil {
		writeInviteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"token": raw, "expiresAt": expiresAt})
}

func (h *Handler) revokeGroupInvite(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r.Context())
	if err := h.store.RevokeGroupInvite(r.Context(), chi.URLParam(r, "groupID"), user.ID); err != nil {
		writeInviteError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) previewGroupInvite(w http.ResponseWriter, r *http.Request) {
	preview, err := h.store.PreviewGroupInvite(r.Context(), chi.URLParam(r, "token"))
	if err != nil {
		writeInviteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, preview)
}

func (h *Handler) redeemGroupInvite(w http.ResponseWriter, r *http.Request) {
	groupID, err := h.store.RedeemGroupInvite(r.Context(), chi.URLParam(r, "token"), userFromContext(r.Context()).ID)
	if err != nil {
		writeInviteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"groupId": groupID})
}

// groupInviteLanding bounces a shared group link into the app without exposing anything about the
// group — the token is the only thing on the page.
func (h *Handler) groupInviteLanding(w http.ResponseWriter, r *http.Request) {
	deep := "schism://group-invite/" + html.EscapeString(chi.URLParam(r, "token"))
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="0; url=%s"><title>Join on Schism</title></head>
<body><main><h1>Join the group on Schism</h1><p>This link adds you to the group.</p>
<a href="%s">Open in Schism</a></main><script>location.href=%q;</script></body></html>`, deep, deep, deep)
}
