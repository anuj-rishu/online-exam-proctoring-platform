# 🚀 Project Push Guide

This project uses a highly automated and secure push script (`push.sh`). Direct use of `git commit` and `git push` is blocked to ensure all code is properly compiled, tested, and semantically versioned before it reaches GitHub.

---

## 🛠️ 1. First-Time Setup (One-time only)

To make pushing code easy, you should set up the `spring push` alias for your machine. This allows you to push code from anywhere in the project by simply typing `spring push`.

### 🍎 Mac / 🐧 Linux (Zsh or Bash)
Open your terminal and paste the following command to register the alias:
```bash
# If using Zsh (Default on Mac)
echo 'spring() { if [ "$1" = "push" ]; then ./push.sh; else echo "Unknown spring command"; fi }' >> ~/.zshrc && source ~/.zshrc

# If using Bash (Default on many Linux distros)
echo 'spring() { if [ "$1" = "push" ]; then ./push.sh; else echo "Unknown spring command"; fi }' >> ~/.bashrc && source ~/.bashrc
```

### 🪟 Windows (Git Bash)
We highly recommend using **Git Bash** on Windows to run project scripts. Open Git Bash and run:
```bash
echo 'spring() { if [ "$1" = "push" ]; then ./push.sh; else echo "Unknown spring command"; fi }' >> ~/.bash_profile && source ~/.bash_profile
```

### 🪟 Windows (PowerShell)
If you prefer PowerShell, you can create a function in your profile:
```powershell
# Open your PowerShell profile
notepad $PROFILE

# Add this block to the bottom of the file and save:
function spring {
    param([string]$Action)
    if ($Action -eq "push") {
        bash ./push.sh
    } else {
        Write-Host "Unknown spring command"
    }
}
```
*Note: You must restart PowerShell after saving.*

---

## 🚀 2. How to Push Code (Daily Workflow)

Once you have made changes to the code and are ready to send them to GitHub, open your terminal and simply run:

```bash
spring push
```

*(Alternatively, if you skipped the setup step above, you can manually type `./push.sh` in the terminal).*

### What happens when you run it?
1. **Compiles Code**: Ensures Maven compiles successfully without errors.
2. **Builds Docker Sandboxes**: Ensures all 3 code-execution sandbox environments build correctly.
3. **Stages Changes**: Automatically runs `git add .`
4. **Versioning**: Prompts you to pick an update type:
   - `[1] Major`: Massive overhauls or breaking changes. (Creates a GitHub Release tag).
   - `[2] Minor`: New features added.
   - `[3] Patch`: Small bug fixes or typo tweaks.
5. **Commit Message**: Asks for an optional message to attach to the commit.
6. **Auto-Push**: Safely bypasses the security hooks and pushes your code!

---

## 🛑 3. Troubleshooting

**Error: "Direct 'git commit/push' is disabled for this project!"**
You tried to manually run `git commit` or `git push`. This is blocked! Use `spring push` or `./push.sh` instead.

**Error: "Permission denied: ./push.sh"**
Your system stripped the executable permissions from the script. Run this to fix it:
```bash
chmod +x push.sh
```
