package api

import (
	"errors"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/store"
)

func writeInviteError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, store.ErrInviteNotFound), errors.Is(err, store.ErrParticipantNotFound):
		writeErr(w, http.StatusNotFound, "invite not found")
	case errors.Is(err, store.ErrInviteExpired):
		writeErr(w, http.StatusGone, "invite expired")
	case errors.Is(err, store.ErrInviteRevoked):
		writeErr(w, http.StatusGone, "invite revoked")
	case errors.Is(err, store.ErrInviteUsed), errors.Is(err, store.ErrParticipantLinked), errors.Is(err, store.ErrAlreadyGroupMember):
		writeErr(w, http.StatusConflict, "invite cannot be redeemed")
	case errors.Is(err, store.ErrNotGroupMember):
		writeErr(w, http.StatusForbidden, "not a member of this group")
	default:
		writeInternalError(w, r, err)
	}
}

func (h *Handler) createParticipantInvite(w http.ResponseWriter, r *http.Request) {
	user := userFromContext(r.Context())
	raw, expiresAt, err := h.store.CreateParticipantInvite(r.Context(), chi.URLParam(r, "groupID"), chi.URLParam(r, "participantID"), user.ID)
	if err != nil {
		writeInviteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"token": raw, "expiresAt": expiresAt})
}

func (h *Handler) previewParticipantInvite(w http.ResponseWriter, r *http.Request) {
	preview, err := h.store.PreviewParticipantInvite(r.Context(), chi.URLParam(r, "token"))
	if err != nil {
		writeInviteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, preview)
}

func (h *Handler) redeemParticipantInvite(w http.ResponseWriter, r *http.Request) {
	groupID, err := h.store.RedeemParticipantInvite(r.Context(), chi.URLParam(r, "token"), userFromContext(r.Context()).ID)
	if err != nil {
		writeInviteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"groupId": groupID})
}
