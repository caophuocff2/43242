#!/bin/bash
echo -e "\e[1;32m==========================================\e[0m"
echo -e "\e[1;32m    PLUTO REJOIN - TERMUX CLIENT SETUP    \e[0m"
echo -e "\e[1;32m==========================================\e[0m"

echo "[*] Cap nhat Termux..."
pkg update -y > /dev/null 2>&1

echo "[*] Cai dat OpenJDK va Kotlin (co the mat vai phut)..."
pkg install openjdk-17 kotlin wget tsu -y

echo "[*] Tai xuong Tool Client (main.kts) tu GitHub..."
mkdir -p /storage/emulated/0/Download
# LƯU Ý: THAY ĐƯỜNG LINK RAW CỦA BẠN VÀO BÊN DƯỚI
wget -O /storage/emulated/0/Download/main.kts "https://raw.githubusercontent.com/caophuocff2/43242/refs/heads/main/main.kts"

echo -e "\e[1;32m[+] CAI DAT HOAN TAT!\e[0m"
echo -e "Ban co the chay luon bang lenh duoi day:"
echo ""
echo -e "\e[1;33msu -c \"export PATH=\$PATH:/data/data/com.termux/files/usr/bin && export TERM=xterm-256color && export TMPDIR=/data/data/com.termux/files/usr/tmp && export LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib && cd /storage/emulated/0/Download && kotlinc -script main.kts\"\e[0m"
echo "=========================================="
