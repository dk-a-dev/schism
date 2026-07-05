package store

import (
	"context"
	"time"

	"github.com/schism/schism-backend/internal/id"
)

type Activity struct {
	ID            string    `json:"id"`
	GroupID       string    `json:"groupId"`
	Time          time.Time `json:"time"`
	ActivityType  string    `json:"activityType"`
	ParticipantID *string   `json:"participantId"`
	ExpenseID     *string   `json:"expenseId"`
	Data          string    `json:"data"`
}

func (s *Store) LogActivity(ctx context.Context, groupID, activityType string, participantID, expenseID *string, data string) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO activities (id, group_id, activity_type, participant_id, expense_id, data)
		 VALUES ($1,$2,$3,$4,$5,$6)`,
		id.New(), groupID, activityType, participantID, expenseID, nullify(data))
	return err
}

func (s *Store) ListActivities(ctx context.Context, groupID string) ([]Activity, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT id, group_id, time, activity_type, participant_id, expense_id, COALESCE(data,'')
		 FROM activities WHERE group_id=$1 ORDER BY time DESC`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Activity{}
	for rows.Next() {
		var a Activity
		if err := rows.Scan(&a.ID, &a.GroupID, &a.Time, &a.ActivityType, &a.ParticipantID, &a.ExpenseID, &a.Data); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}
