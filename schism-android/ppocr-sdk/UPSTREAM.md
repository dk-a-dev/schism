# PaddleOCR Android SDK

This module vendors the official PaddleOCR Android SDK source from:

- Repository: https://github.com/PaddlePaddle/PaddleOCR
- Path: deploy/ppocr-android/ppocr-sdk
- Commit: 2661c7c0ef5c613e8f93c6e93b2e052399f0f854
- License: Apache License 2.0 (see LICENSE)

The bundled models are the official PP-OCRv6_tiny_det_onnx and
PP-OCRv6_tiny_rec_onnx artifacts from the PaddlePaddle Hugging Face organization.

The upstream sample's OpenCV 4.5.3 dependency is replaced with the official OpenCV 4.13.0
Android artifact so its native library works on Android devices using 16 KB memory pages.

SHA-256 checksums:

- Detection ONNX: `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8`
- Recognition ONNX: `9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6`
- Recognition YAML: `66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1`
