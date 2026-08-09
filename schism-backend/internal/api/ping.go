package api

import "net/http"

func (h *Handler) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// ping is a friendly health check at the root: `GET /ping` → a split-themed "pong".
func (h *Handler) ping(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"message": "pong! 🪙 every rupee accounted for — Schism is up.",
		"app":     "schism",
		"status":  "ok",
	})
}
