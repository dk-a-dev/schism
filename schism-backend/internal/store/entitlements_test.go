package store

import (
	"bytes"
	"context"
	"sync"
	"testing"
	"time"

	"github.com/schism/schism-backend/internal/id"
	"github.com/stretchr/testify/require"
)

var testBillingKey = bytes.Repeat([]byte{9}, 32)

// allowanceFixture returns a store, a registered user, and a claim-session input pointed at a group
// that user hosts.
func allowanceFixture(t *testing.T) (*Store, User, ClaimSessionInput) {
	t.Helper()
	s := newTestStore(t)
	ctx := context.Background()
	u, _, err := s.RegisterUser(ctx, "Host", "host-"+id.New()+"@example.com", "password1", "")
	require.NoError(t, err)
	g, err := s.CreateGroupForUser(ctx, GroupInput{
		Name: "Trip", Currency: "₹",
		Participants: []ParticipantInput{{Name: "Host", UserID: &u.ID}, {Name: "Guest"}},
	}, u.ID)
	require.NoError(t, err)
	return s, u, ClaimSessionInput{
		GroupID: g.ID, CreatorParticipantID: g.Participants[0].ID, Title: "Dinner", Currency: "₹",
		Items: []ClaimItem{{Idx: 0, Name: "Dish", Qty: 1, AmountMinor: 10000}},
	}
}

// grantPlus records a verified purchase for userID expiring at expiresAt.
func grantPlus(t *testing.T, s *Store, userID, state string, expiresAt time.Time) {
	t.Helper()
	_, err := s.UpsertPurchase(context.Background(), testBillingKey, userID, "play-token-"+id.New(), PurchaseRecord{
		ProductID: "schism_plus", State: state, ExpiresAt: expiresAt, AutoRenewing: true,
	})
	require.NoError(t, err)
}

func TestEntitlementStatusFollowsStateAndExpiry(t *testing.T) {
	s, u, _ := allowanceFixture(t)
	ctx := context.Background()
	now := time.Now().UTC()

	e, err := s.EntitlementStatus(ctx, u.ID, now)
	require.NoError(t, err)
	require.False(t, e.Active, "a fresh account is not Plus")

	grantPlus(t, s, u.ID, "PURCHASED", now.Add(30*24*time.Hour))
	e, err = s.EntitlementStatus(ctx, u.ID, now)
	require.NoError(t, err)
	require.True(t, e.Active)
	require.Equal(t, "schism_plus", e.ProductID)
	require.True(t, e.AutoRenewing)

	// Expired: same record, evaluated after its expiry.
	e, err = s.EntitlementStatus(ctx, u.ID, now.Add(31*24*time.Hour))
	require.NoError(t, err)
	require.False(t, e.Active)

	// Refunded: never active, even inside the paid window.
	refunded, _, err := s.RegisterUser(ctx, "Ref", "ref-"+id.New()+"@example.com", "password1", "")
	require.NoError(t, err)
	grantPlus(t, s, refunded.ID, "REFUNDED", now.Add(30*24*time.Hour))
	e, err = s.EntitlementStatus(ctx, refunded.ID, now)
	require.NoError(t, err)
	require.False(t, e.Active)
}

// TestEntitlementCancelledButActiveUntilExpiry proves a cancelled subscription keeps Plus until the
// paid period ends: auto-renew is off, entitlement is still on.
func TestEntitlementCancelledButActiveUntilExpiry(t *testing.T) {
	s, u, _ := allowanceFixture(t)
	ctx := context.Background()
	now := time.Now().UTC()
	_, err := s.UpsertPurchase(ctx, testBillingKey, u.ID, "play-token-"+id.New(), PurchaseRecord{
		ProductID: "schism_plus", State: "PURCHASED", ExpiresAt: now.Add(5 * 24 * time.Hour),
		AutoRenewing: false,
	})
	require.NoError(t, err)

	e, err := s.EntitlementStatus(ctx, u.ID, now)
	require.NoError(t, err)
	require.True(t, e.Active)
	require.False(t, e.AutoRenewing)
}

func TestPurchaseTokenStoredEncryptedAndNotRecoverableWithoutKey(t *testing.T) {
	s, u, _ := allowanceFixture(t)
	ctx := context.Background()
	token := "play-token-secret-" + id.New()
	rec, err := s.UpsertPurchase(ctx, testBillingKey, u.ID, token, PurchaseRecord{
		ProductID: "schism_plus", State: "PURCHASED", ExpiresAt: time.Now().UTC().Add(time.Hour),
	})
	require.NoError(t, err)

	var ciphertext []byte
	var hash string
	require.NoError(t, s.pool.QueryRow(ctx,
		`SELECT token_ciphertext, token_hash FROM purchases WHERE id=$1`, rec.ID).Scan(&ciphertext, &hash))
	require.NotContains(t, string(ciphertext), token, "ciphertext must not contain the raw token")
	require.NotEqual(t, token, hash)
	require.Equal(t, TokenHash(token), hash)

	plain, err := openToken(testBillingKey, ciphertext)
	require.NoError(t, err)
	require.Equal(t, token, plain)

	// A different key must not decrypt it, and a wrong-length key is rejected outright.
	_, err = openToken(bytes.Repeat([]byte{1}, 32), ciphertext)
	require.Error(t, err)
	_, err = sealToken(bytes.Repeat([]byte{1}, 16), token)
	require.ErrorIs(t, err, ErrBillingKeyMissing)
	_, err = s.UpsertPurchase(ctx, nil, u.ID, token, PurchaseRecord{ProductID: "schism_plus"})
	require.ErrorIs(t, err, ErrBillingKeyMissing)
}

// TestPurchaseReplayToAnotherUserIsRejected: the same Play token must never entitle two accounts.
func TestPurchaseReplayToAnotherUserIsRejected(t *testing.T) {
	s, u, _ := allowanceFixture(t)
	ctx := context.Background()
	other, _, err := s.RegisterUser(ctx, "Other", "other-"+id.New()+"@example.com", "password1", "")
	require.NoError(t, err)
	token := "play-token-" + id.New()
	in := PurchaseRecord{ProductID: "schism_plus", State: "PURCHASED", ExpiresAt: time.Now().UTC().Add(time.Hour)}

	_, err = s.UpsertPurchase(ctx, testBillingKey, u.ID, token, in)
	require.NoError(t, err)
	_, err = s.UpsertPurchase(ctx, testBillingKey, other.ID, token, in)
	require.ErrorIs(t, err, ErrPurchaseOwnedByAnotherUser)

	// The original owner may refresh the same token as often as they like.
	_, err = s.UpsertPurchase(ctx, testBillingKey, u.ID, token, in)
	require.NoError(t, err)
	e, err := s.EntitlementStatus(ctx, other.ID, time.Now().UTC())
	require.NoError(t, err)
	require.False(t, e.Active)
}

func TestStalePurchasesReturnsOnlyRefreshableRecords(t *testing.T) {
	s, u, _ := allowanceFixture(t)
	ctx := context.Background()
	now := time.Now().UTC()
	token := "play-token-" + id.New()
	rec, err := s.UpsertPurchase(ctx, testBillingKey, u.ID, token, PurchaseRecord{
		ProductID: "schism_plus", State: "PURCHASED", ExpiresAt: now.Add(24 * time.Hour),
	})
	require.NoError(t, err)

	fresh, _, err := s.StalePurchases(ctx, testBillingKey, u.ID, now, 6*time.Hour)
	require.NoError(t, err)
	require.Empty(t, fresh, "a just-verified, unexpired record is not stale")

	// Past the refresh interval it becomes stale, and the token comes back decrypted.
	stale, tokens, err := s.StalePurchases(ctx, testBillingKey, u.ID, now.Add(7*time.Hour), 6*time.Hour)
	require.NoError(t, err)
	require.Len(t, stale, 1)
	require.Equal(t, rec.ID, stale[0].ID)
	require.Equal(t, []string{token}, tokens)

	// So does a record past its expiry, whatever the interval.
	atExpiry, _, err := s.StalePurchases(ctx, testBillingKey, u.ID, now.Add(25*time.Hour), 365*24*time.Hour)
	require.NoError(t, err)
	require.Len(t, atExpiry, 1)

	// A negative interval is the explicit "restore" sweep: everything, however fresh.
	all, _, err := s.StalePurchases(ctx, testBillingKey, u.ID, now, -time.Minute)
	require.NoError(t, err)
	require.Len(t, all, 1)

	// A terminal record is never re-checked.
	_, err = s.UpsertPurchase(ctx, testBillingKey, u.ID, token, PurchaseRecord{
		ProductID: "schism_plus", State: "REFUNDED", ExpiresAt: now.Add(24 * time.Hour),
	})
	require.NoError(t, err)
	terminal, _, err := s.StalePurchases(ctx, testBillingKey, u.ID, now, -time.Minute)
	require.NoError(t, err)
	require.Empty(t, terminal)
}

func TestConsumeAllowanceGivesExactlyThreeFreeLiveSplits(t *testing.T) {
	s, u, in := allowanceFixture(t)
	ctx := context.Background()
	now := time.Now().UTC()

	for i := 0; i < FreeLiveSplitsPerMonth; i++ {
		cs, plus, err := s.CreateClaimSessionGated(ctx, in, u.ID, id.New(), now)
		require.NoError(t, err)
		require.Nil(t, plus, "free split %d must be granted", i+1)
		require.NotNil(t, cs)
	}

	cs, plus, err := s.CreateClaimSessionGated(ctx, in, u.ID, id.New(), now)
	require.NoError(t, err)
	require.Nil(t, cs, "a refused create must not leave a session behind")
	require.NotNil(t, plus)
	require.Equal(t, 3, plus.Used)
	require.Equal(t, 3, plus.Limit)
	require.Equal(t, monthStart(now).AddDate(0, 1, 0), plus.ResetsAt)

	usage, err := s.LiveSplitUsage(ctx, u.ID, now)
	require.NoError(t, err)
	require.Equal(t, 3, usage.Used)
}

func TestConsumeAllowanceIsIdempotentPerKey(t *testing.T) {
	s, u, in := allowanceFixture(t)
	ctx := context.Background()
	now := time.Now().UTC()
	key := "client-key-" + id.New()

	first, plus, err := s.CreateClaimSessionGated(ctx, in, u.ID, key, now)
	require.NoError(t, err)
	require.Nil(t, plus)

	// A retry with the same key returns the SAME session and burns no second allowance.
	for i := 0; i < 3; i++ {
		again, plus, err := s.CreateClaimSessionGated(ctx, in, u.ID, key, now)
		require.NoError(t, err)
		require.Nil(t, plus)
		require.Equal(t, first.ID, again.ID)
	}
	usage, err := s.LiveSplitUsage(ctx, u.ID, now)
	require.NoError(t, err)
	require.Equal(t, 1, usage.Used)
}

func TestAllowanceResetsOnUTCMonthRollover(t *testing.T) {
	s, u, in := allowanceFixture(t)
	ctx := context.Background()
	// A month with a rollover an hour away in UTC.
	end := time.Date(2026, time.March, 31, 23, 0, 0, 0, time.UTC)
	for i := 0; i < FreeLiveSplitsPerMonth; i++ {
		_, plus, err := s.CreateClaimSessionGated(ctx, in, u.ID, id.New(), end)
		require.NoError(t, err)
		require.Nil(t, plus)
	}
	_, plus, err := s.CreateClaimSessionGated(ctx, in, u.ID, id.New(), end)
	require.NoError(t, err)
	require.NotNil(t, plus, "March is exhausted")

	next := time.Date(2026, time.April, 1, 0, 30, 0, 0, time.UTC)
	cs, plus, err := s.CreateClaimSessionGated(ctx, in, u.ID, id.New(), next)
	require.NoError(t, err)
	require.Nil(t, plus, "April starts a fresh allowance")
	require.NotNil(t, cs)

	// March's counter is untouched by April's consumption.
	march, err := s.LiveSplitUsage(ctx, u.ID, end)
	require.NoError(t, err)
	require.Equal(t, 3, march.Used)
	april, err := s.LiveSplitUsage(ctx, u.ID, next)
	require.NoError(t, err)
	require.Equal(t, 1, april.Used)
}

func TestPlusAccountBypassesAllowance(t *testing.T) {
	s, u, in := allowanceFixture(t)
	ctx := context.Background()
	now := time.Now().UTC()
	grantPlus(t, s, u.ID, "PURCHASED", now.Add(30*24*time.Hour))

	for i := 0; i < 10; i++ {
		cs, plus, err := s.CreateClaimSessionGated(ctx, in, u.ID, id.New(), now)
		require.NoError(t, err)
		require.Nil(t, plus)
		require.NotNil(t, cs)
	}
	usage, err := s.LiveSplitUsage(ctx, u.ID, now)
	require.NoError(t, err)
	require.Equal(t, 0, usage.Used, "Plus hosting must not touch the free counter")
}

// TestConcurrentFourthConsumeNeverExceedsTheLimit runs six creates at once against a fresh account
// and requires exactly three to win.
func TestConcurrentFourthConsumeNeverExceedsTheLimit(t *testing.T) {
	s, u, in := allowanceFixture(t)
	now := time.Now().UTC()

	const callers = 6
	granted := make([]bool, callers)
	errs := make([]error, callers)
	var wg sync.WaitGroup
	for i := 0; i < callers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			cs, plus, err := s.CreateClaimSessionGated(context.Background(), in, u.ID, id.New(), now)
			errs[i] = err
			granted[i] = err == nil && plus == nil && cs != nil
		}(i)
	}
	wg.Wait()

	wins := 0
	for i, err := range errs {
		require.NoError(t, err)
		if granted[i] {
			wins++
		}
	}
	require.Equal(t, FreeLiveSplitsPerMonth, wins)

	usage, err := s.LiveSplitUsage(context.Background(), u.ID, now)
	require.NoError(t, err)
	require.Equal(t, FreeLiveSplitsPerMonth, usage.Used)
}

// TestConcurrentSameKeyCreatesOneSession pins the idempotency guarantee under a double-tap.
func TestConcurrentSameKeyCreatesOneSession(t *testing.T) {
	s, u, in := allowanceFixture(t)
	now := time.Now().UTC()
	key := "double-tap-" + id.New()

	ids := make([]string, 2)
	errs := make([]error, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			cs, _, err := s.CreateClaimSessionGated(context.Background(), in, u.ID, key, now)
			errs[i] = err
			if cs != nil {
				ids[i] = cs.ID
			}
		}(i)
	}
	wg.Wait()
	require.NoError(t, errs[0])
	require.NoError(t, errs[1])
	require.Equal(t, ids[0], ids[1])

	usage, err := s.LiveSplitUsage(context.Background(), u.ID, now)
	require.NoError(t, err)
	require.Equal(t, 1, usage.Used)
}
