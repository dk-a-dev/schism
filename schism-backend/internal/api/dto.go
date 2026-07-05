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
