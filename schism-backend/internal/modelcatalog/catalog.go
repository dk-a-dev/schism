package modelcatalog

type Artifact struct {
	Name         string `json:"name"`
	Bytes        int64  `json:"bytes"`
	SHA256       string `json:"sha256"`
	DownloadPath string `json:"downloadPath"`
	UpstreamURL  string `json:"-"`
}

type OCRManifest struct {
	Version               string     `json:"version"`
	MinimumAppVersionCode int        `json:"minimumAppVersionCode"`
	TotalBytes            int64      `json:"totalBytes"`
	Artifacts             []Artifact `json:"artifacts"`
}

const (
	Version = "2026.06"
	ETag    = `"ocr-2026.06-6298800"`
)

var artifacts = []Artifact{
	{
		Name: "det.onnx", Bytes: 1780590,
		SHA256:       "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8",
		DownloadPath: "/v1/models/ocr/2026.06/det.onnx",
		UpstreamURL:  "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/2ba1506c0380b8f0b03dd142459aac66d4421f6c/inference.onnx",
	},
	{
		Name: "rec.onnx", Bytes: 4462639,
		SHA256:       "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6",
		DownloadPath: "/v1/models/ocr/2026.06/rec.onnx",
		UpstreamURL:  "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/2612ab37152ae0a677521bae4e1e3d4fb4cf7c30/inference.onnx",
	},
	{
		Name: "rec.yml", Bytes: 55571,
		SHA256:       "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1",
		DownloadPath: "/v1/models/ocr/2026.06/rec.yml",
		UpstreamURL:  "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/2612ab37152ae0a677521bae4e1e3d4fb4cf7c30/inference.yml",
	},
}

func OCR() OCRManifest {
	return OCRManifest{
		Version: Version, MinimumAppVersionCode: 10300, TotalBytes: 6298800,
		Artifacts: append([]Artifact(nil), artifacts...),
	}
}

func Lookup(version, name string) (Artifact, bool) {
	if version != Version {
		return Artifact{}, false
	}
	for _, artifact := range artifacts {
		if artifact.Name == name {
			return artifact, true
		}
	}
	return Artifact{}, false
}
