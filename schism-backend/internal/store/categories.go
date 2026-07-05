package store

import "context"

type Category struct {
	ID       int    `json:"id"`
	Grouping string `json:"grouping"`
	Name     string `json:"name"`
}

func (s *Store) ListCategories(ctx context.Context) ([]Category, error) {
	rows, err := s.pool.Query(ctx, `SELECT id, grouping, name FROM categories ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Category{}
	for rows.Next() {
		var c Category
		if err := rows.Scan(&c.ID, &c.Grouping, &c.Name); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}
