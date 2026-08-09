package api

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/schism/schism-backend/internal/modelcatalog"
	"github.com/stretchr/testify/require"
)

func TestOCRManifestHTTPContract(t *testing.T) {
	srv := newTestServer(t)
	resp, err := http.Get(srv.URL + "/v1/models/ocr/manifest")
	require.NoError(t, err)
	defer resp.Body.Close()
	require.Equal(t, http.StatusOK, resp.StatusCode)
	require.Equal(t, "public, max-age=300", resp.Header.Get("Cache-Control"))
	require.NotEmpty(t, resp.Header.Get("ETag"))
	var manifest modelcatalog.OCRManifest
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&manifest))
	require.Equal(t, int64(6298800), manifest.TotalBytes)

	conditional, err := http.NewRequest(http.MethodGet, srv.URL+"/v1/models/ocr/manifest", nil)
	require.NoError(t, err)
	conditional.Header.Set("If-None-Match", resp.Header.Get("ETag"))
	notModified, err := http.DefaultClient.Do(conditional)
	require.NoError(t, err)
	defer notModified.Body.Close()
	require.Equal(t, http.StatusNotModified, notModified.StatusCode)
}

func TestOCRArtifactRedirectAndHEADParity(t *testing.T) {
	srv := newTestServer(t)
	client := &http.Client{CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}
	path := srv.URL + "/v1/models/ocr/2026.06/det.onnx"
	get, err := client.Get(path)
	require.NoError(t, err)
	defer get.Body.Close()
	require.Equal(t, http.StatusTemporaryRedirect, get.StatusCode)
	require.Contains(t, get.Header.Get("Location"), "2ba1506c0380b8f0b03dd142459aac66d4421f6c")
	require.Equal(t, "public, max-age=31536000, immutable", get.Header.Get("Cache-Control"))
	require.Equal(t, "1780590", get.Header.Get("X-Artifact-Bytes"))

	req, err := http.NewRequest(http.MethodHead, path, nil)
	require.NoError(t, err)
	head, err := client.Do(req)
	require.NoError(t, err)
	defer head.Body.Close()
	require.Equal(t, get.StatusCode, head.StatusCode)
	require.Equal(t, get.Header.Get("Location"), head.Header.Get("Location"))
	require.Equal(t, get.Header.Get("X-Checksum-SHA256"), head.Header.Get("X-Checksum-SHA256"))

	unknown, err := client.Get(srv.URL + "/v1/models/ocr/latest/det.onnx?url=https://evil.example/model")
	require.NoError(t, err)
	defer unknown.Body.Close()
	require.Equal(t, http.StatusNotFound, unknown.StatusCode)
}
