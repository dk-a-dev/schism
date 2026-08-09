package api

import (
	"fmt"
	"html"
	"net/http"

	"github.com/go-chi/chi/v5"
)

// inviteLanding no longer turns a group id into a data capability. Old links show an upgrade page.
func (h *Handler) inviteLanding(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>New Schism invite needed</title>
<style>
  :root { color-scheme: light dark; }
  body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
         font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
         background:#FBFAF4; color:#1A1A16; }
  @media (prefers-color-scheme: dark){ body{ background:#0F0F0E; color:#ECEBE6; } }
  .card { text-align:center; padding:32px 24px; }
  .logo { width:72px; height:72px; border-radius:50%%; background:#14874F; margin:0 auto 20px; }
  h1 { font-size:22px; margin:0 0 8px; }
  p  { color:#605F58; margin:0 0 24px; }
</style>
</head>
<body>
  <div class="card">
    <div class="logo"></div>
    <h1>This invite has been upgraded</h1>
    <p>Ask a group member to send you a new secure participant invite from Schism.</p>
  </div>
</body>
</html>`)
}

// participantInviteLanding turns a one-time token into an app deep link without exposing group data.
func (h *Handler) participantInviteLanding(w http.ResponseWriter, r *http.Request) {
	deep := "schism://invite/" + html.EscapeString(chi.URLParam(r, "token"))
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="0; url=%s"><title>Join on Schism</title></head>
<body><main><h1>Join your group on Schism</h1><p>This invite links you to your existing participant.</p>
<a href="%s">Open in Schism</a></main><script>location.href=%q;</script></body></html>`, deep, deep, deep)
}

// claimLanding serves the https landing for a claim-session share link (`/c/{sid}`). Like
// inviteLanding it bounces into the app via the `schism://claim/<sid>` deep link with a manual "Open"
// fallback, so a shared claim link opens the claim screen.
func (h *Handler) claimLanding(w http.ResponseWriter, r *http.Request) {
	sid := chi.URLParam(r, "sid")
	deep := "schism://claim/" + html.EscapeString(sid)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="0; url=%s">
<title>Claim your items on Schism</title>
<style>
  :root { color-scheme: light dark; }
  body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
         font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
         background:#FBFAF4; color:#1A1A16; }
  @media (prefers-color-scheme: dark){ body{ background:#0F0F0E; color:#ECEBE6; } }
  .card { text-align:center; padding:32px 24px; }
  .logo { width:72px; height:72px; border-radius:50%%; background:#14874F; margin:0 auto 20px; }
  h1 { font-size:22px; margin:0 0 8px; }
  p  { color:#605F58; margin:0 0 24px; }
  a.btn { display:inline-block; background:#14874F; color:#fff; text-decoration:none;
          padding:14px 28px; border-radius:100px; font-weight:600; }
</style>
</head>
<body>
  <div class="card">
    <div class="logo"></div>
    <h1>Claim your items in Schism</h1>
    <p>If nothing happens, tap the button below.</p>
    <a class="btn" href="%s">Open in Schism</a>
  </div>
  <script>location.href=%q;</script>
</body>
</html>`, deep, deep, deep)
}
