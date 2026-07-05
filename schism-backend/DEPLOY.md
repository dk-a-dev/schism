# Deploying schism-backend (get off ngrok)

Running behind `ngrok` means the app only works while your Mac + the tunnel are up, and the URL
changes every restart. Deploy once to a stable `https://<app>.fly.dev` and the app just works.

## Fly.io (Docker-native, no GitHub needed)

```bash
# 1. Install + log in
brew install flyctl
fly auth login

cd schism-backend

# 2. Create the app from the existing fly.toml (pick a unique name when prompted; keep region bom)
fly launch --no-deploy --copy-config --name <your-unique-name>

# 3. Managed Postgres, attached (this injects DATABASE_URL as a secret)
fly postgres create --name <your-unique-name>-db --region bom
fly postgres attach <your-unique-name>-db

# 4. Ship it (migrations run automatically on boot)
fly deploy

# 5. Your stable URL:
fly open        # -> https://<your-unique-name>.fly.dev
```

Verify: `curl https://<your-unique-name>.fly.dev/v1/categories` → `200`.

## Point the app at it (one-time APK rebuild)

```bash
cd ../schism-android
./gradlew :app:assembleDebug -Pschism.backendUrl=https://<your-unique-name>.fly.dev
# install app/build/outputs/apk/debug/app-debug.apk on the phone
```

Now the app reaches the backend anytime — no Mac, no ngrok. Invite links
(`https://<your-app>.fly.dev/g/<id>`) also open cleanly with no interstitial.

> Notes: the free/shared tier auto-stops the machine when idle and cold-starts on the next request
> (first hit after idle is a little slow). Managed Postgres has a small monthly cost on Fly. Render
> (`render.yaml` blueprint) is an equivalent alternative if you prefer connecting a GitHub repo.
