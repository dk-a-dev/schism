package store

import (
	"context"
	"testing"
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
