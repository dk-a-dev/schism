package store

import (
	"context"
	"sync"
	"testing"

	"github.com/schism/schism-backend/internal/id"
	"github.com/stretchr/testify/require"
)

func TestCreateAndGetClaimSession(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	g, err := st.CreateGroup(ctx, GroupInput{Name: "Trip", Currency: "₹",
		Participants: []ParticipantInput{{Name: "Dev"}, {Name: "Ru"}}})
	if err != nil {
		t.Fatal(err)
	}
	creator := g.Participants[0].ID

	cs, err := st.CreateClaimSession(ctx, ClaimSessionInput{
		GroupID: g.ID, CreatorParticipantID: creator, Title: "Dinner", Currency: "₹",
		Items:    []ClaimItem{{Idx: 0, Name: "Biryani", Qty: 2, AmountMinor: 30000}},
		TaxMinor: 1500,
	})
	if err != nil {
		t.Fatal(err)
	}
	if cs.Status != "open" || cs.Version != 1 {
		t.Fatalf("bad session %+v", cs)
	}

	got, err := st.GetClaimSession(ctx, cs.ID)
	if err != nil || got == nil {
		t.Fatalf("get: %v %v", got, err)
	}
	if len(got.Items) != 1 || got.Items[0].AmountMinor != 30000 {
		t.Fatalf("items %+v", got.Items)
	}
	if got.TaxMinor != 1500 {
		t.Fatalf("tax %d", got.TaxMinor)
	}
}

func TestUpsertClaimsReplacesAndGuards(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	g, _ := st.CreateGroup(ctx, GroupInput{Name: "T", Currency: "₹",
		Participants: []ParticipantInput{{Name: "Dev"}, {Name: "Ru"}}})
	cs, _ := st.CreateClaimSession(ctx, ClaimSessionInput{GroupID: g.ID,
		CreatorParticipantID: g.Participants[0].ID, Items: []ClaimItem{{Idx: 0, Name: "X", Qty: 1, AmountMinor: 1000}}})
	ru := g.Participants[1].ID

	if err := st.UpsertClaims(ctx, cs.ID, ru, 1, map[int]float64{0: 2}); err != nil {
		t.Fatal(err)
	}
	got, _ := st.GetClaimSession(ctx, cs.ID)
	if len(got.Claims) != 1 || got.Claims[0].Weight != 2 {
		t.Fatalf("claims %+v", got.Claims)
	}

	// stale version rejected
	if err := st.UpsertClaims(ctx, cs.ID, ru, 99, map[int]float64{0: 1}); err != ErrClaimStale {
		t.Fatalf("want ErrClaimStale, got %v", err)
	}
}

func TestEditItemsDropsChangedItemClaims(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	g, _ := st.CreateGroup(ctx, GroupInput{Name: "T", Currency: "₹", Participants: []ParticipantInput{{Name: "Dev"}}})
	cs, _ := st.CreateClaimSession(ctx, ClaimSessionInput{GroupID: g.ID, CreatorParticipantID: g.Participants[0].ID,
		Items: []ClaimItem{{Idx: 0, Name: "X", Qty: 1, AmountMinor: 1000}}})
	_ = st.UpsertClaims(ctx, cs.ID, g.Participants[0].ID, 1, map[int]float64{0: 1})

	v, err := st.EditItems(ctx, cs.ID, []ClaimItem{{Idx: 0, Name: "X", Qty: 1, AmountMinor: 2000}})
	if err != nil || v != 2 {
		t.Fatalf("v=%d err=%v", v, err)
	}
	got, _ := st.GetClaimSession(ctx, cs.ID)
	if len(got.Claims) != 0 {
		t.Fatalf("claims should be dropped: %+v", got.Claims)
	}
}

func TestComputeClaimSplitWeightedWithTax(t *testing.T) {
	items := []ClaimItem{{Idx: 0, Name: "Dish", Qty: 3, AmountMinor: 30000}}
	claims := []Claim{{ItemIdx: 0, ParticipantID: "dev", Weight: 2}, {ItemIdx: 0, ParticipantID: "ru", Weight: 1}}
	owed := ComputeClaimSplit(items, claims, 3000, 0, 0, 0, nil, []string{"dev", "ru"}, "dev")
	// 30000 split 2:1 → dev 20000, ru 10000; tax 3000 split 2:1 → dev 2000, ru 1000
	if owed["dev"] != 22000 || owed["ru"] != 11000 {
		t.Fatalf("owed %+v", owed)
	}
}

func TestComputeClaimSplitUnclaimedSplitEvenly(t *testing.T) {
	items := []ClaimItem{{Idx: 0, Name: "Nobody", Qty: 1, AmountMinor: 10000}}
	owed := ComputeClaimSplit(items, nil, 0, 0, 0, 0,
		[]UnclaimedResolution{{ItemIdx: 0, Mode: "split"}}, []string{"a", "b"}, "a")
	if owed["a"] != 5000 || owed["b"] != 5000 {
		t.Fatalf("owed %+v", owed)
	}
}

func TestFinalizeClaimSessionBuildsExpenseAndIsIdempotent(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	g, _ := st.CreateGroup(ctx, GroupInput{Name: "T", Currency: "₹",
		Participants: []ParticipantInput{{Name: "Dev"}, {Name: "Ru"}}})
	dev, ru := g.Participants[0].ID, g.Participants[1].ID
	cs, _ := st.CreateClaimSession(ctx, ClaimSessionInput{GroupID: g.ID, CreatorParticipantID: dev, Title: "Dinner",
		Items: []ClaimItem{{Idx: 0, Name: "Dish", Qty: 3, AmountMinor: 30000}}, TaxMinor: 3000})
	if err := st.UpsertClaims(ctx, cs.ID, dev, 1, map[int]float64{0: 2}); err != nil {
		t.Fatal(err)
	}
	if err := st.UpsertClaims(ctx, cs.ID, ru, 1, map[int]float64{0: 1}); err != nil {
		t.Fatal(err)
	}

	eid, err := st.FinalizeClaimSession(ctx, cs.ID, 1, nil)
	if err != nil || eid == "" {
		t.Fatalf("finalize: eid=%q err=%v", eid, err)
	}
	e, err := st.GetExpense(ctx, g.ID, eid)
	if err != nil || e == nil {
		t.Fatalf("get expense: %v %v", e, err)
	}
	if e.Amount != 33000 {
		t.Fatalf("amount %d", e.Amount)
	}
	byPid := map[string]int64{}
	for _, pf := range e.PaidFor {
		byPid[pf.ParticipantID] = pf.Shares
	}
	if byPid[dev] != 22000 || byPid[ru] != 11000 {
		t.Fatalf("paidFor %+v", byPid)
	}

	got, err := st.GetClaimSession(ctx, cs.ID)
	if err != nil || got.Status != "finalized" || got.ExpenseID == nil || *got.ExpenseID != eid {
		t.Fatalf("session after finalize: %+v err=%v", got, err)
	}

	// Idempotent: a second finalize (even with a now-stale expectedVersion) returns the same expense id.
	eid2, err := st.FinalizeClaimSession(ctx, cs.ID, 999, nil)
	if err != nil || eid2 != eid {
		t.Fatalf("second finalize: eid=%q err=%v", eid2, err)
	}
}

func TestCancelClaimSessionAndParticipantLookup(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	u, _, err := st.CreateUser(ctx, "Dev", "dev-"+id.New()+"@example.com", "1")
	if err != nil {
		t.Fatal(err)
	}
	g, _ := st.CreateGroup(ctx, GroupInput{Name: "T", Currency: "₹",
		Participants: []ParticipantInput{{Name: "Dev", UserID: &u.ID}, {Name: "Ru"}}})
	var devPid string
	for _, p := range g.Participants {
		if p.UserID != nil && *p.UserID == u.ID {
			devPid = p.ID
		}
	}
	if devPid == "" {
		t.Fatal("dev participant not linked")
	}
	cs, _ := st.CreateClaimSession(ctx, ClaimSessionInput{GroupID: g.ID, CreatorParticipantID: devPid,
		Items: []ClaimItem{{Idx: 0, Name: "X", Qty: 1, AmountMinor: 1000}}})

	// ParticipantForUserInGroup returns the linked participant id, "" when no link.
	got, err := st.ParticipantForUserInGroup(ctx, u.ID, g.ID)
	if err != nil || got != devPid {
		t.Fatalf("participant lookup got=%q err=%v", got, err)
	}
	none, err := st.ParticipantForUserInGroup(ctx, "nobody", g.ID)
	if err != nil || none != "" {
		t.Fatalf("expected empty, got=%q err=%v", none, err)
	}

	// Cancel flips status; a claim after cancel is locked out.
	if err := st.CancelClaimSession(ctx, cs.ID); err != nil {
		t.Fatal(err)
	}
	after, _ := st.GetClaimSession(ctx, cs.ID)
	if after.Status != "cancelled" {
		t.Fatalf("status %q", after.Status)
	}
	if err := st.UpsertClaims(ctx, cs.ID, devPid, 1, map[int]float64{0: 1}); err != ErrClaimLocked {
		t.Fatalf("want ErrClaimLocked, got %v", err)
	}
}

// TestFinalizeRejectsUnresolvedItemsThenSucceedsWithResolution proves finalize never silently drops an
// item's amount: an item with zero claimed weight and no resolution must block finalize (ErrUnresolvedItems)
// rather than being skipped, and once resolved the expense totals the full bill.
func TestFinalizeRejectsUnresolvedItemsThenSucceedsWithResolution(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	g, _ := st.CreateGroup(ctx, GroupInput{Name: "T", Currency: "₹",
		Participants: []ParticipantInput{{Name: "Dev"}, {Name: "Ru"}}})
	dev := g.Participants[0].ID
	cs, _ := st.CreateClaimSession(ctx, ClaimSessionInput{GroupID: g.ID, CreatorParticipantID: dev, Title: "Dinner",
		Items: []ClaimItem{
			{Idx: 0, Name: "Claimed", Qty: 1, AmountMinor: 10000},
			{Idx: 1, Name: "Unclaimed", Qty: 1, AmountMinor: 5000},
		}, TaxMinor: 1000})
	if err := st.UpsertClaims(ctx, cs.ID, dev, 1, map[int]float64{0: 1}); err != nil {
		t.Fatal(err)
	}

	if _, err := st.FinalizeClaimSession(ctx, cs.ID, 1, nil); err != ErrUnresolvedItems {
		t.Fatalf("want ErrUnresolvedItems, got %v", err)
	}

	eid, err := st.FinalizeClaimSession(ctx, cs.ID, 1,
		[]UnclaimedResolution{{ItemIdx: 1, Mode: "split"}})
	if err != nil || eid == "" {
		t.Fatalf("finalize with resolution: eid=%q err=%v", eid, err)
	}
	e, err := st.GetExpense(ctx, g.ID, eid)
	if err != nil || e == nil {
		t.Fatalf("get expense: %v %v", e, err)
	}
	// Full bill: 10000 (claimed) + 5000 (resolved) + 1000 tax = 16000. Nothing dropped.
	if e.Amount != 16000 {
		t.Fatalf("amount %d, want full bill 16000", e.Amount)
	}
}

// TestFinalizeDropsClaimsOfRemovedParticipant proves that removing a participant from the group while
// their claim session is still open doesn't wedge finalize with an FK violation, and their claim's
// weight no longer counts (per the documented "their claims are dropped" behavior).
func TestFinalizeDropsClaimsOfRemovedParticipant(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	g, _ := st.CreateGroup(ctx, GroupInput{Name: "T", Currency: "₹",
		Participants: []ParticipantInput{{Name: "Dev"}, {Name: "Ru"}, {Name: "Amit"}}})
	byName := func(name string) Participant {
		for _, p := range g.Participants {
			if p.Name == name {
				return p
			}
		}
		t.Fatalf("no participant %q", name)
		return Participant{}
	}
	dev, ru, amit := byName("Dev"), byName("Ru"), byName("Amit")

	cs, _ := st.CreateClaimSession(ctx, ClaimSessionInput{GroupID: g.ID, CreatorParticipantID: dev.ID, Title: "Dinner",
		Items: []ClaimItem{{Idx: 0, Name: "Dish", Qty: 2, AmountMinor: 20000}}, TaxMinor: 2000})
	if err := st.UpsertClaims(ctx, cs.ID, dev.ID, 1, map[int]float64{0: 1}); err != nil {
		t.Fatal(err)
	}
	if err := st.UpsertClaims(ctx, cs.ID, amit.ID, 1, map[int]float64{0: 1}); err != nil {
		t.Fatal(err)
	}

	// Remove Amit from the group while the session is still open.
	devID, ruID := dev.ID, ru.ID
	if _, err := st.UpdateGroup(ctx, g.ID, GroupInput{Name: "T", Currency: "₹",
		Participants: []ParticipantInput{{ID: &devID, Name: "Dev"}, {ID: &ruID, Name: "Ru"}}}); err != nil {
		t.Fatal(err)
	}

	eid, err := st.FinalizeClaimSession(ctx, cs.ID, 1, nil)
	if err != nil || eid == "" {
		t.Fatalf("finalize: eid=%q err=%v", eid, err)
	}
	e, err := st.GetExpense(ctx, g.ID, eid)
	if err != nil || e == nil {
		t.Fatalf("get expense: %v %v", e, err)
	}
	byPid := map[string]int64{}
	for _, pf := range e.PaidFor {
		byPid[pf.ParticipantID] = pf.Shares
	}
	if _, present := byPid[amit.ID]; present {
		t.Fatalf("amit's claim should have been dropped, got %+v", byPid)
	}
	// Amit's claim is gone (dropped by the ON DELETE CASCADE + the defensive owed-map filter), so dev
	// is the sole remaining claimant on item 0 and absorbs the whole item + tax: 20000 + 2000 = 22000.
	// The total is still exact over what's left claimed - nothing is silently lost to nobody.
	if e.Amount != 22000 {
		t.Fatalf("amount %d, want 22000 (dev absorbs the full item+tax once amit's claim is dropped)", e.Amount)
	}
	if byPid[dev.ID] != 22000 {
		t.Fatalf("dev share %+v", byPid)
	}
}

// TestComputeClaimSplitPotSurvivesZeroClaimedSubtotal proves the charge pot (tax/fees/discount/roundoff)
// is still distributed when the claimed items happen to sum to zero minor units, instead of vanishing.
func TestComputeClaimSplitPotSurvivesZeroClaimedSubtotal(t *testing.T) {
	items := []ClaimItem{{Idx: 0, Name: "Free", Qty: 1, AmountMinor: 0}}
	claims := []Claim{{ItemIdx: 0, ParticipantID: "dev", Weight: 1}, {ItemIdx: 0, ParticipantID: "ru", Weight: 1}}
	owed := ComputeClaimSplit(items, claims, 1000, 0, 0, 0, nil, []string{"dev", "ru"}, "dev")
	if owed["dev"]+owed["ru"] != 1000 {
		t.Fatalf("pot lost: owed %+v", owed)
	}
	if owed["dev"] == 0 && owed["ru"] == 0 {
		t.Fatalf("pot dropped entirely: owed %+v", owed)
	}
}

// TestClaimVsFinalizeRace proves the row-lock state machine never silently loses a claim: a concurrent
// UpsertClaims and FinalizeClaimSession either both succeed (the claim is counted in the expense) or
// the claim is cleanly rejected with ErrClaimLocked — never a partial/lost write, and exactly one
// expense results. Run with -race.
//
// A "cover" resolution is passed for the sole item so the item is always resolvable regardless of which
// goroutine's row lock wins: if ru's claim lands first it claims the item (the resolution is then
// irrelevant, per ComputeClaimSplit precedence); if finalize wins the lock first with no claim landed
// yet, the resolution assigns the item to the creator instead of finalize spuriously failing with
// ErrUnresolvedItems.
func TestClaimVsFinalizeRace(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	g, _ := st.CreateGroup(ctx, GroupInput{Name: "T", Currency: "₹",
		Participants: []ParticipantInput{{Name: "Dev"}, {Name: "Ru"}}})
	dev, ru := g.Participants[0].ID, g.Participants[1].ID
	cs, _ := st.CreateClaimSession(ctx, ClaimSessionInput{GroupID: g.ID, CreatorParticipantID: dev,
		Items: []ClaimItem{{Idx: 0, Name: "X", Qty: 1, AmountMinor: 1000}}})

	var wg sync.WaitGroup
	wg.Add(2)
	var claimErr error
	var finErr error
	var eid string
	go func() {
		defer wg.Done()
		claimErr = st.UpsertClaims(ctx, cs.ID, ru, 1, map[int]float64{0: 1})
	}()
	go func() {
		defer wg.Done()
		eid, finErr = st.FinalizeClaimSession(ctx, cs.ID, 1, []UnclaimedResolution{{ItemIdx: 0, Mode: "cover"}})
	}()
	wg.Wait()

	// No panic; claim either counted (nil) or cleanly locked out — never any other error.
	if claimErr != nil && claimErr != ErrClaimLocked {
		t.Fatalf("unexpected claim error: %v", claimErr)
	}
	require.NoError(t, finErr)
	require.NotEmpty(t, eid)

	// Exactly one expense exists for the group.
	list, err := st.ListExpenses(ctx, g.ID)
	require.NoError(t, err)
	require.Len(t, list, 1)

	// If the claim landed before finalize, ru is in the expense; otherwise the claim was locked out.
	got, _ := st.GetClaimSession(ctx, cs.ID)
	require.Equal(t, "finalized", got.Status)
	e := list[0]
	ruInExpense := false
	for _, pf := range e.PaidFor {
		if pf.ParticipantID == ru {
			ruInExpense = true
		}
	}
	if claimErr == nil {
		require.True(t, ruInExpense, "claim succeeded but ru not in finalized expense")
	} else {
		require.False(t, ruInExpense, "claim was locked out but ru appeared in expense")
	}
}
