package api

import (
	"context"
	"net/http"

	"github.com/go-chi/chi/v5"
)

func participantFromContext(ctx context.Context) string {
	participantID, _ := ctx.Value(participantKey).(string)
	return participantID
}

// memberParticipant resolves the authenticated user to their linked participant.
// Missing groups are 404; existing groups that do not contain the caller are 403.
func (h *Handler) memberParticipant(w http.ResponseWriter, r *http.Request, groupID string) (string, bool) {
	user := userFromContext(r.Context())
	if user == nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized")
		return "", false
	}
	group, err := h.store.GetGroup(r.Context(), groupID)
	if err != nil {
		writeInternalError(w, r, err)
		return "", false
	}
	if group == nil {
		writeErr(w, http.StatusNotFound, "group not found")
		return "", false
	}
	participantID, err := h.store.ParticipantForUserInGroup(r.Context(), user.ID, groupID)
	if err != nil {
		writeInternalError(w, r, err)
		return "", false
	}
	if participantID == "" {
		writeErr(w, http.StatusForbidden, "not a member of this group")
		return "", false
	}
	return participantID, true
}

func (h *Handler) requireGroupMember(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		participantID, ok := h.memberParticipant(w, r, chi.URLParam(r, "groupID"))
		if !ok {
			return
		}
		ctx := context.WithValue(r.Context(), participantKey, participantID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}
