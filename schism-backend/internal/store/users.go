package store

import (
	"context"
	"time"

	"github.com/schism/schism-backend/internal/id"
)

type User struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Email     string    `json:"email"`
	Phone     string    `json:"phone"`
	CreatedAt time.Time `json:"createdAt"`
}

// CreateUser registers a device owner's (unverified) identity. Email is deliberately NOT treated as
// a unique/lookup key — without verification, upserting by email would let anyone claim or overwrite
// another person's account — so every call inserts a distinct user and returns its id.
func (s *Store) CreateUser(ctx context.Context, name, email, phone string) (User, error) {
	var u User
	err := s.pool.QueryRow(ctx,
		`INSERT INTO users (id, name, email, phone) VALUES ($1,$2,$3,$4)
		 RETURNING id, name, email, phone, created_at`,
		id.New(), name, email, phone).
		Scan(&u.ID, &u.Name, &u.Email, &u.Phone, &u.CreatedAt)
	if err != nil {
		return User{}, err
	}
	return u, nil
}
