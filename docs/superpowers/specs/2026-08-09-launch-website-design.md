# Schism Launch Website Design

**Status:** Approved for implementation under the owner's auto-approval instruction  
**Date:** 2026-08-09

## Purpose

Ship one small public page that explains Schism clearly, earns trust, sends Android visitors to the
Play listing when it exists, and hosts the policy/support routes required for launch. It must deploy
with the existing Go backend and add no separate hosting, CMS, analytics, cookie banner, or JavaScript
framework.

## Audience and Message

The page is for people who regularly share expenses with friends, flatmates, partners, or travel
groups. The central promise is: `Split expenses. Keep the context.` Supporting claims stay factual:
scan and review bills, understand supported bank messages on-device after explicit opt-in, assign
items, see balances, and use Live Split. The page must not promise perfect OCR, universal bank
support, automatic debt settlement, or bank affiliation.

## Experience

The page uses Schism's quiet-luxury paper-ledger language: cream ground, charcoal type, emerald
actions, mint panels, restrained terracotta/amber accents, subtle rules, and the existing split-coin
seam. No neon finance gradients, stock-photo teams, fake phone screens, floating glass cards, or
fabricated reviews.

The responsive page contains:

1. A compact header with the split-coin mark, Schism name, Privacy, Support, and primary CTA.
2. A hero with the launch promise, concise supporting copy, one CTA, and a code-native ledger/receipt
   composition that remains useful before store screenshots exist.
3. A three-part `Capture → Review → Split` explanation.
4. A privacy section stating that receipt OCR and opted-in SMS understanding happen on-device, while
   account and shared group data use the Schism service.
5. A Live Split section that explains collaborative item claiming without overstating availability.
6. A real-product gallery populated only with verified synthetic-fixture screenshots.
7. A final CTA and footer links to Privacy, Terms, Support, and Account deletion.

If `SCHISM_PLAY_URL` is absent, buttons render as a non-link `Coming soon on Google Play` state. A
production support email remains required; no example contact or broken Play URL is shipped.

## Technical Shape

An `internal/web` package embeds semantic HTML templates and static CSS/SVG assets with `go:embed`.
The API router mounts `/`, `/assets/site/*`, `/privacy`, `/terms`, `/support`, and
`/account-deletion`. Static files use content-type allowlisting, immutable caching only for
fingerprinted assets, ETags, and the existing security headers. HTML has a strict CSP and no inline
script. The page is usable without images, honors reduced motion, has visible focus, uses logical CSS
properties for RTL resilience, and maintains WCAG AA contrast.

Search/social metadata is factual and includes canonical URL only when `SCHISM_PUBLIC_URL` is
configured. Open Graph artwork comes from the validated launch asset pipeline. No visitor identifier,
tracking pixel, analytics request, or marketing cookie is added for v1.3.

## Verification

Go tests cover routes, headers, escaping, optional/valid CTA state, metadata, footer links, and
embedded asset integrity. Browser verification covers 360 px mobile, Pixel viewport, tablet, and
desktop; keyboard navigation, reduced motion, large text, dark-system preference, and image-disabled
rendering. Lighthouse targets are Performance ≥95, Accessibility ≥95, Best Practices ≥95, and SEO
≥95 against a release build on localhost. Legal wording remains subject to owner/legal review.

