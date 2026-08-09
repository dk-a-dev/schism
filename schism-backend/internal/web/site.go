package web

import (
	"crypto/sha256"
	"embed"
	"fmt"
	"html/template"
	"io/fs"
	"net/http"
	"net/mail"
	"net/url"
	"path"
	"sort"
	"strings"
)

//go:embed templates/*.html static/*
var content embed.FS

type Config struct {
	SupportEmail string
	PublicURL    string
	PlayURL      string
	// LegalVenueCity names the city whose courts have jurisdiction under the Terms. It has no
	// default and startup fails without it, for the same reason SupportEmail does: a legal page
	// that ships with the venue blank is worse than one that never ships.
	LegalVenueCity string
}

type site struct {
	templates *template.Template
	config    Config
	assets    map[string]asset
}

type asset struct {
	body        []byte
	contentType string
	etag        string
}

type pageData struct {
	Title          string
	Description    string
	CanonicalURL   string
	SupportEmail   string
	SupportHref    string
	PlayURL        string
	LegalVenueCity string
}

func New(config Config) (http.Handler, error) {
	config.SupportEmail = strings.TrimSpace(config.SupportEmail)
	if err := validateSupportEmail(config.SupportEmail); err != nil {
		return nil, err
	}
	config.LegalVenueCity = strings.TrimSpace(config.LegalVenueCity)
	if config.LegalVenueCity == "" {
		return nil, fmt.Errorf("legal venue city is required: set SCHISM_LEGAL_VENUE_CITY to the city whose courts have jurisdiction")
	}
	var err error
	if config.PublicURL, err = validateHTTPSURL("public URL", config.PublicURL, false); err != nil {
		return nil, err
	}
	if config.PlayURL, err = validateHTTPSURL("Play URL", config.PlayURL, true); err != nil {
		return nil, err
	}

	templates, err := template.ParseFS(content, "templates/*.html")
	if err != nil {
		return nil, fmt.Errorf("parse site templates: %w", err)
	}
	embedded := map[string]struct {
		path        string
		contentType string
	}{
		"/assets/site/site.css":       {path: "static/site.css", contentType: "text/css; charset=utf-8"},
		"/assets/site/split-coin.svg": {path: "static/split-coin.svg", contentType: "image/svg+xml"},
	}
	// Product screenshots ship as a directory so adding or replacing a capture is a file
	// change, not a code change.
	shots, err := fs.Glob(content, "static/shots/*.png")
	if err != nil {
		return nil, fmt.Errorf("list embedded screenshots: %w", err)
	}
	for _, shot := range shots {
		embedded["/assets/site/shots/"+path.Base(shot)] = struct {
			path        string
			contentType string
		}{path: shot, contentType: "image/png"}
	}

	assets := make(map[string]asset, len(embedded))
	for route, e := range embedded {
		body, readErr := fs.ReadFile(content, e.path)
		if readErr != nil {
			return nil, fmt.Errorf("read embedded asset %q: %w", e.path, readErr)
		}
		digest := sha256.Sum256(body)
		assets[route] = asset{
			body:        body,
			contentType: e.contentType,
			etag:        fmt.Sprintf(`"%x"`, digest[:16]),
		}
	}

	return &site{templates: templates, config: config, assets: assets}, nil
}

// AssetRoutes lists every static asset route this handler serves, sorted. The static-site
// generator asks the handler rather than keeping its own list, so a newly added screenshot
// cannot be served by the backend and silently missing from the generated site.
func (s *site) AssetRoutes() []string {
	routes := make([]string, 0, len(s.assets))
	for route := range s.assets {
		routes = append(routes, route)
	}
	sort.Strings(routes)
	return routes
}

func (s *site) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	siteHeaders(w.Header())
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, http.StatusText(http.StatusMethodNotAllowed), http.StatusMethodNotAllowed)
		return
	}
	if a, ok := s.assets[r.URL.Path]; ok {
		s.serveAsset(w, r, a)
		return
	}

	var name, title, description string
	switch r.URL.Path {
	case "/":
		name = "home"
		title = "Schism — Split expenses. Keep the context."
		description = "Scan and review bills, split shared expenses, and keep the context with Schism."
	case "/privacy":
		name = "privacy"
		title = "Privacy — Schism"
		description = "How Schism handles account, group, receipt, SMS, purchase, and advertising data."
	case "/terms":
		name = "terms"
		title = "Terms — Schism"
		description = "Terms for using Schism."
	case "/support":
		name = "support"
		title = "Support — Schism"
		description = "Get help with Schism."
	case "/account-deletion":
		name = "account-deletion"
		title = "Account deletion — Schism"
		description = "Delete a Schism account or request deletion after uninstalling the app."
	default:
		http.NotFound(w, r)
		return
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return
	}
	data := pageData{
		Title:          title,
		Description:    description,
		CanonicalURL:   s.canonical(r.URL.Path),
		SupportEmail:   s.config.SupportEmail,
		SupportHref:    "mailto:" + s.config.SupportEmail,
		PlayURL:        s.config.PlayURL,
		LegalVenueCity: s.config.LegalVenueCity,
	}
	if err := s.templates.ExecuteTemplate(w, name, data); err != nil {
		// Templates are parsed at startup and execute only against strings, so this is defensive.
		http.Error(w, http.StatusText(http.StatusInternalServerError), http.StatusInternalServerError)
	}
}

func (s *site) serveAsset(w http.ResponseWriter, r *http.Request, a asset) {
	w.Header().Set("Content-Type", a.contentType)
	w.Header().Set("Cache-Control", "public, max-age=3600, must-revalidate")
	w.Header().Set("ETag", a.etag)
	if r.Header.Get("If-None-Match") == a.etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(a.body)
}

func (s *site) canonical(path string) string {
	if s.config.PublicURL == "" {
		return ""
	}
	if path == "/" {
		return s.config.PublicURL
	}
	return strings.TrimRight(s.config.PublicURL, "/") + path
}

func siteHeaders(header http.Header) {
	header.Set("X-Content-Type-Options", "nosniff")
	header.Set("Referrer-Policy", "no-referrer")
	header.Set("Content-Security-Policy", "default-src 'self'; img-src 'self'; style-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'")
	header.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")
}

func validateSupportEmail(value string) error {
	if value == "" {
		return fmt.Errorf("support email is required")
	}
	address, err := mail.ParseAddress(value)
	if err != nil || address.Address != value || strings.ContainsAny(value, "\r\n") {
		return fmt.Errorf("support email must be a valid bare email address")
	}
	return nil
}

func validateHTTPSURL(label, value string, allowQuery bool) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", nil
	}
	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil || parsed.Fragment != "" {
		return "", fmt.Errorf("%s must be an absolute HTTPS URL without userinfo or fragment", label)
	}
	if !allowQuery && (parsed.RawQuery != "" || parsed.ForceQuery) {
		return "", fmt.Errorf("%s must not contain a query", label)
	}
	return parsed.String(), nil
}
