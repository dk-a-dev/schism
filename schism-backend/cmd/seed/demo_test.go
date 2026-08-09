package main

import "testing"

// TestDemoFixture is the one check the fixture needs: every split the seeder posts must satisfy the
// server's validation rules, so a bad edit fails here instead of half-way through a live seed run.
func TestDemoFixture(t *testing.T) {
	for _, g := range demoGroups {
		people := map[string]bool{}
		for _, p := range g.people {
			people[p] = true
		}
		if !people["Dev"] || g.people[0] != "Dev" {
			t.Fatalf("%s: Dev must be participant 0 (the account holder)", g.name)
		}
		for _, e := range g.expenses {
			if e.amount <= 0 || e.amount > 1_000_000_000 {
				t.Errorf("%s/%s: amount %d out of range", g.name, e.title, e.amount)
			}
			if !people[e.payer] {
				t.Errorf("%s/%s: payer %q not in group", g.name, e.title, e.payer)
			}
			if e.daysAgo < 0 || e.daysAgo > 45 {
				t.Errorf("%s/%s: daysAgo %d outside the ~6 week window", g.name, e.title, e.daysAgo)
			}
			var sum int64
			for name, s := range e.shares {
				if !people[name] {
					t.Errorf("%s/%s: share for %q not in group", g.name, e.title, name)
				}
				if s <= 0 {
					t.Errorf("%s/%s: share for %q must be > 0", g.name, e.title, name)
				}
				sum += s
			}
			switch e.mode {
			case "BY_AMOUNT":
				if sum != e.amount {
					t.Errorf("%s/%s: BY_AMOUNT shares sum %d != amount %d", g.name, e.title, sum, e.amount)
				}
			case "BY_PERCENTAGE":
				if sum != 10000 {
					t.Errorf("%s/%s: BY_PERCENTAGE shares sum %d != 10000", g.name, e.title, sum)
				}
			case "", "BY_SHARES":
			default:
				t.Errorf("%s/%s: unknown split mode %q", g.name, e.title, e.mode)
			}
			if e.shares == nil && e.reimbursement {
				t.Errorf("%s/%s: a settle-up needs an explicit payee", g.name, e.title)
			}
		}
		if g.bill != nil {
			for i, it := range g.bill.items {
				if it.Idx != i || it.AmountMinor <= 0 || it.Qty <= 0 {
					t.Errorf("%s: bill item %d malformed: %+v", g.name, i, it)
				}
			}
		}
	}
}
