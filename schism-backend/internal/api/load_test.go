package api

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestConcurrentOCRManifestLoad(t *testing.T) {
	srv := httptest.NewServer(NewRouter(nil, false))
	t.Cleanup(srv.Close)
	const requests = 500
	errs := make(chan error, requests)
	var wg sync.WaitGroup
	for i := 0; i < requests; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			resp, err := http.Get(srv.URL + "/v1/models/ocr/manifest")
			if err == nil {
				resp.Body.Close()
				if resp.StatusCode != http.StatusOK {
					err = &unexpectedStatusError{status: resp.StatusCode}
				}
			}
			if err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		require.NoError(t, err)
	}
}

type unexpectedStatusError struct{ status int }

func (e *unexpectedStatusError) Error() string { return http.StatusText(e.status) }
