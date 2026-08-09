package api

import (
	"crypto/sha256"
	"encoding/hex"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

const maxLimiterEntries = 10_000

type limiterEntry struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

type keyedLimiter struct {
	mu      sync.Mutex
	limit   rate.Limit
	burst   int
	ttl     time.Duration
	entries map[string]*limiterEntry
}

func newKeyedLimiter(limit rate.Limit, burst int, ttl time.Duration) *keyedLimiter {
	return &keyedLimiter{limit: limit, burst: burst, ttl: ttl, entries: make(map[string]*limiterEntry)}
}

func (l *keyedLimiter) Allow(key string) bool {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()

	if entry := l.entries[key]; entry != nil {
		if now.Sub(entry.lastSeen) <= l.ttl {
			entry.lastSeen = now
			return entry.limiter.Allow()
		}
		delete(l.entries, key)
	}

	if len(l.entries) >= maxLimiterEntries {
		l.evictOldest()
	}
	entry := &limiterEntry{limiter: rate.NewLimiter(l.limit, l.burst), lastSeen: now}
	l.entries[key] = entry
	return entry.limiter.Allow()
}

func (l *keyedLimiter) evictOldest() {
	var oldestKey string
	var oldestTime time.Time
	for key, entry := range l.entries {
		if oldestKey == "" || entry.lastSeen.Before(oldestTime) {
			oldestKey = key
			oldestTime = entry.lastSeen
		}
	}
	delete(l.entries, oldestKey)
}

func clientIP(r *http.Request) string {
	if raw := strings.TrimSpace(r.Header.Get("X-Envoy-External-Address")); raw != "" {
		if addr, err := netip.ParseAddr(raw); err == nil {
			return addr.String()
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	if addr, err := netip.ParseAddr(r.RemoteAddr); err == nil {
		return addr.String()
	}
	return "unknown"
}

func limiterKey(parts ...string) string {
	sum := sha256.Sum256([]byte(strings.Join(parts, "\x00")))
	return hex.EncodeToString(sum[:])
}

func writeRateLimited(w http.ResponseWriter) {
	w.Header().Set("Retry-After", "60")
	writeErr(w, http.StatusTooManyRequests, "rate_limited")
}
