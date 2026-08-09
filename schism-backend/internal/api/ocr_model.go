package api

import (
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/modelcatalog"
)

func (h *Handler) ocrModelManifest(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "public, max-age=300")
	w.Header().Set("ETag", modelcatalog.ETag)
	if r.Header.Get("If-None-Match") == modelcatalog.ETag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	writeJSON(w, http.StatusOK, modelcatalog.OCR())
}

func (h *Handler) ocrModelArtifact(w http.ResponseWriter, r *http.Request) {
	artifact, ok := modelcatalog.Lookup(chi.URLParam(r, "version"), chi.URLParam(r, "file"))
	if !ok {
		writeErr(w, http.StatusNotFound, "model artifact not found")
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	w.Header().Set("X-Artifact-Bytes", strconv.FormatInt(artifact.Bytes, 10))
	w.Header().Set("X-Checksum-SHA256", artifact.SHA256)
	http.Redirect(w, r, artifact.UpstreamURL, http.StatusTemporaryRedirect)
}
