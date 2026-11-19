#!/data/data/com.termux/files/usr/bin/sh
# Run inside Termux from this folder:  sh setup.sh
# Installs the zsh prompt/title hooks and terminal colors (existing .zshrc is backed up).
set -e
cd "$(dirname "$0")"

[ -f "$HOME/.zshrc" ] && cp "$HOME/.zshrc" "$HOME/.zshrc.bak"
cp zshrc "$HOME/.zshrc"

mkdir -p "$HOME/.termux"
cp colors.properties "$HOME/.termux/colors.properties"

command -v zsh >/dev/null 2>&1 || pkg install -y zsh
termux-reload-settings 2>/dev/null || true

echo "Done. Run: exec zsh   (optional: chsh -s zsh)"
echo "Font: put SF Mono (your own licensed copy) at ~/.termux/font.ttf, then termux-reload-settings"
