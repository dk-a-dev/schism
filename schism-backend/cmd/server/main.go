package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/schism/schism-backend/internal/api"
	"github.com/schism/schism-backend/internal/billing"
	"github.com/schism/schism-backend/internal/config"
	"github.com/schism/schism-backend/internal/store"
	webui "github.com/schism/schism-backend/internal/web"
)

func newHTTPServer(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}
}

func runHTTPServer(ctx context.Context, server *http.Server) error {
	serveErr := make(chan error, 1)
	go func() { serveErr <- server.ListenAndServe() }()

	select {
	case err := <-serveErr:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			return err
		}
		if err := <-serveErr; !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	}
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}
	publicSite, err := webui.New(webui.Config{
		SupportEmail:   cfg.SupportEmail,
		PublicURL:      cfg.PublicURL,
		PlayURL:        cfg.PlayURL,
		LegalVenueCity: cfg.LegalVenueCity,
	})
	if err != nil {
		log.Fatal(err)
	}
	if err := store.RunMigrations(cfg.DatabaseURL); err != nil {
		log.Fatal(err)
	}
	pool, err := store.NewPoolWithOptions(ctx, cfg.DatabaseURL, store.PoolOptions{
		MaxConns: cfg.DBMaxConns, MinConns: cfg.DBMinConns, MaxConnLifetime: cfg.DBMaxConnLifetime,
	})
	if err != nil {
		log.Fatal(err)
	}
	defer pool.Close()
	pingCtx, cancelPing := context.WithTimeout(ctx, 5*time.Second)
	if err := pool.Ping(pingCtx); err != nil {
		cancelPing()
		log.Fatal(err)
	}
	cancelPing()

	r := chi.NewRouter()
	r.Get("/ready", func(w http.ResponseWriter, req *http.Request) {
		if err := pool.Ping(req.Context()); err != nil {
			http.Error(w, "db down", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})
	monetization := api.Monetization{
		PlusEnabled:      cfg.PlusEnabled,
		AdsEnabled:       cfg.AdsEnabled,
		PurchasesEnabled: cfg.PurchasesEnabled,
		BillingTokenKey:  cfg.BillingTokenKey,
	}
	if cfg.PurchasesEnabled {
		verifier, err := billing.NewGoogle(cfg.PlayPackageName, cfg.PlayServiceAccountJSON)
		if err != nil {
			log.Fatal(err)
		}
		monetization.Verifier = verifier
	}
	r.Mount("/", api.NewRouterWithMonetization(store.NewStore(pool), cfg.LogRequests, monetization, publicSite))

	log.Printf("listening on %s", cfg.Addr)
	if err := runHTTPServer(ctx, newHTTPServer(cfg.Addr, r)); err != nil {
		log.Fatal(err)
	}
}
