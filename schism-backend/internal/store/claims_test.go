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
