package api

import (
	"io"
	"net"
	"net/http"
	"os"
	"time"
)

// Default on-device LLM: ungated, Apache-2.0 Qwen2.5-1.5B (MediaPipe .task) — downloads with no
// token, so the app "just works". Override with MODEL_UPSTREAM_URL (e.g. a smaller/self-hosted
// file). Set HF_TOKEN to proxy a license-gated model (e.g. Gemma) without exposing the token to
// clients — best practice: the token lives on the server, never in the app.
// ekv4096 = 4096-token context: receipt prompts are ~1k tokens, so the small-context (1280) build
// would overflow and make on-device parsing fail silently.
const defaultModelUpstream = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"

var modelHTTPClient = &http.Client{Transport: &http.Transport{
	Proxy:                 http.ProxyFromEnvironment,
	DialContext:           (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
	TLSHandshakeTimeout:   10 * time.Second,
	ResponseHeaderTimeout: 20 * time.Second,
	IdleConnTimeout:       90 * time.Second,
}}

// modelDownload serves the on-device model to the app. With no HF_TOKEN it simply redirects to the
// (ungated) upstream so the device downloads directly. With HF_TOKEN set it proxies the bytes,
// attaching the token server-side so gated models work without shipping a token in the app.
func (h *Handler) modelDownload(w http.ResponseWriter, r *http.Request) {
	upstream := os.Getenv("MODEL_UPSTREAM_URL")
	if upstream == "" {
		upstream = defaultModelUpstream
	}
	token := os.Getenv("HF_TOKEN")

	if token == "" {
		http.Redirect(w, r, upstream, http.StatusFound)
		return
	}

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, upstream, nil)
	if err != nil {
		http.Error(w, "bad upstream", http.StatusInternalServerError)
		return
	}
	req.Header.Set("Authorization", "Bearer "+token)
	for _, header := range []string{"Range", "If-None-Match", "If-Modified-Since"} {
		if value := r.Header.Get(header); value != "" {
			req.Header.Set(header, value)
		}
	}
	resp, err := modelHTTPClient.Do(req)
	if err != nil {
		http.Error(w, "upstream fetch failed", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent && resp.StatusCode != http.StatusNotModified {
		http.Error(w, "upstream returned "+resp.Status, http.StatusBadGateway)
		return
	}
	for _, header := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"} {
		if value := resp.Header.Get(header); value != "" {
			w.Header().Set(header, value)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}
