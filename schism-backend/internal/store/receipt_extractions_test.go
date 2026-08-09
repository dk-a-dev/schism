package store

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/schism/schism-backend/internal/id"
	"github.com/stretchr/testify/require"
)

// The boundary is the whole point: a second call inside the hour is refused, and one just past it
// is granted. The window is walked with an explicit clock so the test costs no wall time.
func TestClaimReceiptExtractionWindowBoundary(t *testing.T) {
	s, u, in := allowanceFixture(t)
	ctx := context.Background()
	start := time.Date(2026, 3, 5, 12, 0, 0, 0, time.UTC)

	cases := []struct {
		name        string
		at          time.Time
		wantGranted bool
	}{
		{"first call", start, true},
		{"immediately after", start, false},
		{"one minute later", start.Add(time.Minute), false},
		{"one second before the window closes", start.Add(59*time.Minute + 59*time.Second), false},
		{"exactly one hour later", start.Add(time.Hour), true},
		{"straight after the granted retry", start.Add(time.Hour), false},
		{"61 minutes after the first call", start.Add(61 * time.Minute), false},
		{"61 minutes after the second", start.Add(2*time.Hour + time.Minute), true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			nextAt, granted, err := s.ClaimReceiptExtraction(ctx, u.ID, in.GroupID, tc.at)
			require.NoError(t, err)
			require.Equal(t, tc.wantGranted, granted)
			if granted {
				require.True(t, nextAt.IsZero())
				return
			}
			// A refusal must say when the slot frees up, and that must be in the future.
			require.False(t, nextAt.IsZero())
			require.True(t, nextAt.After(tc.at), "nextAt %s should be after %s", nextAt, tc.at)
			require.LessOrEqual(t, nextAt.Sub(tc.at), ReceiptExtractWindow)
		})
	}
}

// The limit is per (user, group): a different group, or a different user, is unaffected.
func TestClaimReceiptExtractionIsScopedToUserAndGroup(t *testing.T) {
	s, u, in := allowanceFixture(t)
	ctx := context.Background()
	now := time.Now().UTC()

	_, granted, err := s.ClaimReceiptExtraction(ctx, u.ID, in.GroupID, now)
	require.NoError(t, err)
	require.True(t, granted)

	other, err := s.CreateGroupForUser(ctx, GroupInput{
		Name: "Other", Currency: "₹",
		Participants: []ParticipantInput{{Name: "Host", UserID: &u.ID}},
	}, u.ID)
	require.NoError(t, err)
	_, granted, err = s.ClaimReceiptExtraction(ctx, u.ID, other.ID, now)
	require.NoError(t, err)
	require.True(t, granted, "a different group has its own hourly slot")

	second, _, err := s.RegisterUser(ctx, "Guest", "guest-"+id.New()+"@example.com", "password1", "")
	require.NoError(t, err)
	_, granted, err = s.ClaimReceiptExtraction(ctx, second.ID, in.GroupID, now)
	require.NoError(t, err)
	require.True(t, granted, "a different user has their own hourly slot")
}

// Concurrent claims must grant exactly one — this is why the limiter lives in Postgres and not in
// process memory, where N replicas would grant N.
func TestClaimReceiptExtractionGrantsOnlyOneUnderRace(t *testing.T) {
	s, u, in := allowanceFixture(t)
	now := time.Now().UTC()

	const racers = 8
	var wg sync.WaitGroup
	results := make([]bool, racers)
	errs := make([]error, racers)
	wg.Add(racers)
	for i := range racers {
		go func() {
			defer wg.Done()
			_, granted, err := s.ClaimReceiptExtraction(context.Background(), u.ID, in.GroupID, now)
			results[i], errs[i] = granted, err
		}()
	}
	wg.Wait()

	wins := 0
	for i := range racers {
		require.NoError(t, errs[i])
		if results[i] {
			wins++
		}
	}
	require.Equal(t, 1, wins, "exactly one concurrent claim may win")
}
