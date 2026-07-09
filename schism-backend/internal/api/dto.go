package api

import (
	"time"

	"github.com/schism/schism-backend/internal/store"
)

type participantDTO struct {
	ID     *string `json:"id"`
	Name   string  `json:"name"`
	UserID *string `json:"userId"`
	Phone  *string `json:"phone"`
}
type groupFormDTO struct {
	Name         string           `json:"name"`
	Information  string           `json:"information"`
	Currency     string           `json:"currency"`
	CurrencyCode string           `json:"currencyCode"`
	Participants []participantDTO `json:"participants"`
}

func (d groupFormDTO) toInput() store.GroupInput {
	parts := make([]store.ParticipantInput, len(d.Participants))
	for i, p := range d.Participants {
		parts[i] = store.ParticipantInput{ID: p.ID, Name: p.Name, UserID: p.UserID, Phone: p.Phone}
	}
	return store.GroupInput{
		Name: d.Name, Information: d.Information, Currency: d.Currency,
		CurrencyCode: d.CurrencyCode, Participants: parts,
	}
}

type paidForDTO struct {
	ParticipantID string `json:"participantId"`
	Shares        int64  `json:"shares"`
}
type expenseFormDTO struct {
	Title           string       `json:"title"`
	Amount          int64        `json:"amount"`
	CategoryID      int          `json:"categoryId"`
	// Accepts a date-only "2006-01-02" (what the clients send) or a full RFC3339 timestamp;
	// parsed leniently in toInput so a plain date never fails JSON decoding.
	ExpenseDate     string       `json:"expenseDate"`
	PaidByID        string       `json:"paidById"`
	SplitMode       string       `json:"splitMode"`
	IsReimbursement bool         `json:"isReimbursement"`
	Notes           string       `json:"notes"`
	AddedBy         string       `json:"addedBy"`
	PaidFor         []paidForDTO `json:"paidFor"`
}

type claimItemDTO struct {
	Idx         int    `json:"idx"`
	Name        string `json:"name"`
	Qty         int    `json:"qty"`
	AmountMinor int64  `json:"amountMinor"`
}

func toStoreItems(items []claimItemDTO) []store.ClaimItem {
	out := make([]store.ClaimItem, len(items))
	for i, it := range items {
		out[i] = store.ClaimItem{Idx: it.Idx, Name: it.Name, Qty: it.Qty, AmountMinor: it.AmountMinor}
	}
	return out
}

type createClaimSessionDTO struct {
	Title         string         `json:"title"`
	Currency      string         `json:"currency"`
	Items         []claimItemDTO `json:"items"`
	TaxMinor      int64          `json:"taxMinor"`
	FeesMinor     int64          `json:"feesMinor"`
	DiscountMinor int64          `json:"discountMinor"`
	RoundoffMinor int64          `json:"roundoffMinor"`
}

func (d createClaimSessionDTO) toStoreItems() []store.ClaimItem { return toStoreItems(d.Items) }

type claimWeightDTO struct {
	ItemIdx int     `json:"itemIdx"`
	Weight  float64 `json:"weight"`
}
type putClaimsDTO struct {
	ExpectedVersion int              `json:"expectedVersion"`
	Weights         []claimWeightDTO `json:"weights"`
}

type resolutionDTO struct {
	ItemIdx       int    `json:"itemIdx"`
	Mode          string `json:"mode"`
	ParticipantID string `json:"participantId"`
}
type finalizeDTO struct {
	ExpectedVersion int             `json:"expectedVersion"`
	Resolutions     []resolutionDTO `json:"resolutions"`
}

func (d finalizeDTO) toResolutions() []store.UnclaimedResolution {
	out := make([]store.UnclaimedResolution, len(d.Resolutions))
	for i, r := range d.Resolutions {
		out[i] = store.UnclaimedResolution{ItemIdx: r.ItemIdx, Mode: r.Mode, ParticipantID: r.ParticipantID}
	}
	return out
}

type setReadyDTO struct {
	Ready bool `json:"ready"`
}

type editItemsDTO struct {
	Items []claimItemDTO `json:"items"`
}

func (d editItemsDTO) toStoreItems() []store.ClaimItem { return toStoreItems(d.Items) }

func (d expenseFormDTO) toInput() store.ExpenseInput {
	pf := make([]store.PaidForInput, len(d.PaidFor))
	for i, p := range d.PaidFor {
		pf[i] = store.PaidForInput{ParticipantID: p.ParticipantID, Shares: p.Shares}
	}
	date := time.Now()
	if d.ExpenseDate != "" {
		if t, err := time.Parse("2006-01-02", d.ExpenseDate); err == nil {
			date = t
		} else if t, err := time.Parse(time.RFC3339, d.ExpenseDate); err == nil {
			date = t
		}
	}
	mode := d.SplitMode
	if mode == "" {
		mode = "EVENLY"
	}
	return store.ExpenseInput{
		Title: d.Title, Amount: d.Amount, CategoryID: d.CategoryID, ExpenseDate: date,
		PaidByID: d.PaidByID, SplitMode: mode, IsReimbursement: d.IsReimbursement,
		Notes: d.Notes, AddedBy: d.AddedBy, PaidFor: pf,
	}
}
