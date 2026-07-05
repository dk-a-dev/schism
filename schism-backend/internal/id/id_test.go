package id

import (
	"regexp"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestNewFormat(t *testing.T) {
	re := regexp.MustCompile(`^[A-Za-z0-9_-]{12}$`)
	seen := map[string]bool{}
	for i := 0; i < 1000; i++ {
		v := New()
		require.Regexp(t, re, v)
		require.False(t, seen[v], "duplicate id %s", v)
		seen[v] = true
	}
}
