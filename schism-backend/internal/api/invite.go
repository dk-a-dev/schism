package api

import (
	"fmt"
	"html"
	"net/http"

	"github.com/go-chi/chi/v5"
)

// inviteLanding serves a tiny HTML page for an https invite link (`/g/{groupID}`). Messengers such
// as WhatsApp only linkify http(s) URLs, so shares point here; the page immediately bounces into the
// app via the `schism://group/<id>` deep link and offers a manual "Open" button as a fallback.
func (h *Handler) inviteLanding(w http.ResponseWriter, r *http.Request) {
	gid := chi.URLParam(r, "groupID")
	deep := "schism://group/" + html.EscapeString(gid)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="0; url=%s">
<title>Join on Schism</title>
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
    <h1>Open this group in Schism</h1>
    <p>If nothing happens, tap the button below.</p>
    <a class="btn" href="%s">Open in Schism</a>
  </div>
  <script>location.href=%q;</script>
</body>
</html>`, deep, deep, deep)
}
