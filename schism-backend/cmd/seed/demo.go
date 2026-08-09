package main

// Demo seeder: drives the PUBLIC HTTP API (never the database) to fill a signed-in account with
// synthetic, non-sensitive demo data for screenshots. Run with:
//
//	go run ./cmd/seed demo -base https://api.example -email you@example -password "$PW"
//
// Idempotent: groups are matched by name against the caller's memberships and reused, and every
// expense is created with a stable Idempotency-Key, so a second run changes nothing. There is no
// destructive mode because the API has no "delete group" route.

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

// ---------- tiny HTTP client ----------

type demoClient struct {
	base  string
	token string
}

func (c *demoClient) do(method, path string, in, out any, headers map[string]string) error {
	var body io.Reader
	if in != nil {
		b, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, c.base+path, body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode >= 300 {
		return fmt.Errorf("%s %s -> %s: %s", method, path, resp.Status, strings.TrimSpace(string(raw)))
	}
	if out != nil && len(raw) > 0 {
		return json.Unmarshal(raw, out)
	}
	return nil
}

// ---------- API shapes (only the fields the seeder needs) ----------

type apiParticipant struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}
type apiGroup struct {
	ID           string           `json:"id"`
	Name         string           `json:"name"`
	Participants []apiParticipant `json:"participants"`
}
type apiAuth struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Token string `json:"token"`
}

// ---------- fixture data ----------

// exp is one demo expense. shares maps participant NAME -> share value, interpreted per mode
// (weights for BY_SHARES, minor units for BY_AMOUNT, percent×100 for BY_PERCENTAGE). A nil shares
// map means an even split across everyone in the group.
type exp struct {
	title         string
	amount        int64 // minor units (paise)
	category      int
	daysAgo       int
	payer         string
	mode          string // "" == EVENLY
	shares        map[string]int64
	reimbursement bool
	notes         string
}

type billItem struct {
	Idx         int    `json:"idx"`
	Name        string `json:"name"`
	Qty         int    `json:"qty"`
	AmountMinor int64  `json:"amountMinor"`
}

type bill struct {
	title string
	items []billItem
	tax   int64
}

type grp struct {
	name     string
	info     string
	people   []string // people[0] is the account holder and becomes the linked participant
	expenses []exp
	bill     *bill // optional open Live Split session
}

// demoGroups is the whole fixture. Merchants, people and amounts are invented; nothing here maps to
// a real person, business, account or tax registration.
var demoGroups = []grp{
	{
		name:   "Goa Trip",
		info:   "Four days on the coast. Everything from the flights to the last beach shack.",
		people: []string{"Dev", "Asha", "Rohan", "Mira"},
		expenses: []exp{
			{title: "Sunbeam Air tickets", amount: 32_000_00, category: 34, daysAgo: 41, payer: "Dev"},
			{title: "Saltbreeze Resort — 3 nights", amount: 27_450_00, category: 31, daysAgo: 40, payer: "Asha"},
			{title: "Cove & Co seafood dinner", amount: 4_860_00, category: 6, daysAgo: 39, payer: "Rohan",
				mode: "BY_SHARES", shares: map[string]int64{"Dev": 2, "Asha": 1, "Rohan": 2, "Mira": 1},
				notes: "Rohan and Dev ordered the platter."},
			{title: "Blue Bay scooter rental", amount: 1_800_00, category: 32, daysAgo: 38, payer: "Mira"},
			{title: "Anchor Fuels petrol", amount: 950_00, category: 30, daysAgo: 37, payer: "Dev"},
			{title: "Tidepool ferry tickets", amount: 1_280_00, category: 28, daysAgo: 36, payer: "Asha",
				mode: "BY_AMOUNT", shares: map[string]int64{"Dev": 380_00, "Asha": 300_00, "Rohan": 300_00, "Mira": 300_00},
				notes: "Dev took the upper deck seat."},
			{title: "Beach shack lunch", amount: 2_140_00, category: 6, daysAgo: 35, payer: "Mira"},
			{title: "Airport cab home", amount: 1_640_00, category: 35, daysAgo: 34, payer: "Rohan"},
			{title: "Settle up — Rohan to Dev", amount: 6_000_00, category: 0, daysAgo: 20, payer: "Rohan",
				shares: map[string]int64{"Dev": 1}, reimbursement: true},
		},
	},
	{
		name:   "Flat 402",
		info:   "Rent, bills and the weekly grocery run.",
		people: []string{"Dev", "Asha", "Rohan"},
		expenses: []exp{
			{title: "Rent — July", amount: 42_000_00, category: 17, daysAgo: 41, payer: "Dev",
				mode: "BY_PERCENTAGE", shares: map[string]int64{"Dev": 4000, "Asha": 3000, "Rohan": 3000},
				notes: "Dev has the larger room."},
			{title: "Electricity bill", amount: 3_180_00, category: 37, daysAgo: 32, payer: "Asha"},
			{title: "Loomtown fibre internet", amount: 1_299_00, category: 41, daysAgo: 30, payer: "Rohan"},
			{title: "Greenbasket groceries", amount: 4_865_00, category: 7, daysAgo: 27, payer: "Asha"},
			{title: "Household supplies", amount: 875_00, category: 12, daysAgo: 22, payer: "Rohan"},
			{title: "Rent — August", amount: 42_000_00, category: 17, daysAgo: 11, payer: "Dev",
				mode: "BY_PERCENTAGE", shares: map[string]int64{"Dev": 4000, "Asha": 3000, "Rohan": 3000}},
			{title: "Greenbasket groceries", amount: 3_920_00, category: 7, daysAgo: 12, payer: "Dev"},
			{title: "Sparrow deep clean", amount: 2_500_00, category: 36, daysAgo: 8, payer: "Dev",
				mode: "BY_SHARES", shares: map[string]int64{"Dev": 1, "Asha": 1, "Rohan": 1}},
			{title: "Settle up — Asha to Dev", amount: 9_000_00, category: 0, daysAgo: 5, payer: "Asha",
				shares: map[string]int64{"Dev": 1}, reimbursement: true},
		},
	},
	{
		name:   "Sunday Table",
		info:   "The standing Sunday dinner. Whoever books, pays; we square it later.",
		people: []string{"Dev", "Asha", "Rohan", "Mira"},
		expenses: []exp{
			{title: "Nimbu Kitchen dinner", amount: 3_720_00, category: 6, daysAgo: 35, payer: "Mira"},
			{title: "Kettle & Crumb brunch", amount: 2_680_00, category: 6, daysAgo: 21, payer: "Dev",
				mode: "BY_SHARES", shares: map[string]int64{"Dev": 2, "Asha": 1, "Rohan": 1, "Mira": 2}},
			{title: "Cellar 9 wine", amount: 2_450_00, category: 8, daysAgo: 14, payer: "Rohan",
				mode: "BY_AMOUNT", shares: map[string]int64{"Dev": 700_00, "Asha": 550_00, "Rohan": 700_00, "Mira": 500_00}},
			{title: "Marigold Cafe dessert", amount: 960_00, category: 9, daysAgo: 7, payer: "Asha"},
			{title: "Board game night snacks", amount: 780_00, category: 1, daysAgo: 2, payer: "Mira"},
		},
		bill: &bill{
			title: "Nimbu Kitchen — Sunday dinner",
			items: []billItem{
				{Idx: 0, Name: "Paneer tikka", Qty: 1, AmountMinor: 420_00},
				{Idx: 1, Name: "Lime soda", Qty: 2, AmountMinor: 240_00},
				{Idx: 2, Name: "Dal makhani", Qty: 1, AmountMinor: 380_00},
				{Idx: 3, Name: "Butter naan", Qty: 4, AmountMinor: 320_00},
				{Idx: 4, Name: "Veg biryani", Qty: 1, AmountMinor: 460_00},
				{Idx: 5, Name: "Gulab jamun", Qty: 2, AmountMinor: 180_00},
			},
			tax: 100_00,
		},
	},
}

// ---------- runner ----------

func demoMain(args []string) {
	fs := flag.NewFlagSet("demo", flag.ExitOnError)
	base := fs.String("base", os.Getenv("SCHISM_BASE_URL"), "backend base URL (env SCHISM_BASE_URL)")
	email := fs.String("email", os.Getenv("SCHISM_EMAIL"), "account email (env SCHISM_EMAIL)")
	password := fs.String("password", os.Getenv("SCHISM_PASSWORD"), "account password (env SCHISM_PASSWORD)")
	name := fs.String("name", "Dev", "display name used if -register creates the account")
	register := fs.Bool("register", false, "register the account when login fails (for local/testing)")
	_ = fs.Parse(args)
	if *base == "" || *email == "" || *password == "" {
		log.Fatal("demo: -base, -email and -password (or SCHISM_BASE_URL/SCHISM_EMAIL/SCHISM_PASSWORD) are required")
	}

	c := &demoClient{base: strings.TrimRight(*base, "/")}
	var auth apiAuth
	creds := map[string]string{"email": *email, "password": *password}
	if err := c.do("POST", "/v1/auth/login", creds, &auth, nil); err != nil {
		if !*register {
			log.Fatalf("login: %v", err)
		}
		reg := map[string]string{"name": *name, "email": *email, "password": *password}
		if err := c.do("POST", "/v1/auth/register", reg, &auth, nil); err != nil {
			log.Fatalf("register: %v", err)
		}
	}
	c.token = auth.Token
	fmt.Printf("signed in as %s (%s)\n", auth.Name, auth.ID)

	existing := map[string]apiGroup{}
	var mine []apiGroup
	if err := c.do("GET", "/v1/groups", nil, &mine, nil); err != nil {
		log.Fatalf("list groups: %v", err)
	}
	for _, g := range mine {
		existing[g.Name] = g
	}

	today := time.Now().UTC().Truncate(24 * time.Hour)
	for _, sd := range demoGroups {
		g, fresh := existing[sd.name], false
		if g.ID == "" {
			parts := make([]map[string]string, len(sd.people))
			for i, p := range sd.people {
				parts[i] = map[string]string{"name": p}
			}
			var created struct {
				GroupID string `json:"groupId"`
			}
			body := map[string]any{"name": sd.name, "information": sd.info,
				"currency": "₹", "currencyCode": "INR", "participants": parts}
			if err := c.do("POST", "/v1/groups", body, &created, nil); err != nil {
				log.Fatalf("create group %q: %v", sd.name, err)
			}
			if err := c.do("GET", "/v1/groups/"+created.GroupID, nil, &g, nil); err != nil {
				log.Fatalf("fetch group %q: %v", sd.name, err)
			}
			fresh = true
		}

		byName := map[string]string{}
		for _, p := range g.Participants {
			byName[p.Name] = p.ID
		}
		for _, want := range sd.people {
			if byName[want] == "" {
				// ponytail: a pre-existing same-named group with different people is a conflict we
				// refuse rather than reconcile. Rename or use a clean account.
				log.Fatalf("group %q exists but has no participant %q — rename it and re-run", sd.name, want)
			}
		}

		for i, e := range sd.expenses {
			paidFor := []map[string]any{}
			if e.shares == nil {
				for _, p := range sd.people {
					paidFor = append(paidFor, map[string]any{"participantId": byName[p], "shares": 1})
				}
			} else {
				for _, p := range sd.people { // stable order
					if s, ok := e.shares[p]; ok {
						paidFor = append(paidFor, map[string]any{"participantId": byName[p], "shares": s})
					}
				}
			}
			mode := e.mode
			if mode == "" {
				mode = "EVENLY"
			}
			body := map[string]any{
				"title": e.title, "amount": e.amount, "categoryId": e.category,
				"expenseDate": today.AddDate(0, 0, -e.daysAgo).Format(time.RFC3339),
				"paidById":    byName[e.payer], "splitMode": mode,
				"isReimbursement": e.reimbursement, "notes": e.notes, "paidFor": paidFor,
			}
			key := fmt.Sprintf("demo-v1-%02d", i)
			if err := c.do("POST", "/v1/groups/"+g.ID+"/expenses", body, nil,
				map[string]string{"Idempotency-Key": key}); err != nil {
				log.Fatalf("expense %q in %q: %v", e.title, sd.name, err)
			}
		}

		// The Live Split session is only created with the group. Re-running never adds a second one:
		// with monetization off the server ignores Idempotency-Key on this route, so "fresh" is the
		// only guard available through the public API.
		if sd.bill != nil && fresh {
			body := map[string]any{
				"title": sd.bill.title, "currency": "₹", "items": sd.bill.items,
				"taxMinor": sd.bill.tax,
				"taxes":    []map[string]any{{"label": "GST 5%", "amountMinor": sd.bill.tax}},
			}
			var cs struct {
				ID string `json:"id"`
			}
			if err := c.do("POST", "/v1/groups/"+g.ID+"/claim-sessions", body, &cs,
				map[string]string{"Idempotency-Key": "demo-v1-bill-" + g.ID}); err != nil {
				log.Fatalf("claim session in %q: %v", sd.name, err)
			}
			fmt.Printf("  live split %s (%d items) → %s/c/%s\n", sd.bill.title, len(sd.bill.items), c.base, cs.ID)
		}

		state := "reused"
		if fresh {
			state = "created"
		}
		fmt.Printf("✓ %-14s %s  id=%s  %d expenses\n", sd.name, state, g.ID, len(sd.expenses))
	}
	fmt.Println("\nDemo seed complete (idempotent — safe to re-run).")
}
