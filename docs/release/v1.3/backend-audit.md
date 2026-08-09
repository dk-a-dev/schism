# Schism v1.3 Backend Audit

Audit date: 2026-08-09

## Resolved findings

| Area | Resolution | Evidence |
|---|---|---|
| HTTP boundary | 1 MiB JSON limit, content-type/unknown-field/single-document validation, security headers, request IDs, sanitized panic/internal errors, server timeouts and graceful shutdown | `internal/api/request_test.go`, `security_test.go`, `cmd/server/main_test.go` |
| Authentication | 90-day hash-only sessions, hourly activity touch, current-session logout, normalized/validated identities, keyed register/login throttles | migration `0011`, auth and limiter suites |
| Authorization | Required session and linked participant on every group resource; requested group IDs intersect membership; actors are server-derived | table-driven authorization matrix plus cross-group participant rejection |
| Invitations | Seven-day random tokens stored only as SHA-256, bound to an existing unlinked participant, transactional one-time redemption | migration `0012`, store concurrency test, API lifecycle test |
| OCR delivery | Compile-time allowlist, revision-pinned official upstreams, stable ETag, immutable versioned redirects, exact byte counts and SHA-256 | model catalog tests and local asset verification |
| Database pool | Bounded configurable pool (`20/2/30m` defaults), five-second startup ping, migration handles closed | config and pool tests |
| Load boundary | 500 concurrent public manifest requests complete with response bodies closed | `TestConcurrentOCRManifestLoad` |

## Verified model payload

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| detector ONNX | 1,780,590 | `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8` |
| recognizer ONNX | 4,462,639 | `9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6` |
| recognizer YAML | 55,571 | `66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1` |
| Total | 6,298,800 | — |

## Release verification

Environment: Go `1.26.2` darwin/arm64 and PostgreSQL `16.14`.

```text
go test ./... -count=1
go test -race -p 1 ./...
go vet ./...
staticcheck ./...
docker build -t schism-backend:v1.3-rc .
```

Results on 2026-08-09:

- `go test ./... -count=1`: pass.
- `go test -race -p 1 ./...`: pass.
- `go vet ./...`: pass.
- `go run honnef.co/go/tools/cmd/staticcheck@v0.7.0 ./...`: pass. (The globally installed
  Staticcheck was built with Go 1.25 and cannot analyze a Go 1.26 module.)
- Docker image build: environment-blocked; Docker 29.4.0 is installed but the configured OrbStack
  daemon socket does not exist. The multi-stage distroless Dockerfile was inspected but not built.

`-p 1` keeps the race suite within a 100-connection local PostgreSQL test ceiling; production pool
limits are independent.
