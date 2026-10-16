# SRM Insider Labs

A proctored online coding-exam platform. Students take timed, section-based
MCQ and coding tests under live webcam and browser monitoring; admins run the
exam operationally (question bank, live proctoring, grading); a super admin
controls the operation as a whole (admin accounts, proctor-to-student
assignment, permanent deletions).

This document covers everything needed to set the project up from scratch on
**Windows, macOS, and Linux**, and how to use it once it's running.

> To put it on a public domain over HTTPS once it runs locally, see
> **[DEPLOYMENT.md](DEPLOYMENT.md)**.

---

## 1. Tech stack (what you're installing, and why)

| Component | Used for |
|---|---|
| Java 25 (JDK) | Runs the Spring Boot backend |
| Maven | Builds the project — a wrapper (`mvnw`/`mvnw.cmd`) is included, so a separate Maven install is **not** required |
| MongoDB | Primary database — users, questions, exam sessions, proctor assignments |
| Redis | Session cache, distributed code-execution queue, real-time pub/sub |
| Docker | Runs each student's submitted code inside an isolated, resource-limited container (no network, memory/CPU/process caps) |
| Python 3.10–3.12 | Runs the identity-verification microservice (face/ID matching), auto-started by the Java app |
| Cloudflare R2 account | Stores identity-verification photos (S3-compatible object storage) |
| Brevo account | Sends login credential emails to new admins/students, and OTP emails for super-admin login |
| Cloudflare Realtime TURN | Relays live proctoring video/audio between proctor and student (see §4) |
| OpenPDF | Renders downloadable violation reports — LGPL/MPL, chosen over iText 5+ which is AGPL and would need a paid licence |

The frontend is plain HTML/JS/Tailwind (loaded via CDN) — there is no separate
frontend build step or Node.js requirement on the host machine.

---

## 2. Prerequisites

### 2.1 Java 25 (JDK)

- **Windows**: Download the JDK 25 installer from [Adoptium](https://adoptium.net/) and run it. Verify with `java -version` in a new terminal.
- **macOS**: `brew install openjdk@25` (Homebrew), then follow the printed instructions to link it, or download from Adoptium.
- **Linux (Debian/Ubuntu)**: `sudo apt install openjdk-25-jdk` (or use [Adoptium's apt repo](https://adoptium.net/installation/linux/) if 25 isn't in your distro's repos yet).

### 2.2 Docker

Required for sandboxed code execution (the default and recommended mode).

- **Windows / macOS**: Install [Docker Desktop](https://www.docker.com/products/docker-desktop/) and make sure it's running before you start the app.
- **Linux**: Install [Docker Engine](https://docs.docker.com/engine/install/) for your distribution, then add your user to the `docker` group (`sudo usermod -aG docker $USER`, then log out/in) so you don't need `sudo` for every command.

### 2.3 MongoDB

Pick one:
- **Local install**: [MongoDB Community Server](https://www.mongodb.com/try/download/community) — installers for Windows/macOS/Linux on that page. Default connection is `mongodb://localhost:27017`.
- **Docker** (same on every OS, no local install needed):
  ```bash
  docker run -d --name labs-mongo -p 27017:27017 mongo:7
  ```
- **MongoDB Atlas** (managed, cloud-hosted, free tier available) — use the connection string Atlas gives you instead of `localhost`.

### 2.4 Redis

Pick one:
- **Windows**: Native Redis isn't officially supported on Windows — easiest is Docker (below), or [Memurai](https://www.memurai.com/) (a Redis-compatible Windows port).
- **macOS**: `brew install redis && brew services start redis`
- **Linux**: `sudo apt install redis-server` (Debian/Ubuntu) or your distro's equivalent, then `sudo systemctl enable --now redis-server`
- **Docker** (identical on every OS):
  ```bash
  docker run -d --name labs-redis -p 6379:6379 redis:7
  ```

### 2.5 Python 3.10–3.12

- **Windows**: Install from [python.org](https://www.python.org/downloads/) — check "Add python.exe to PATH" during install.
- **macOS**: `brew install python@3.12`
- **Linux**: `sudo apt install python3 python3-venv python3-pip`

### 2.6 Git

- **Windows**: [Git for Windows](https://git-scm.com/download/win) (this also gives you **Git Bash**, which you'll need to run the one shell script in this project — see §5).
- **macOS**: `brew install git` (or it's already present via Xcode Command Line Tools).
- **Linux**: `sudo apt install git`

---

## 3. Get the code

```bash
git clone https://github.com/srm-insider/SRM-INSIDER-LAB
cd labs
```

---

## 4. Configure environment variables

Copy the example file and fill in real values:

```bash
cp .env.example .env      # macOS/Linux/Git Bash
copy .env.example .env    # Windows Command Prompt
```

Edit `.env`:

| Variable | What it is | Where to get it |
|---|---|---|
| `R2_ACCESS_KEY` / `R2_SECRET_KEY` | Cloudflare R2 API credentials | Cloudflare dashboard → R2 → Manage API Tokens |
| `R2_ENDPOINT` | Your R2 account's S3-compatible endpoint | Shown alongside your R2 bucket in the Cloudflare dashboard |
| `R2_BUCKET` | The bucket name to store identity-verification photos in | Create one in Cloudflare R2 first |
| `JWT_SECRET` | Signs login tokens | Generate a long random string, e.g. `openssl rand -hex 32` |
| `MONGODB_URI` | Your MongoDB connection string | `mongodb://localhost:27017/labs_db` for a local install, or your Atlas URI |
| `REDIS_HOST` / `REDIS_PORT` | Your Redis connection | `localhost` / `6379` for a local or Docker Redis |
| `SUPERADMIN_EMAIL` | The one email address allowed to become Super Admin | Pick the real email your client will log in with |
| `BREVO_API_KEY` | Sends credential/OTP emails | [Brevo](https://www.brevo.com/) dashboard → SMTP & API → API Keys |
| `BREVO_SENDER_EMAIL` | The "from" address on those emails | Must be a **verified sender** in your Brevo account |
| `SANDBOX_ENABLED` | Whether code execution runs in Docker | Leave as `true` — see §6 |
| `CORS_ALLOWED_ORIGINS` | Origins allowed to call the API cross-origin | Comma-separated, no trailing slash. Only matters if you serve the frontend from a different origin |
| `CF_TURN_KEY_ID` / `CF_TURN_API_TOKEN` | Relays live proctoring video/audio | Cloudflare dashboard → **Realtime** → **TURN Service** → create a Turn Key |

**The app will not start without `MONGODB_URI`, `REDIS_HOST`/`PORT`, and `JWT_SECRET` set.** R2 and Brevo are checked lazily — the app boots without them, but photo storage and all outbound email (which includes every new admin/student's login credentials, and the super admin's OTP) silently won't work until they're configured. Set them before onboarding real users.

> **`JWT_SECRET` must be valid Base64** (`A–Z a–z 0–9 + / =` only). Hyphens or
> underscores will crash token generation at startup. `openssl rand -hex 32`
> always produces a safe value.

> **Without `CF_TURN_*`, live video and the proctor's Talk button fail silently
> once students are on a different network from the proctor** — webcam
> snapshots keep arriving, which makes it look like a partial bug rather than
> missing configuration. Verify a key works before an exam:
> ```bash
> curl -X POST "https://rtc.live.cloudflare.com/v1/turn/keys/$CF_TURN_KEY_ID/credentials/generate" \
>   -H "Authorization: Bearer $CF_TURN_API_TOKEN" \
>   -H "Content-Type: application/json" -d '{"ttl": 86400}'
> ```
> A working key returns HTTP `201` and a JSON `iceServers` object.

---

## 5. One-time setup: identity-verification service

This only needs to be done once (and again if `requirements.txt` ever changes).

**macOS / Linux:**
```bash
cd python-services/identity-verification
./setup.sh
cd ../..
```

**Windows** (run in Git Bash, not Command Prompt/PowerShell):
```bash
cd python-services/identity-verification
./setup.sh
cd ../..
```
If you don't have Git Bash available, do the equivalent manually in PowerShell:
```powershell
cd python-services\identity-verification
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
# then download these two files into a new "models" folder:
#   models\face_detection_yunet.onnx   <- https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx
#   models\face_recognition_sface.onnx <- https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx
cd ..\..
```
This downloads two face-detection/recognition model files (~37 MB total). The Java app auto-starts this service from the virtual environment it creates — you never run it manually after this.

---

## 6. One-time setup: code execution sandbox images

Each student's Run/Test click executes inside a locked-down, disposable Docker
container (no network access, capped memory/CPU/process count, non-root,
read-only filesystem). Build the three images once — same commands on every OS
(run from the project root, with Docker running):

```bash
docker build -t sandbox-python docker/sandbox-python
docker build -t sandbox-node   docker/sandbox-node
docker build -t sandbox-java   docker/sandbox-java
```

Re-run these only if you edit the Dockerfiles under `docker/`.

> If `SANDBOX_ENABLED=false`, the app instead runs student code directly on
> the host with no isolation. This exists only for local debugging without
> Docker installed — **never use it in production.**

### Tuning how much of the machine code execution may use

Concurrency is derived at startup from the host's core count, so the same
build behaves sensibly on a laptop and on a larger server:

| Variable | Default | Effect |
|---|---|---|
| `CPU_UTILISATION` | `0.8` | Share of cores student code may consume |
| `EXEC_WORKERS` | auto | Pin the concurrent-submission count instead of deriving it |
| `SANDBOX_CPUS` | auto | Pin the per-container CPU cap instead of deriving it |

At startup the log states what it chose, e.g.:

```
[CpuBudget] 8 cores detected, 80% budget -> 6 workers x 1.0 CPU (6.0 cores max for student code)
```

The default is deliberately **not 100%**. This process also relays live
proctoring video and shares the host with MongoDB, Redis and Docker —
saturating every core makes the proctor's feed stutter and can stall
WebSocket traffic, which matters more during an exam than finishing a code
run a second sooner. Raise it only if code execution is the bottleneck and
proctoring is unaffected.

> **On GPUs:** there is nothing to enable. The face models are tiny (~230 KB)
> and run at ~100 fps on CPU while being called once per student every 25
> seconds, so inference is nowhere near a bottleneck. Benchmarked on this
> hardware, routing them through the GPU via OpenCL was *slightly slower*
> than CPU — transfer overhead exceeds the saving at this model size. CUDA
> is not available at all on Apple Silicon, and the stock
> `opencv-python-headless` wheel is CPU-only regardless of platform.

---

## 7. Run the application

From the project root:

- **macOS / Linux:**
  ```bash
  ./mvnw spring-boot:run
  ```
- **Windows:**
  ```cmd
  mvnw.cmd spring-boot:run
  ```

On a successful start you'll see `Started LabsApplication in ... seconds` in
the log. The app is now listening on **http://localhost:8080**, and it has
automatically started the Python identity-verification service alongside
itself (you'll see a `[PythonService] ... starting on port 8500` log line).

To stop: `Ctrl+C` in the terminal it's running in.

---

## 8. First login — becoming Super Admin

1. Open **http://localhost:8080/admin/adminlogin.html**.
2. Use the OTP login option and enter the exact email you set as
   `SUPERADMIN_EMAIL`. The account is created automatically on first request.
3. Check that inbox for a one-time code (sent via Brevo) and enter it.
4. You're now logged in as Super Admin.

There is no separate "sign up" step for the Super Admin — the email in your
`.env` file *is* the Super Admin identity.

---

## 9. Proctoring & integrity features

### What the system detects

Every signal below is **recorded server-side** as it happens, pushed live to
the assigned proctor, and included in that student's violation report.

| Signal | What triggers it |
|---|---|
| **Different face** | The person on camera doesn't match the face enrolled at identity verification. **Two occurrences block the test** pending proctor review |
| **Multiple faces** | More than one person visible in frame |
| **Looking away** | Head turned away from the screen across consecutive checks (a single glance is ignored) |
| **Tab switch** | Switched browser tabs or minimised the window |
| **App switch** | Another application took focus over the exam window while it was still on screen |
| **Copy / paste** | Clipboard or right-click use outside the code editor, or pasting content that didn't originate inside it |
| **Screenshot** | Screen-capture keyboard shortcuts pressed |

Face checks run every 25 seconds against the enrolled face embedding, using
the same OpenCV models as identity verification — no extra model to install.

Leaving fullscreen is separately **enforced** during the exam (the student is
pushed back into fullscreen and blocked from continuing until they are), but
it is not currently written to the violation record as its own event.

### Liveness check (anti-spoofing)

Before a student can capture their enrollment photo, they must complete a
randomised head-turn challenge — **left, right, then face forward**, with the
order of the turns chosen server-side each time and an animated guide showing
the motion.

A printed photo or a picture on a phone would have to be physically rotated
to three distinct angles in an order it can't know in advance. The sequence
and the student's progress live in Redis rather than the page, and
enrollment is refused server-side without a completed challenge, so editing
the page achieves nothing.

### Copy/paste rules

Copy, cut, paste and right-click are blocked everywhere on the exam page.
**Inside the code editor**, copy/cut work normally and paste is allowed
*only* when the pasted text is exactly what the student last copied from that
same editor — so they can rearrange their own code, but can't paste in a
solution from another tab, an AI tool, or a file.

### Desktop only

The exam refuses to run on phones and tablets — a full-screen notice tells
the student to switch to a desktop. Enforced both on the page (minimum
1024×600, touch-primary devices rejected) and in `/system-check` on the
server, which withholds all question content until a device passes.

### Violation reports

Every row on the **Submissions** page has a **Report** button showing counts
for each violation type plus a full chronological event timeline, and a
**Download PDF** button producing a filed-and-emailable document with the
student's details, per-type counts (including zeroes), the timeline, and a
note on what browser-based proctoring cannot see.

### Code similarity / plagiarism detection

**Super Admin → Similarity Report** (`admin-plagiarism.html`) compares every
pair of coding answers within a year/domain and flags near-identical
submissions, with a side-by-side code view.

It uses winnowing fingerprints (the approach behind MOSS) rather than string
comparison, so a copy still registers after variables are renamed, comments
are changed, or the code is reformatted. Short answers are excluded — a
one-line solution is identical between honest students too.

> Scores are **advisory, not a verdict.** Always read the two submissions
> side by side before acting on one.

### Limits worth being honest about

Browser-based proctoring cannot see outside the exam window. It will **not**
detect a second device, a person sitting off-camera, notes on the desk, or an
overlay window that never takes focus. The webcam signals are the real
backstop for those, and a human proctor reviewing the evidence is the final
word.

---

## 10. Day-to-day usage

### As Super Admin
- **Manage Admins** (`admin-admins.html`) — invite ordinary admins. Each new
  admin's password is auto-generated and emailed to them via Brevo.
- **Assign proctoring scope** — on the same page, tick which exam domains
  (e.g. "Web Dev", "AIML") each admin can proctor, and a capacity (10/20/30/40
  students at a time). The system guarantees no student is ever watched by two
  proctors simultaneously.
- **Delete a submission** — only Super Admin can permanently erase a student's
  submission (Submissions page → trash icon). This cannot be undone.
- **Similarity Report** (`admin-plagiarism.html`) — flag near-identical coding
  submissions across the cohort. Super Admin only, because it compares across
  the per-proctor student boundaries the rest of the app keeps.

### As Admin (Proctor)
- **Add Problem** (`admin-add-problem.html`) — add questions one at a time, or
  bulk-upload a CSV (template downloadable from the page).
- **Live Proctoring** (`admin-live.html`) — see webcam snapshots from your
  assigned students in real time, open a full-size live video feed, talk to a
  student, approve/reject unblock requests, and get alerted on every signal
  listed in §9.
- **Blocked Students** (`admin-blocked.html`) — see everyone currently locked
  out for proctoring violations and unblock them if warranted.
- **Submissions** (`admin-submissions.html`) — review and grade completed
  exams, open a per-student **violation report**, and download it as a PDF.

### As Student
- Log in at **http://localhost:8080/** with the credentials emailed on
  account creation. **Use a desktop or laptop** — phones and tablets are
  refused (§9).
- Complete the **liveness challenge** (turn left, right, then face forward),
  then capture the identity photo and ID card — this calls the Python service
  set up in §5.
- Complete the hardware/browser check, then work through exam sections in
  order — MCQ and coding questions, run/test code in-browser, submit each
  section to unlock the next.
- Proctoring runs throughout; warnings appear on-screen when a violation is
  recorded, and repeated face mismatches block the test until a proctor
  releases it.

### Creating student accounts
Super Admin or Admin → Manage Students page → add one at a time, or bulk
upload a CSV of names/emails. Each gets a random password emailed to them.

---

## 11. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| App won't start: `Failed to load ApplicationContext` mentioning MongoDB | Mongo isn't running / wrong `MONGODB_URI` | Confirm Mongo is up and the URI in `.env` is correct |
| App won't start: `Illegal base64 character` | `JWT_SECRET` contains characters outside the Base64 alphabet | Regenerate with `openssl rand -hex 32` |
| Live video and Talk never connect, but snapshots work | No TURN relay configured | Set `CF_TURN_KEY_ID` / `CF_TURN_API_TOKEN` (§4) |
| A UI feature silently does nothing, no server error | A resource blocked by the Content-Security-Policy | Open browser devtools and look for CSP violation messages; add the host to the matching directive in `SecurityConfig.java` |
| "Student with this email already exists" but the list is empty | The address belongs to an account with a different role (or none) | The error names the actual role — check Manage Admins. A role-less orphan can be cleared by a Super Admin from the students page |
| Identity photos show a broken-image icon | CSP `img-src` doesn't allow the R2 host | Check `img-src` in `SecurityConfig.java` |
| Students can't type in the code editor | CSP `style-src` doesn't allow CodeMirror's stylesheet host | Run `ContentSecurityPolicyCoversStaticAssetsTest` — it names the file and directive |
| Liveness challenge never appears | Serving a stale page or stale classes | Restart the app; if it persists, `rm -rf target/classes` and rebuild |
| A flood of `cannot find symbol` getter errors on build | One annotation error aborted Lombok processing — the getters are fine | Scroll to the **first** error; fix that one |
| App starts but logs `[R2] cloudflare.r2.access-key/secret-key are not set` | R2 env vars missing | Fine for local testing; fill in `.env` before onboarding real users — photo upload won't work until you do |
| Admins/students never receive their login email | Brevo not configured, or sender email not verified in Brevo | Check `BREVO_API_KEY`/`BREVO_SENDER_EMAIL`, and that the sender is verified in your Brevo account |
| Code Run/Test always fails instantly | Docker isn't running, or the three sandbox images (§6) haven't been built | Start Docker Desktop / Docker Engine, then run the `docker build` commands |
| Identity verification stuck on "service unavailable" | Python setup (§5) wasn't run, or the venv is missing | Re-run `setup.sh` (or the manual Windows steps) in `python-services/identity-verification/` |
| Windows: `setup.sh` won't run | Double-clicking or running from Command Prompt doesn't work — it's a bash script | Run it from **Git Bash**, or follow the manual PowerShell steps in §5 |

---

## 12. A note on security

This project has been through a security review covering authentication,
authorization, injection, the code-execution sandbox, and the identity
verification service. Things worth keeping in mind operationally:

- **Never set `SANDBOX_ENABLED=false` outside of local debugging** — it
  removes all isolation between student code and the host server.
- **Generate a real, random `JWT_SECRET` for every environment** (never reuse
  the placeholder from `.env.example`), and never commit `.env` to version
  control (it's already git-ignored).
- **Keep MongoDB and Redis bound to localhost.** Neither should ever be
  reachable from the internet.
- **The app sends a strict Content-Security-Policy.** A blocked resource
  produces *no server-side error* — the browser just silently refuses it — so
  if you add a new CDN script, stylesheet or image host, add it to the
  matching directive in `SecurityConfig.java`. The test
  `ContentSecurityPolicyCoversStaticAssetsTest` scans every page and fails the
  build if a `<script>` or `<link rel=stylesheet>` host isn't permitted.
- **Client-side proctoring checks are defence in depth, not the gate.** Every
  rule that matters (desktop-only, liveness, hardware validation, section
  order, scoring) is re-checked server-side, because page JavaScript can be
  edited by the student.

### Running the tests

```bash
./mvnw test
```

Requires MongoDB and Redis running locally; Docker is needed for the
code-execution tests. If a build behaves oddly after pulling changes, clear
the stale compiler output first — Maven's incremental compile can skip a
changed file:

```bash
rm -rf target/classes && ./mvnw compile
```
