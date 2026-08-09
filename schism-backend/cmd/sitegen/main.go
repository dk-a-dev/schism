// Command sitegen renders the marketing and policy pages to static files for hosting on a real
// domain, so the public site is not served from the API hostname.
//
// It drives the same http.Handler the backend serves, rather than re-rendering the templates
// itself: there is exactly one copy of the site's markup and content, and this cannot drift from
// what the backend would have produced.
//
// The dynamic surfaces (invite landings at /i/{token}, /i/g/{token}, the API itself) stay on the
// backend and are deliberately not exported.
package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"

	webui "github.com/schism/schism-backend/internal/web"
)

// Pages become directory indexes (/privacy -> privacy/index.html) so any static host serves the
// same clean URLs the backend does, with no rewrite rules to configure.
var pages = []string{"/", "/privacy", "/terms", "/support", "/account-deletion"}

// vercelConfig ships with the generated output rather than being kept by hand in the output
// directory: regenerating into a clean directory must produce a complete, deployable site, and a
// hand-placed file there is one `rm -rf` away from silently dropping the security headers.
//
// The CSP allows no script at all — the site is CSS-only by contract — and images only from this
// origin, which covers the product screenshots.
const vercelConfig = `{
  "$schema": "https://openapi.vercel.sh/vercel.json",
  "cleanUrls": true,
  "trailingSlash": false,
  "headers": [
    {
      "source": "/(.*)",
      "headers": [
        { "key": "X-Content-Type-Options", "value": "nosniff" },
        { "key": "Referrer-Policy", "value": "no-referrer" },
        {
          "key": "Content-Security-Policy",
          "value": "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        }
      ]
    },
    {
      "source": "/assets/site/(.*)",
      "headers": [
        { "key": "Cache-Control", "value": "public, max-age=31536000, immutable" }
      ]
    }
  ]
}
`

// assetLister is satisfied by the site handler. Asking it for its own routes keeps the
// generated site and the served site in step without a second list to maintain here.
type assetLister interface{ AssetRoutes() []string }

func main() {
	out := flag.String("out", "dist/site", "directory to write the static site into")
	supportEmail := flag.String("support-email", os.Getenv("SCHISM_SUPPORT_EMAIL"), "contact address shown on the pages")
	publicURL := flag.String("public-url", os.Getenv("SCHISM_PUBLIC_URL"), "absolute https base for canonical links")
	playURL := flag.String("play-url", os.Getenv("SCHISM_PLAY_URL"), "Play listing; empty renders the truthful coming-soon state")
	flag.Parse()

	handler, err := webui.New(webui.Config{
		SupportEmail: *supportEmail,
		PublicURL:    *publicURL,
		PlayURL:      *playURL,
	})
	if err != nil {
		log.Fatalf("configure site: %v", err)
	}

	for _, page := range pages {
		dir := filepath.Join(*out, strings.TrimPrefix(page, "/"))
		if err := write(handler, page, filepath.Join(dir, "index.html")); err != nil {
			log.Fatal(err)
		}
	}
	lister, ok := handler.(assetLister)
	if !ok {
		log.Fatal("site handler does not expose its asset routes")
	}
	assets := lister.AssetRoutes()
	for _, asset := range assets {
		if err := write(handler, asset, filepath.Join(*out, filepath.FromSlash(strings.TrimPrefix(asset, "/")))); err != nil {
			log.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(*out, "vercel.json"), []byte(vercelConfig), 0o644); err != nil {
		log.Fatalf("write vercel.json: %v", err)
	}
	fmt.Printf("wrote %d pages, %d assets and vercel.json to %s\n", len(pages), len(assets), *out)
}

func write(handler http.Handler, path, dest string) error {
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	if rec.Code != http.StatusOK {
		return fmt.Errorf("%s returned %d, refusing to write a broken page", path, rec.Code)
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	return os.WriteFile(dest, rec.Body.Bytes(), 0o644)
}
