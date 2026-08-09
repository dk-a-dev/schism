package api

import (
	"testing"
	"time"

	"github.com/stretchr/testify/require"
	"golang.org/x/time/rate"
)

func TestRateLimiterRejectsRequestBeyondBurst(t *testing.T) {
	limiter := newKeyedLimiter(rate.Every(time.Hour), 5, 15*time.Minute)
	for i := 0; i < 5; i++ {
		require.True(t, limiter.Allow("127.0.0.1|asha@example.test"))
	}
	require.False(t, limiter.Allow("127.0.0.1|asha@example.test"))
	require.True(t, limiter.Allow("127.0.0.2|asha@example.test"))
}

func TestRateLimiterEvictsIdleKey(t *testing.T) {
	limiter := newKeyedLimiter(rate.Every(time.Hour), 1, 10*time.Millisecond)
	require.True(t, limiter.Allow("idle-key"))
	require.False(t, limiter.Allow("idle-key"))
	time.Sleep(30 * time.Millisecond)
	require.True(t, limiter.Allow("idle-key"))
}
