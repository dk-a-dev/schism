package web

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

// The legal pages shipped for months carrying sentences like "These terms govern your use of
// Schism. They remain subject to owner and legal approval before public launch." — published, on
// the live site, on the very URL a Play reviewer opens from the listing. Nothing failed, because
// nothing was looking.
//
// These phrases are all placeholder-speak: text addressed to us rather than to the reader. If a
// clause genuinely is not ready, the honest move is to leave the clause out, not to publish a note
// about it.
var placeholderPhrases = []string{
	"before launch",
	"before public launch",
	"subject to owner",
	"legal approval",
	"legal reviewer",
	"must be supplied",
	"to be determined",
	"tbd",
	"lorem ipsum",
	"placeholder",
}

func TestLegalPagesCarryNoPlaceholderLanguage(t *testing.T) {
	h := newTestSite(t, "")
	for _, path := range []string{"/privacy", "/terms", "/account-deletion", "/support", "/"} {
		body := strings.ToLower(get(t, h, path).Body.String())
		for _, phrase := range placeholderPhrases {
			require.NotContainsf(t, body, phrase, "%s still contains placeholder language %q", path, phrase)
		}
	}
}

// The Terms name a court. Rendering that page with the venue blank would publish "The courts of
// have exclusive jurisdiction", so the venue is required configuration rather than a defaulted
// empty string.
func TestSiteRequiresLegalVenueCity(t *testing.T) {
	_, err := New(Config{
		SupportEmail: "support@schism.test",
		PublicURL:    "https://schism.test",
	})
	require.Error(t, err)
	require.Contains(t, err.Error(), "SCHISM_LEGAL_VENUE_CITY")
}

func TestTermsRenderTheConfiguredVenue(t *testing.T) {
	body := get(t, newTestSite(t, ""), "/terms").Body.String()
	require.Contains(t, body, "courts of Testville")
	// The substantive clauses a reviewer looks for are present, not merely non-placeholder.
	require.Contains(t, strings.ToLower(body), "as is")
	require.Contains(t, strings.ToLower(body), "limitation of liability")
	require.Contains(t, strings.ToLower(body), "governed by the laws of india")
}
