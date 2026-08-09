package main

import (
	"context"
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestHTTPServerUsesBoundedProductionTimeouts(t *testing.T) {
	server := newHTTPServer(":0", http.NewServeMux())
	require.Equal(t, 5*time.Second, server.ReadHeaderTimeout)
	require.Equal(t, 15*time.Second, server.ReadTimeout)
	require.Equal(t, 30*time.Second, server.WriteTimeout)
	require.Equal(t, 60*time.Second, server.IdleTimeout)
	require.Equal(t, 1<<20, server.MaxHeaderBytes)
}

func TestRunHTTPServerStopsAfterContextCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	server := newHTTPServer("127.0.0.1:0", http.NewServeMux())
	done := make(chan error, 1)
	go func() { done <- runHTTPServer(ctx, server) }()

	cancel()
	select {
	case err := <-done:
		require.NoError(t, err)
	case <-time.After(2 * time.Second):
		t.Fatal("server did not stop after cancellation")
	}
}
