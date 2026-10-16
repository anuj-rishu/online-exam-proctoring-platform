# Making SRM Insider Labs Public

How to take a working local install (see [README.md](README.md) for that part)
and serve it on a real domain over HTTPS — from a laptop, desktop, or server,
on **Windows, macOS, or Linux**.

The examples use the domain `labs.srminsider.in` and port `8080`. Substitute
your own.

> **Deploying to a server?** [Section 11](#11-docker-compose-deployment) is now
> the shortest path: set a domain and secrets in `.env`, run
> `docker compose up -d`, and you get the app, MongoDB, Redis and automatic
> HTTPS with nothing else to install. The manual sections below still apply if
> you want to run the app directly on the host.

---

## Table of contents

1. [Choosing how to host](#1-choosing-how-to-host)
2. [App configuration for public hosting](#2-app-configuration-for-public-hosting)
3. [Install cloudflared](#3-install-cloudflared)
4. [Create and route the tunnel](#4-create-and-route-the-tunnel)
5. [Run the app as a background service](#5-run-the-app-as-a-background-service)
6. [Run the tunnel as a background service](#6-run-the-tunnel-as-a-background-service)
7. [Stop the machine from sleeping](#7-stop-the-machine-from-sleeping)
8. [Pre-launch checklist](#8-pre-launch-checklist)
9. [Troubleshooting](#9-troubleshooting)
10. [Known limits of hosting from a personal machine](#10-known-limits-of-hosting-from-a-personal-machine)
11. [Docker Compose deployment](#11-docker-compose-deployment)

---

## 1. Choosing how to host

**Option A — Cloudflare Tunnel (recommended, and what this guide covers).**
A small agent (`cloudflared`) runs on your machine and makes an *outbound*
connection to Cloudflare. Cloudflare then serves your domain over HTTPS and
forwards requests down that tunnel.

Why this is the right choice for a laptop or home/office machine:
- **No port forwarding** and no router configuration.
- **Works behind NAT and with a dynamic IP** — your address can change freely.
- **Your home/office IP is never exposed** publicly.
- **HTTPS is handled by Cloudflare** — no certificate to buy, install, or renew.
- Free.

**Option B — A cloud VPS** (Hetzner, DigitalOcean, AWS, Oracle Cloud free
tier, etc.). More reliable for real exams because it isn't your laptop, but
you manage the server, firewall, and TLS certificates yourself. If you go this
route you can still use a Cloudflare Tunnel on the VPS, or point DNS straight
at the VPS IP and terminate TLS with Caddy/nginx + Let's Encrypt.

> **For a graded/proctored exam with real students, Option B is the safer
> choice.** See [section 10](#10-known-limits-of-hosting-from-a-personal-machine).

---

## 2. App configuration for public hosting

All of these live in `.env` at the project root (git-ignored — never commit
it). See `.env.example` for the full template.

### 2.1 Required before real users log in

| Variable | Why it matters publicly |
|---|---|
| `JWT_SECRET` | Must be a long random value, unique per environment. Generate with `openssl rand -hex 32`. **Must contain only Base64 characters** (`A–Z a–z 0–9 + / =`) — hyphens/underscores will crash token generation at startup. |
| `MONGODB_URI` | Keep MongoDB bound to localhost, or use MongoDB Atlas. Never expose port 27017 publicly. |
| `REDIS_HOST` / `REDIS_PORT` | Same — keep Redis on localhost. Never expose port 6379 publicly. |
| `BREVO_API_KEY`, `BREVO_SENDER_EMAIL` | Without these, **no one receives login credentials or super-admin OTP emails** — nobody can log in. The sender address must be verified in Brevo. |
| `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_ENDPOINT`, `R2_BUCKET` | Identity-verification photo storage. The app now starts without them (logs a warning), but identity photos silently won't work. |
| `SUPERADMIN_EMAIL` | The single email allowed to become Super Admin via OTP login. |

### 2.2 Set your public domain

```bash
CORS_ALLOWED_ORIGINS=https://labs.srminsider.in
```

Comma-separated, no spaces, no trailing slash. (Strictly this only matters if
something calls the API from a *different* origin — the frontend is served by
the app itself, so those calls are same-origin — but set it correctly anyway.)

### 2.3 TURN relay — required for live video/audio proctoring

**This is the single most commonly missed step.** WebRTC needs a relay to
connect two browsers on different real-world networks. With STUN only, live
video and the proctor's mic **fail silently** once students are off your LAN —
webcam snapshots keep working, which makes it look like a partial bug rather
than a missing relay.

Get a TURN key from the Cloudflare dashboard → **Realtime** → **TURN Service**
→ create a Turn Key. It gives you a **Turn Token ID** and an **API Token**:

```bash
CF_TURN_KEY_ID=your_turn_token_id
CF_TURN_API_TOKEN=your_turn_api_token
```

The backend uses these to mint short-lived credentials per session — the API
token itself is never sent to the browser. Verify your key works before
relying on it:

```bash
curl -X POST "https://rtc.live.cloudflare.com/v1/turn/keys/$CF_TURN_KEY_ID/credentials/generate" \
  -H "Authorization: Bearer $CF_TURN_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ttl": 86400}'
```

A working key returns HTTP `201` and a JSON `iceServers` object. `{"error":
"cannot find specified key"}` means the ID/token pair isn't a TURN key (a
general Cloudflare API token or a Realtime *App* ID will not work here).

### 2.4 HTTPS and WebSockets — nothing to do

The frontend already picks `wss://` automatically when the page is served over
`https://`, and Cloudflare terminates TLS for you. The app keeps serving plain
HTTP on `localhost:8080`; that traffic never crosses the public internet
unencrypted.

### 2.5 If you add any new external resource later

The app sends a strict `Content-Security-Policy`. **A blocked resource
produces no server-side error at all** — the browser just silently refuses it,
so it looks like a random UI bug. If you add a new CDN script, stylesheet,
font, or image host, add it to the matching directive in
`SecurityConfig.java`. The test
`ContentSecurityPolicyCoversStaticAssetsTest` scans every page and fails the
build if a `<script>` or `<link rel=stylesheet>` host isn't permitted — run
the test suite after touching either.

---

## 3. Install cloudflared

**Prerequisite:** your domain's nameservers must already point to Cloudflare
(add the site in the Cloudflare dashboard first — the free plan is fine).

**macOS**
```bash
brew install cloudflare/cloudflare/cloudflared
```

**Windows** — download the `.msi` from
[Cloudflare's releases page](https://github.com/cloudflare/cloudflared/releases)
and run it, or with winget:
```powershell
winget install --id Cloudflare.cloudflared
```

**Linux (Debian/Ubuntu)**
```bash
curl -L https://pkg.cloudflare.com/cloudflare-main.gpg \
  | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main" \
  | sudo tee /etc/apt/sources.list.d/cloudflared.list
sudo apt update && sudo apt install cloudflared
```

Other distributions: download the binary or `.rpm` from the releases page.

Verify: `cloudflared --version`

---

## 4. Create and route the tunnel

These steps are the same on all three platforms.

```bash
# 1. Authenticate — opens a browser; pick your domain
cloudflared tunnel login

# 2. Create the tunnel
cloudflared tunnel create labs-tunnel

# 3. Note the tunnel ID
cloudflared tunnel list
```

Step 2 writes a credentials file named `<tunnel-id>.json` into your
`cloudflared` config directory:
- macOS/Linux: `~/.cloudflared/`
- Windows: `%USERPROFILE%\.cloudflared\`

**Create the config file yourself** — `cloudflared tunnel create` does *not*
create it. Put it next to that credentials file as `config.yml`:

```yaml
tunnel: <your-tunnel-id>
credentials-file: /Users/<you>/.cloudflared/<your-tunnel-id>.json

ingress:
  - hostname: labs.srminsider.in
    service: http://localhost:8080
  - service: http_status:404
```

On Windows use a Windows path, e.g.
`C:\Users\<you>\.cloudflared\<your-tunnel-id>.json`.

> **Only route port 8080.** Never add ingress entries for MongoDB (27017) or
> Redis (6379) — that would publish your database to the internet. The
> catch-all `http_status:404` entry at the end is what keeps anything else
> from being served.

Create the DNS record and validate:

```bash
cloudflared tunnel route dns labs-tunnel labs.srminsider.in
cloudflared tunnel ingress validate
```

Then test it in the foreground (with your app already running on 8080):

```bash
cloudflared tunnel run labs-tunnel
```

Visit `https://labs.srminsider.in`. Once that works, `Ctrl+C` and move on to
running both pieces as services.

---

## 5. Run the app as a background service

Running via `./mvnw spring-boot:run` or your IDE is fine for development, but
for anything public, build a jar and run that — it survives closing your
editor and starts faster:

```bash
./mvnw clean package -DskipTests
# produces target/labs-0.0.1-SNAPSHOT.jar
java -jar target/labs-0.0.1-SNAPSHOT.jar
```

### macOS (launchd)

Create `~/Library/LaunchAgents/in.srminsider.labs.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>in.srminsider.labs</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/java</string>
        <string>-jar</string>
        <string>/Users/<you>/Developer/labs/target/labs-0.0.1-SNAPSHOT.jar</string>
    </array>
    <key>WorkingDirectory</key>
    <string>/Users/<you>/Developer/labs</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/Users/<you>/Developer/labs/logs/app.out.log</string>
    <key>StandardErrorPath</key>
    <string>/Users/<you>/Developer/labs/logs/app.err.log</string>
</dict>
</plist>
```

```bash
mkdir -p ~/Developer/labs/logs
launchctl load ~/Library/LaunchAgents/in.srminsider.labs.plist
launchctl list | grep srminsider     # confirm it's running
```

`WorkingDirectory` matters — the app reads `.env` and starts the Python
identity service using paths relative to it.

`/usr/bin/java` is the macOS stub that resolves to your active JDK. If you
have several JDKs installed and want to pin one, use its full path instead
(`/usr/libexec/java_home -v 25` prints it).

### Linux (systemd)

Create `/etc/systemd/system/labs.service`:

```ini
[Unit]
Description=SRM Insider Labs
After=network-online.target mongod.service redis-server.service

[Service]
Type=simple
User=youruser
WorkingDirectory=/home/youruser/labs
ExecStart=/usr/bin/java -jar /home/youruser/labs/target/labs-0.0.1-SNAPSHOT.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now labs
sudo systemctl status labs
journalctl -u labs -f      # follow logs
```

### Windows

Simplest reliable option is [NSSM](https://nssm.cc/):

```powershell
nssm install SRMLabs "C:\Program Files\Java\jdk-25\bin\java.exe" "-jar C:\labs\target\labs-0.0.1-SNAPSHOT.jar"
nssm set SRMLabs AppDirectory C:\labs
nssm set SRMLabs Start SERVICE_AUTO_START
nssm start SRMLabs
```

Alternatively use Task Scheduler with a "At startup" trigger, running
`java -jar ...` with "Start in" set to the project directory.

---

## 6. Run the tunnel as a background service

**macOS / Linux:**
```bash
sudo cloudflared service install
```

**Windows (run PowerShell as Administrator):**
```powershell
cloudflared service install
```

This reads your `config.yml`, copies it plus credentials to a system location,
and registers a service that starts at boot.

Verify it actually installed a working service — not just a placeholder:

**macOS:** `cat /Library/LaunchDaemons/com.cloudflare.cloudflared.plist` —
the `ProgramArguments` array must include `tunnel` and `run`, not just the
bare binary path. If it shows only the binary with no arguments, the install
was incomplete:
```bash
sudo cloudflared service uninstall
sudo cloudflared service install
```

**Linux:** `sudo systemctl status cloudflared`
**Windows:** `Get-Service cloudflared`

Then stop any manual `cloudflared tunnel run` you still have open, and confirm
the site still loads — that proves the *service* is serving it, not your
terminal.

---

## 7. Stop the machine from sleeping

If the host sleeps, every connected student's exam drops.

**macOS** — System Settings → Battery/Energy → prevent sleep while plugged in.
To also keep running with the lid closed:
```bash
sudo pmset -c disablesleep 1     # revert with: sudo pmset -c disablesleep 0
```

**Windows** — Settings → System → Power & battery → Screen and sleep → set
sleep to **Never** when plugged in. For lid-close: Control Panel → Power
Options → "Choose what closing the lid does" → **Do nothing**.

**Linux**
```bash
sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
```
On a laptop also set `HandleLidSwitch=ignore` in `/etc/systemd/logind.conf`,
then `sudo systemctl restart systemd-logind`.

Use **wired Ethernet** rather than Wi-Fi wherever possible.

---

## 8. Pre-launch checklist

Run through this before real students log in.

**Infrastructure**
- [ ] `https://labs.srminsider.in` loads over HTTPS
- [ ] The tunnel is running as a **service**, with no manual terminal open
- [ ] The app is running as a **service**, with your IDE closed
- [ ] Both survive a full reboot (actually reboot and re-test)
- [ ] MongoDB and Redis are **not** reachable from outside the machine
- [ ] Sleep is disabled; machine is on wired network and plugged in

**Configuration**
- [ ] `JWT_SECRET` is a fresh random Base64-safe value, not the example one
- [ ] Brevo sends mail — create a test admin and confirm the email arrives
- [ ] R2 credentials set; complete one identity verification end-to-end
- [ ] `CF_TURN_KEY_ID` / `CF_TURN_API_TOKEN` set and verified (`curl` above)
- [ ] `CORS_ALLOWED_ORIGINS` contains your real domain
- [ ] `SANDBOX_ENABLED=true`, and the three sandbox images are built
      (`docker images | grep sandbox`)

**Functional test, from a different network than the admin** (e.g. phone
hotspot for the student, so you're genuinely testing the public path):
- [ ] Student can log in, verify identity, and enter the exam
- [ ] Student can **type in the code editor** and Run/Test code
- [ ] Admin sees the student's webcam snapshots
- [ ] Admin can open the **live video feed** and hear/talk via mic
- [ ] Admin can view the student's **identity photos**
- [ ] Browser devtools console shows **zero CSP violations** on both the
      student and admin pages
- [ ] Tab-switch and screenshot attempts appear in the proctor's alerts
- [ ] Section submit works and unlocks the next section

**Backups**
- [ ] You have a plan for backing up MongoDB (`mongodump`), since exam
      submissions live only on this machine unless you're using Atlas

---

## 9. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `https://…` returns 502 | App isn't running on 8080 | Start the app; check `curl http://localhost:8080/` locally first |
| Site dies when you close a terminal | Tunnel is running manually, not as a service | [Section 6](#6-run-the-tunnel-as-a-background-service) |
| Site dies on reboot | App and/or tunnel service not installed or not enabled | [Sections 5 & 6](#5-run-the-app-as-a-background-service) |
| `cloudflared service install` says "already installed" | A previous partial install left a placeholder service | `sudo cloudflared service uninstall` then install again |
| Live video/mic never connects, snapshots fine | No TURN relay configured | [Section 2.3](#23-turn-relay--required-for-live-videoaudio-proctoring) |
| Identity photos show a broken-image icon | CSP `img-src` doesn't allow the R2 host | Check `img-src` in `SecurityConfig.java` |
| Students can't type in the code editor | CSP `style-src` doesn't allow CodeMirror's CSS host | Check `style-src`; run `ContentSecurityPolicyCoversStaticAssetsTest` |
| Some UI feature silently does nothing | A CSP-blocked resource | Open devtools console and look for CSP violation messages |
| App won't start: `Illegal base64 character` | `JWT_SECRET` contains non-Base64 characters (e.g. hyphens) | Use `openssl rand -hex 32` |
| App won't start: `Access key ID cannot be blank` | Older builds crashed on missing R2 credentials | Update to current code (warns instead), or set R2 vars |
| Nobody receives credential/OTP emails | Brevo key missing, or sender not verified in Brevo | Fix `BREVO_*` vars; verify the sender address |
| Code Run/Test fails instantly | Docker not running, or sandbox images not built | Start Docker; rebuild the three images |
| Identity verification returns 503 | Python service venv missing | Re-run `python-services/identity-verification/setup.sh` |

---

## 10. Known limits of hosting from a personal machine

Be honest with yourself (and your client) about these before an exam:

- **Single point of failure.** A sleep, crash, reboot, Wi-Fi drop, or power
  loss interrupts every student's exam at once. There is no failover.
- **Shared resources.** Every student's code submission spawns a Docker
  container on this same machine, and live video relays through it. Using the
  machine for other work during an exam will affect performance. Test with a
  realistic number of concurrent students before exam day.
- **Your own network is the bottleneck.** Home/office upload bandwidth limits
  how many simultaneous video feeds you can sustain.
- **Data lives here.** Exam submissions, proctoring logs, and student records
  are on this one disk. Take backups.
- **Security posture.** Student code runs in a Docker container on this
  machine. That's a strong boundary but not a virtual machine — never set
  `SANDBOX_ENABLED=false` while publicly reachable.

For a real graded exam, a modest cloud VPS (2 vCPU / 4 GB is plenty to start)
removes most of these risks for a few dollars a month, and the entire setup in
this guide works there unchanged.

---

## 11. Docker Compose deployment

One published image plus three stock ones. Nothing to install on the server
beyond Docker itself — no JDK, no Python, no Maven, no model downloads.

### 11.1 What runs

| Service | Image | Notes |
|---|---|---|
| `app` | `ghcr.io/srm-insider/srm-insider-lab` | Spring Boot + the Python face service + baked-in face models |
| `mongo` | `mongo:8` | Named volume, no published port |
| `redis` | `redis:7` | AOF on, `noeviction` — it holds live sessions, not just cache |
| `caddy` | `caddy:2` | Automatic Let's Encrypt certificate for your domain |

Only Caddy publishes ports (80/443). MongoDB and Redis are reachable solely on
the internal compose network, so they cannot be exposed by a firewall mistake.

### 11.2 Server prerequisites

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER && newgrp docker
```

Point your domain's **A record** at the machine's public IP before starting —
Caddy needs to answer an HTTP-01 challenge on port 80 to issue the certificate.
Leave Cloudflare proxying **off (grey cloud)**.

### 11.3 Deploy

```bash
git clone https://github.com/srm-insider/SRM-INSIDER-LAB labs && cd labs
cp .env.example .env
nano .env          # set DOMAIN, then the secrets below
docker compose up -d
```

In `.env` you only need to set:

| Variable | Notes |
|---|---|
| `DOMAIN` | e.g. `labs.srminsider.in` — drives both the TLS certificate and the CORS origin |
| `JWT_SECRET` | `openssl rand -hex 32` — Base64 alphabet only |
| `SUPERADMIN_EMAIL` | The one address allowed to become Super Admin |
| `BREVO_API_KEY` / `BREVO_SENDER_EMAIL` | Without these nobody can log in — no credential or OTP mail is sent |
| `R2_*` | Identity-photo storage |
| `CF_TURN_KEY_ID` / `CF_TURN_API_TOKEN` | Live proctoring video; fails silently without them |

`MONGODB_URI`, `REDIS_HOST` and `CORS_ALLOWED_ORIGINS` are overridden by
`docker-compose.yml` to point at the compose services, so leave them alone.

Watch the first start — it builds the three sandbox images once (~2 min):

```bash
docker compose logs -f app
```

Look for `Started LabsApplication`, `[CpuBudget] ... cores detected`, and
`[PythonService] ... starting on port 8500`.

### 11.4 Why the app mounts the Docker socket

Student code runs in throwaway containers. The app shells out to `docker run`
and bind-mounts a scratch directory into each one, so it drives the **host**
daemon through the mounted socket rather than running a daemon of its own.

That has one consequence worth understanding: the daemon resolves bind-mount
paths against the host filesystem, not against the app container. `EXEC_TMPDIR`
is therefore mounted at the **same absolute path on both sides**, and the app
runs with `-Djava.io.tmpdir` pointing at it. Change one without the other and
every submission mounts an empty directory and fails.

Mounting the socket also gives the app container effective root on the host.
That is the same privilege it has when run directly on the host, but keep the
box dedicated to this service.

### 11.5 Upgrades, backups, rollback

```bash
docker compose pull && docker compose up -d      # upgrade
docker compose down                              # stop (volumes survive)
```

Data lives in the named volumes `labs_mongo-data`, `labs_redis-data` and
`labs_caddy-data`, so replacing containers never touches exam data. Back Mongo
up with:

```bash
docker compose exec -T mongo mongodump --archive --gzip --db labs_db > labs-$(date +%F).gz
```

Pin a known-good build by setting `APP_VERSION=4.0.1` in `.env` rather than
tracking `latest`.

### 11.6 Publishing a new image

Tag the repo and let CI build it:

```bash
git tag v4.0.2 && git push origin v4.0.2
```

`.github/workflows/publish-image.yml` builds `linux/amd64` and pushes
`ghcr.io/<owner>/<repo>:4.0.2` and `:latest`.

To build by hand, use `build.sh` — it picks the architecture from intent rather
than from whichever machine you happen to be on:

```bash
./build.sh              # local:  native arch (arm64 on Apple Silicon), tagged :dev
./build.sh prod 4.0.2   # prod:   linux/amd64, tagged :4.0.2 + :latest, pushed
```

Local stays native because emulating x86 makes the Maven and pip stages several
times slower. Production forces `linux/amd64` **regardless of your machine** —
an arm64 image pushed from a Mac fails on an x86 server with `exec format
error`, and it fails at container start, not at build time, so nothing warns you
until the server is already down.

GHCR packages are **private by default**, so the server must authenticate once:

```bash
echo "$GH_TOKEN" | docker login ghcr.io -u <github-user> --password-stdin
```

`$GH_TOKEN` must have the **`read:packages`** scope. The token from `gh auth
token` does *not* have it by default — `gh auth status` lists the scopes it
actually holds, and a pull with a token lacking it fails with `DENIED`, which
looks identical to the image not existing. Either run `gh auth refresh -s
read:packages` and reuse that token, or create a classic PAT with `read:packages`
at github.com/settings/tokens.

Alternatively make the package public (GitHub → Packages → the package →
Package settings → Change visibility) and the server needs no login at all. The
image contains no secrets, but it does contain the compiled application, so this
publishes your code.

> **Never bake `.env` into the image.** It carries the R2 keys, Brevo key, TURN
> token and `JWT_SECRET`; anyone who can pull the image can read every layer,
> and deleting the tag afterwards does not undo the disclosure. `.dockerignore`
> excludes it, and compose mounts it read-only at runtime instead.
