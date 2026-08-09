package modelcatalog

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func TestOCRCatalogIsPinnedAndComplete(t *testing.T) {
	manifest := OCR()
	require.Equal(t, "2026.06", manifest.Version)
	require.Equal(t, 10300, manifest.MinimumAppVersionCode)
	require.Equal(t, int64(6298800), manifest.TotalBytes)
	require.Len(t, manifest.Artifacts, 3)
	var sum int64
	for _, artifact := range manifest.Artifacts {
		require.Len(t, artifact.SHA256, 64)
		require.NotContains(t, artifact.DownloadPath, "http")
		sum += artifact.Bytes
	}
	require.Equal(t, manifest.TotalBytes, sum)

	det, ok := Lookup("2026.06", "det.onnx")
	require.True(t, ok)
	require.Equal(t, int64(1780590), det.Bytes)
	require.Contains(t, det.UpstreamURL, "2ba1506c0380b8f0b03dd142459aac66d4421f6c")
	_, ok = Lookup("latest", "det.onnx")
	require.False(t, ok)
}
