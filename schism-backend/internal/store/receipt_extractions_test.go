package store

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// Two calls are allowed inside the window, the third is refused, and the window rolls over on the
// hour. The clock is explicit so the test costs no wall time.
func TestClaimReceiptExtractionWindowBoundary(t *testing.T) {
	s, u, _ := allowanceFixture(t)
	ctx := context.Background()
	start := time.Date(2026, 3, 5, 12, 0, 0, 0, time.UTC)

	cases := []struct {
		name        string
		at          time.Time
		wantGranted bool
	}{
		{"first call opens the window", start, true},
		{"second call spends the last slot", start.Add(time.Minute), true},
		{"third call inside the window is refused", start.Add(2 * time.Minute), false},
		{"still refused just before the window rolls", start.Add(ReceiptExtractWindow - time.Second), false},
		{"granted once the window has rolled over", start.Add(ReceiptExtractWindow + time.Second), true},
		{"and the new window has its own second slot", start.Add(ReceiptExtractWindow + 2*time.Second), true},
		{"but not a third", start.Add(ReceiptExtractWindow + 3*time.Second), false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			nextAt, granted, err := s.ClaimReceiptExtraction(ctx, u.ID, tc.at)
			require.NoError(t, err)
			require.Equal(t, tc.wantGranted, granted)
			if !granted {
				require.True(t, nextAt.After(tc.at), "a refusal must say when the window rolls over")
			}
		})
	}
}

// The limit is per user and deliberately NOT per group. Scoping it per (user, group) bounded
// nothing, because creating a group is unlimited: an account could loop create-group -> extract and
// spend our provider keys without a ceiling. This test is the regression guard for that bypass.
func TestClaimReceiptExtractionIsPerUserNotPerGroup(t *testing.T) {
	s, u, _ := allowanceFixture(t)
	ctx := context.Background()
	now := time.Date(2026, 3, 5, 12, 0, 0, 0, time.UTC)

	for i := 0; i < ReceiptExtractLimit; i++ {
		_, granted, err := s.ClaimReceiptExtraction(ctx, u.ID, now)
		require.NoError(t, err)
		require.True(t, granted)
	}

	// Spinning up brand-new groups must not buy more calls.
	for i := 0; i < 3; i++ {
		_, err := s.CreateGroup(ctx, GroupInput{
			Name: "Fresh", Currency: "$", Participants: []ParticipantInput{{Name: "A"}},
		})
		require.NoError(t, err)
		_, granted, err := s.ClaimReceiptExtraction(ctx, u.ID, now)
		require.NoError(t, err)
		require.False(t, granted, "a new group must not reset the user's allowance")
	}

	// A different user has their own allowance.
	second, _, err := s.CreateUser(ctx, "Second", "second@example.test", "")
	require.NoError(t, err)
	_, granted, err := s.ClaimReceiptExtraction(ctx, second.ID, now)
	require.NoError(t, err)
	require.True(t, granted, "the limit must be per user, not global")
}

// Replicas racing the same user must not both be granted: the limit is enforced in Postgres
// precisely because an in-memory limiter would grant the allowance once per instance.
func TestClaimReceiptExtractionGrantsExactlyTheLimitUnderRace(t *testing.T) {
	s, u, _ := allowanceFixture(t)
	now := time.Date(2026, 3, 5, 12, 0, 0, 0, time.UTC)

	const racers = 8
	var wg sync.WaitGroup
	results := make([]bool, racers)
	for i := 0; i < racers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, granted, err := s.ClaimReceiptExtraction(context.Background(), u.ID, now)
			if err == nil {
				results[i] = granted
			}
		}(i)
	}
	wg.Wait()

	got := 0
	for _, granted := range results {
		if granted {
			got++
		}
	}
	require.Equal(t, ReceiptExtractLimit, got, "exactly the allowance may be granted, however many racers")
}
