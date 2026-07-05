// Command seed wipes the group data and inserts a few mock groups with participants and expenses,
// for a clean demo restart. Run with: make seed  (needs Postgres up).
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/schism/schism-backend/internal/store"
)

func main() {
	url := os.Getenv("DATABASE_URL")
	if url == "" {
		log.Fatal("DATABASE_URL is required")
	}
	ctx := context.Background()
	pool, err := store.NewPool(ctx, url)
	if err != nil {
		log.Fatalf("connect: %v", err)
	}
	defer pool.Close()
	s := store.NewStore(pool)

	// Wipe group data (participants/expenses/paid_for/activities cascade). Users/tokens are kept so
	// the device stays registered.
	if _, err := pool.Exec(ctx, `TRUNCATE TABLE groups CASCADE`); err != nil {
		log.Fatalf("wipe: %v", err)
	}
	log.Println("wiped group data")

	now := time.Now()
	seeds := []struct {
		name    string
		people  []string
		expenses []struct {
			title  string
			amount int64
			payer  int // index into people
		}
	}{
		{
			name:   "Goa Trip",
			people: []string{"Dev", "Aisha", "Rohan"},
			expenses: []struct {
				title  string
				amount int64
				payer  int
			}{
				{"Beach resort", 1_800000, 0},
				{"Seafood dinner", 46500, 1},
				{"Scooter rental", 12000, 2},
				{"Cab from airport", 9000, 0},
			},
		},
		{
			name:   "Flat 402",
			people: []string{"Dev", "Sam", "Priya"},
			expenses: []struct {
				title  string
				amount int64
				payer  int
			}{
				{"October rent", 3_600000, 0},
				{"Groceries", 84300, 1},
				{"Wifi bill", 109900, 2},
			},
		},
		{
			name:   "Office Lunch",
			people: []string{"Dev", "Karan", "Meera"},
			expenses: []struct {
				title  string
				amount int64
				payer  int
			}{
				{"Thali Tuesday", 62000, 1},
				{"Biryani Friday", 78000, 0},
			},
		},
	}

	for _, sd := range seeds {
		parts := make([]store.ParticipantInput, len(sd.people))
		for i, name := range sd.people {
			n := name
			parts[i] = store.ParticipantInput{Name: n}
		}
		g, err := s.CreateGroup(ctx, store.GroupInput{
			Name: sd.name, Currency: "₹", CurrencyCode: "INR", Participants: parts,
		})
		if err != nil {
			log.Fatalf("create group %q: %v", sd.name, err)
		}
		for j, e := range sd.expenses {
			paidFor := make([]store.PaidForInput, len(g.Participants))
			for k, p := range g.Participants {
				paidFor[k] = store.PaidForInput{ParticipantID: p.ID, Shares: 1}
			}
			_, err := s.CreateExpense(ctx, g.ID, store.ExpenseInput{
				Title:       e.title,
				Amount:      e.amount,
				ExpenseDate: now.AddDate(0, 0, -j),
				PaidByID:    g.Participants[e.payer].ID,
				SplitMode:   "EVENLY",
				AddedBy:     g.Participants[e.payer].ID,
				PaidFor:     paidFor,
			}, fmt.Sprintf("seed-%s-%d", g.ID, j))
			if err != nil {
				log.Fatalf("create expense %q: %v", e.title, err)
			}
		}
		fmt.Printf("✓ %-14s  id=%s  join: schism://group/%s\n", sd.name, g.ID, g.ID)
	}
	fmt.Println("\nSeed complete. Open the join links above on the device to see the groups.")
}
