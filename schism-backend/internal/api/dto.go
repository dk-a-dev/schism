package api

import (
	"time"

	"github.com/schism/schism-backend/internal/store"
)

type participantDTO struct {
	ID   *string `json:"id"`
	Name string  `json:"name"`
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
		parts[i] = store.ParticipantInput{ID: p.ID, Name: p.Name}
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
	ExpenseDate     time.Time    `json:"expenseDate"`
	PaidByID        string       `json:"paidById"`
	SplitMode       string       `json:"splitMode"`
	IsReimbursement bool         `json:"isReimbursement"`
	Notes           string       `json:"notes"`
	PaidFor         []paidForDTO `json:"paidFor"`
}

func (d expenseFormDTO) toInput() store.ExpenseInput {
	pf := make([]store.PaidForInput, len(d.PaidFor))
	for i, p := range d.PaidFor {
		pf[i] = store.PaidForInput{ParticipantID: p.ParticipantID, Shares: p.Shares}
	}
	date := d.ExpenseDate
	if date.IsZero() {
		date = time.Now()
	}
	mode := d.SplitMode
	if mode == "" {
		mode = "EVENLY"
	}
	return store.ExpenseInput{
		Title: d.Title, Amount: d.Amount, CategoryID: d.CategoryID, ExpenseDate: date,
		PaidByID: d.PaidByID, SplitMode: mode, IsReimbursement: d.IsReimbursement,
		Notes: d.Notes, PaidFor: pf,
	}
}
