package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/schism/schism-backend/internal/id"
)

type User struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Email     string    `json:"email"`
	Phone     string    `json:"phone"`
	CreatedAt time.Time `json:"createdAt"`
}

// UpsertUser links a device owner's identity. When email is non-empty and already
// registered, it updates that row's name and phone; otherwise it inserts a new user.
func (s *Store) UpsertUser(ctx context.Context, name, email, phone string) (User, error) {
	if email != "" {
		var u User
		err := s.pool.QueryRow(ctx,
			`UPDATE users SET name=$2, phone=$3 WHERE email=$1
			 RETURNING id, name, email, phone, created_at`,
			email, name, phone).
			Scan(&u.ID, &u.Name, &u.Email, &u.Phone, &u.CreatedAt)
		if err == nil {
			return u, nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return User{}, err
		}
	}
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

func (s *Store) GetUser(ctx context.Context, uid string) (*User, error) {
	var u User
	err := s.pool.QueryRow(ctx,
		`SELECT id, name, email, phone, created_at FROM users WHERE id=$1`, uid).
		Scan(&u.ID, &u.Name, &u.Email, &u.Phone, &u.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}
