package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/stretchr/testify/require"
)

// Full flow: create group → add 2 expenses (uneven modes) → verify balances net to zero.
func TestEndToEndFlow(t *testing.T) {
	srv := newTestServer(t)
	g := createGroupFixture(t, srv.URL)
	a, b := g.Participants[0].ID, g.Participants[1].ID

	mk := func(body string) {
		resp, err := http.Post(srv.URL+"/v1/groups/"+g.ID+"/expenses", "application/json", bytes.NewBufferString(body))
		require.NoError(t, err)
		require.Equal(t, http.StatusCreated, resp.StatusCode)
	}
	mk(fmt.Sprintf(`{"title":"A pays","amount":3000,"paidById":%q,"splitMode":"BY_PERCENTAGE",
	  "paidFor":[{"participantId":%q,"shares":4000},{"participantId":%q,"shares":6000}]}`, a, a, b))
	mk(fmt.Sprintf(`{"title":"B pays","amount":1000,"paidById":%q,"splitMode":"EVENLY",
	  "paidFor":[{"participantId":%q,"shares":100},{"participantId":%q,"shares":100}]}`, b, a, b))

	resp, _ := http.Get(srv.URL + "/v1/groups/" + g.ID + "/balances")
	var out struct {
		Balances map[string]struct{ Total int64 } `json:"balances"`
	}
	require.NoError(t, json.NewDecoder(resp.Body).Decode(&out))
	var sum int64
	for _, v := range out.Balances {
		sum += v.Total
	}
	require.Equal(t, int64(0), sum, "balances must net to zero")
}
