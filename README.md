# Postfix SMTP 서버 구축 실무 가이드

> **대상 OS**: Ubuntu 24.04 LTS  
> **작성일**: 2026-04-10  
> **도메인 예시**: `mail.example.com` (실제 도메인으로 교체 필요)  
> **서버 IP 예시**: `203.0.113.10` (실제 공인 IP로 교체 필요)  
> **내부 네트워크 예시**: `192.168.0.0/24`

---

## 목차

1. [아키텍처 개요](#1-아키텍처-개요)
2. [사전 준비](#2-사전-준비)
3. [Postfix 설치](#3-postfix-설치)
4. [main.cf 핵심 설정](#4-maincf-핵심-설정)
5. [master.cf 포트 설정 (25 vs 587)](#5-mastercf-포트-설정-25-vs-587)
6. [SASL 인증 설정](#6-sasl-인증-설정)
7. [TLS 인증서 설정](#7-tls-인증서-설정)
8. [DNS 레코드 설정 (MX, PTR, SPF, DKIM, DMARC)](#8-dns-레코드-설정)
9. [DKIM 서명 설정 (OpenDKIM)](#9-dkim-서명-설정-opendkim)
10. [방화벽 설정 (ufw)](#10-방화벽-설정-ufw)
11. [테스트](#11-테스트)
12. [로그 확인 및 모니터링](#12-로그-확인-및-모니터링)
13. [실무 트러블슈팅](#13-실무-트러블슈팅)

---

## 1. 아키텍처 개요

```
[내부 서버/앱]              [외부 발신자]
192.168.0.0/24              (Gmail 등)
      |                         |
      | 인증 없이 발신           | 포트 25 (MX 수신)
      v                         v
  +-----------------------------------+
  |   Postfix (mail.example.com)      |
  |   포트 25  : MX 수신 + 내부 릴레이 |
  |   포트 587 : 인증 발신 (STARTTLS)  |
  +-----------------------------------+
      |
      | 포트 25 (STARTTLS)
      v
  [외부 수신 서버 - Gmail, Naver 등]
```

**포트 25 vs 587 차이점**:

| 항목 | 포트 25 (SMTP) | 포트 587 (Submission) |
|------|---------------|---------------------|
| 용도 | 서버 간 메일 전송 (MX 수신) | 클라이언트 → 서버 발신용 |
| 인증 | 보통 불필요 (서버 간) | SASL 인증 필수 |
| 암호화 | 선택적 STARTTLS | STARTTLS 필수 |
| ISP 차단 | 자주 차단됨 | 보통 허용 |
| 사용 주체 | 다른 메일 서버 | Thunderbird, 앱 등 |

---

## 2. 사전 준비

### 2.1 호스트명 설정

```bash
# 호스트명을 FQDN으로 설정 (PTR 레코드와 일치시켜야 함)
sudo hostnamectl set-hostname mail.example.com

# /etc/hosts 편집
sudo tee -a /etc/hosts <<EOF
203.0.113.10   mail.example.com mail
EOF
```

### 2.2 시스템 업데이트

```bash
sudo apt update && sudo apt upgrade -y
```

### 2.3 사전 확인 사항

- **공인 IP**: 고정 IP 필요 (VPS 또는 자체 서버)
- **ISP 포트 25 차단 여부**: 일부 ISP/클라우드(AWS, GCP 등)는 포트 25를 기본 차단. 해제 요청 필요
- **도메인**: DNS 관리 권한 필요
- **Reverse DNS(PTR)**: 호스팅 업체에 PTR 레코드 설정 요청 (203.0.113.10 → mail.example.com)

---

## 3. Postfix 설치

```bash
# Postfix + 관련 패키지 설치
sudo apt install -y postfix mailutils

# 설치 중 선택 화면:
# - General type of mail configuration: "Internet Site" 선택
# - System mail name: "example.com" 입력 (도메인만, mail. 제외)
```

설치 확인:

```bash
postconf mail_version
# 출력 예: mail_version = 3.8.x

sudo systemctl status postfix
```

---

## 4. main.cf 핵심 설정

기존 설정을 백업한 후 교체합니다.

```bash
# 백업
sudo cp /etc/postfix/main.cf /etc/postfix/main.cf.bak
```

아래 내용을 `/etc/postfix/main.cf`에 작성합니다. 각 설정의 의미를 주석으로 설명합니다.

```bash
sudo tee /etc/postfix/main.cf <<'EOF'
# ============================================================
# 기본 설정
# ============================================================
# smtpd_banner: 외부에 버전 정보 노출 방지
smtpd_banner = $myhostname ESMTP
biff = no
append_dot_mydomain = no
readme_directory = no

# ============================================================
# 호스트/도메인 설정
# ============================================================
# myhostname: FQDN. PTR 레코드와 반드시 일치해야 함
myhostname = mail.example.com

# mydomain: 메일 도메인 (@ 뒤에 붙는 값)
mydomain = example.com

# myorigin: 발신 메일의 @도메인
myorigin = $mydomain

# mydestination: 이 서버가 최종 수신지인 도메인 목록
# 여기 등록된 도메인으로 오는 메일은 로컬 사서함에 배달됨
mydestination = $myhostname, $mydomain, localhost.$mydomain, localhost

# ============================================================
# 네트워크 설정
# ============================================================
# inet_interfaces: 모든 인터페이스에서 수신 (외부 메일 수신 위해 필수)
inet_interfaces = all
inet_protocols = ipv4

# mynetworks: 인증 없이 릴레이 허용할 IP 대역
# 주의: 여기 등록된 IP는 SMTP 인증 없이 메일 발신 가능
# 오픈 릴레이 방지를 위해 반드시 신뢰할 수 있는 IP만 등록
mynetworks = 127.0.0.0/8 192.168.0.0/24

# ============================================================
# 수신 제한 (오픈 릴레이 방지 핵심)
# ============================================================
# smtpd_recipient_restrictions: 메일 수신/릴레이 허용 조건을 순서대로 평가
smtpd_recipient_restrictions =
    permit_mynetworks,
    permit_sasl_authenticated,
    reject_unauth_destination,
    reject_unknown_reverse_client_hostname,
    reject_unauth_pipelining

# 설명:
# 1) permit_mynetworks: mynetworks에 등록된 IP는 무조건 허용 (인증 불필요)
# 2) permit_sasl_authenticated: SASL 인증 성공한 사용자 허용
# 3) reject_unauth_destination: 위 두 조건에 해당하지 않으면,
#    mydestination에 없는 도메인으로의 릴레이를 거부 → 오픈 릴레이 차단
# 4) reject_unknown_reverse_client_hostname: PTR 레코드 없는 클라이언트 거부
# 5) reject_unauth_pipelining: 비정상 SMTP 명령 순서 거부 (스팸봇 차단)

# smtpd_relay_restrictions (Postfix 2.10+): 릴레이 전용 제한
# smtpd_recipient_restrictions와 동일하게 설정하되, 이쪽이 먼저 평가됨
smtpd_relay_restrictions =
    permit_mynetworks,
    permit_sasl_authenticated,
    reject_unauth_destination

# ============================================================
# 발신 제한
# ============================================================
smtpd_sender_restrictions =
    reject_unknown_sender_domain,
    reject_non_fqdn_sender

smtpd_helo_restrictions =
    reject_invalid_helo_hostname,
    reject_non_fqdn_helo_hostname

# HELO 필수 요구
smtpd_helo_required = yes

# ============================================================
# TLS 설정 (Let's Encrypt 인증서 사용 가정)
# ============================================================
# 수신 시 TLS (다른 서버가 이 서버에 접속할 때)
smtpd_tls_cert_file = /etc/letsencrypt/live/mail.example.com/fullchain.pem
smtpd_tls_key_file = /etc/letsencrypt/live/mail.example.com/privkey.pem
smtpd_tls_security_level = may
smtpd_tls_loglevel = 1
smtpd_tls_received_header = yes
smtpd_tls_protocols = >=TLSv1.2

# 발신 시 TLS (이 서버가 외부 서버에 접속할 때)
smtp_tls_security_level = may
smtp_tls_loglevel = 1
smtp_tls_protocols = >=TLSv1.2

# TLS 세션 캐시
smtpd_tls_session_cache_database = btree:${data_directory}/smtpd_scache
smtp_tls_session_cache_database = btree:${data_directory}/smtp_scache

# ============================================================
# SASL 인증 설정 (Dovecot SASL 사용)
# ============================================================
smtpd_sasl_auth_enable = yes
smtpd_sasl_type = dovecot
smtpd_sasl_path = private/auth
smtpd_sasl_security_options = noanonymous
smtpd_sasl_local_domain = $myhostname
broken_sasl_auth_clients = yes

# ============================================================
# 메일 크기 제한
# ============================================================
# 50MB (바이트 단위)
message_size_limit = 52428800
mailbox_size_limit = 0

# ============================================================
# 메일박스 형식
# ============================================================
home_mailbox = Maildir/

# ============================================================
# DKIM 연동 (OpenDKIM milter)
# ============================================================
milter_protocol = 6
milter_default_action = accept
smtpd_milters = inet:localhost:8891
non_smtpd_milters = $smtpd_milters

# ============================================================
# 기타 보안
# ============================================================
# 수신 속도 제한 (DoS 방지)
smtpd_client_connection_rate_limit = 30
smtpd_client_message_rate_limit = 60
smtpd_error_sleep_time = 5s
smtpd_soft_error_limit = 3
smtpd_hard_error_limit = 10

# VRFY 명령 비활성화 (사용자 열거 방지)
disable_vrfy_command = yes

# 호환성 레벨
compatibility_level = 3.6
EOF
```

설정 적용:

```bash
# 문법 검사
sudo postfix check

# 적용
sudo systemctl restart postfix
```

---

## 5. master.cf 포트 설정 (25 vs 587)

`/etc/postfix/master.cf`에서 submission(587) 포트를 활성화합니다.

```bash
sudo cp /etc/postfix/master.cf /etc/postfix/master.cf.bak
```

아래 라인을 찾아 주석을 해제하고 수정합니다:

```bash
sudo tee -a /etc/postfix/master.cf <<'EOF'

# ==========================================================
# Submission 포트 (587) - 클라이언트 발신용
# ==========================================================
submission inet n       -       y       -       -       smtpd
  -o syslog_name=postfix/submission
  -o smtpd_tls_security_level=encrypt
  -o smtpd_sasl_auth_enable=yes
  -o smtpd_tls_auth_only=yes
  -o smtpd_reject_unlisted_recipient=no
  -o smtpd_recipient_restrictions=permit_sasl_authenticated,reject
  -o milter_macro_daemon_name=ORIGINATING
EOF
```

**587 포트 설정 설명**:

- `smtpd_tls_security_level=encrypt`: TLS 암호화 필수
- `smtpd_sasl_auth_enable=yes`: SASL 인증 필수
- `smtpd_tls_auth_only=yes`: TLS 연결에서만 인증 허용 (평문 인증 방지)
- `smtpd_recipient_restrictions=permit_sasl_authenticated,reject`: 인증된 사용자만 허용, 나머지 거부

```bash
sudo systemctl restart postfix
```

---

## 6. SASL 인증 설정

Dovecot을 SASL 인증 백엔드로 사용합니다.

### 6.1 Dovecot 설치

```bash
sudo apt install -y dovecot-core dovecot-imapd
```

### 6.2 Dovecot 인증 소켓 설정

```bash
sudo tee /etc/dovecot/conf.d/10-master.conf.local <<'EOF'
service auth {
  unix_listener /var/spool/postfix/private/auth {
    mode = 0660
    user = postfix
    group = postfix
  }
}
EOF
```

> **참고**: 실제 환경에서는 `/etc/dovecot/conf.d/10-master.conf` 파일 내 `service auth` 블록을 직접 수정하는 것이 더 안전합니다. 위 `.local` 파일은 Dovecot 설정 구조에 따라 적용되지 않을 수 있으므로, 기존 파일을 편집하세요.

### 6.3 인증 방식 설정

```bash
# /etc/dovecot/conf.d/10-auth.conf 에서 아래 항목 확인/수정
sudo sed -i 's/^#disable_plaintext_auth.*/disable_plaintext_auth = yes/' /etc/dovecot/conf.d/10-auth.conf
sudo sed -i 's/^auth_mechanisms.*/auth_mechanisms = plain login/' /etc/dovecot/conf.d/10-auth.conf
```

### 6.4 시스템 사용자 생성 (테스트용)

```bash
# 메일 발신 테스트용 사용자 추가
sudo adduser --disabled-login --gecos "Mail User" mailuser
sudo passwd mailuser
# 비밀번호 입력 (예: SecurePass123!)
```

### 6.5 Dovecot 재시작

```bash
sudo systemctl restart dovecot
sudo systemctl enable dovecot
```

---

## 7. TLS 인증서 설정 (Let's Encrypt)

```bash
# Certbot 설치
sudo apt install -y certbot

# 인증서 발급 (standalone 모드 - 80 포트 사용)
# 주의: 웹 서버가 80 포트를 사용 중이면 잠시 중지하거나 webroot 모드 사용
sudo certbot certonly --standalone -d mail.example.com

# 자동 갱신 테스트
sudo certbot renew --dry-run
```

인증서 갱신 후 Postfix 자동 재시작 설정:

```bash
sudo tee /etc/letsencrypt/renewal-hooks/post/postfix-restart.sh <<'EOF'
#!/bin/bash
systemctl restart postfix
systemctl restart dovecot
EOF

sudo chmod +x /etc/letsencrypt/renewal-hooks/post/postfix-restart.sh
```

---

## 8. DNS 레코드 설정

DNS 관리 콘솔(Cloudflare, AWS Route53, 가비아 등)에서 아래 레코드를 등록합니다.

### 8.1 기본 레코드

```
; A 레코드 - 메일 서버 IP
mail.example.com.    IN  A      203.0.113.10

; MX 레코드 - 메일 수신 서버 지정
; 우선순위 10 (숫자가 낮을수록 높은 우선순위)
example.com.         IN  MX     10 mail.example.com.
```

### 8.2 Reverse DNS (PTR 레코드)

PTR 레코드는 호스팅/ISP 업체에 요청하여 설정합니다. 직접 DNS에 등록하는 것이 아닙니다.

```
; PTR 레코드 (호스팅 업체에 설정 요청)
; 203.0.113.10 → mail.example.com
; 이것이 없으면 Gmail 등에서 스팸 처리 확률이 매우 높음
10.113.0.203.in-addr.arpa.  IN  PTR  mail.example.com.
```

**확인 방법**:

```bash
dig -x 203.0.113.10 +short
# 출력: mail.example.com.
```

### 8.3 SPF (Sender Policy Framework)

이 도메인에서 메일을 보낼 수 있는 서버를 지정합니다.

```
; SPF 레코드 - TXT 레코드로 등록
example.com.  IN  TXT  "v=spf1 mx ip4:203.0.113.10 -all"
```

| 항목 | 의미 |
|------|------|
| `v=spf1` | SPF 버전 1 |
| `mx` | MX 레코드에 지정된 서버 허용 |
| `ip4:203.0.113.10` | 해당 IP 허용 |
| `-all` | 위 조건 외 모든 서버 **거부** (hard fail) |

> **주의**: `~all`(soft fail)은 테스트용, 운영에서는 반드시 `-all`(hard fail) 사용

### 8.4 DKIM (DomainKeys Identified Mail)

DKIM 공개키는 OpenDKIM 설정(9단계) 후 생성되는 값을 등록합니다.

```
; DKIM 공개키 - TXT 레코드로 등록
; selector: "mail" 사용 (설정에 따라 변경)
mail._domainkey.example.com.  IN  TXT  "v=DKIM1; k=rsa; p=MIIBIjANBgkqh...(공개키)..."
```

### 8.5 DMARC (Domain-based Message Authentication, Reporting & Conformance)

SPF, DKIM 인증 실패 시 수신 서버가 취할 정책을 지정합니다.

```
; DMARC 레코드
_dmarc.example.com.  IN  TXT  "v=DMARC1; p=quarantine; rua=mailto:dmarc-reports@example.com; ruf=mailto:dmarc-forensic@example.com; pct=100; adkim=r; aspf=r"
```

| 항목 | 의미 |
|------|------|
| `p=quarantine` | 인증 실패 메일을 스팸함으로 이동. `reject`는 완전 거부 |
| `rua=mailto:...` | 집계 보고서 수신 주소 |
| `ruf=mailto:...` | 포렌식(상세) 보고서 수신 주소 |
| `pct=100` | 정책 적용 비율 (100%) |
| `adkim=r` | DKIM 정렬 모드: relaxed |
| `aspf=r` | SPF 정렬 모드: relaxed |

> **단계적 적용 권장**: 처음에는 `p=none`으로 시작 → 보고서 확인 → `p=quarantine` → `p=reject` 순서로 강화

---

## 9. DKIM 서명 설정 (OpenDKIM)

### 9.1 설치

```bash
sudo apt install -y opendkim opendkim-tools
```

### 9.2 키 생성

```bash
# 디렉토리 생성
sudo mkdir -p /etc/opendkim/keys/example.com

# 키 생성 (selector: mail)
sudo opendkim-genkey -b 2048 -d example.com -D /etc/opendkim/keys/example.com -s mail -v

# 권한 설정
sudo chown -R opendkim:opendkim /etc/opendkim/keys/
sudo chmod 600 /etc/opendkim/keys/example.com/mail.private
```

### 9.3 공개키 확인 (DNS에 등록할 값)

```bash
sudo cat /etc/opendkim/keys/example.com/mail.txt
# 출력된 TXT 레코드 값을 DNS에 등록
```

### 9.4 OpenDKIM 설정

```bash
sudo tee /etc/opendkim.conf <<'EOF'
AutoRestart             Yes
AutoRestartRate         10/1h
Syslog                  yes
SyslogSuccess           yes
LogWhy                  yes

Canonicalization        relaxed/simple
ExternalIgnoreList      refile:/etc/opendkim/TrustedHosts
InternalHosts           refile:/etc/opendkim/TrustedHosts
KeyTable                refile:/etc/opendkim/KeyTable
SigningTable             refile:/etc/opendkim/SigningTable

Mode                    sv
PidFile                 /run/opendkim/opendkim.pid
SignatureAlgorithm      rsa-sha256
UserID                  opendkim:opendkim
Socket                  inet:8891@localhost
OversignHeaders         From
EOF
```

### 9.5 테이블 파일 생성

```bash
# SigningTable: 어떤 도메인을 어떤 키로 서명할지
sudo tee /etc/opendkim/SigningTable <<'EOF'
*@example.com    mail._domainkey.example.com
EOF

# KeyTable: 키 파일 위치
sudo tee /etc/opendkim/KeyTable <<'EOF'
mail._domainkey.example.com    example.com:mail:/etc/opendkim/keys/example.com/mail.private
EOF

# TrustedHosts: 서명 대상 호스트
sudo tee /etc/opendkim/TrustedHosts <<'EOF'
127.0.0.1
localhost
192.168.0.0/24
mail.example.com
EOF
```

### 9.6 서비스 시작

```bash
sudo systemctl restart opendkim
sudo systemctl enable opendkim
sudo systemctl restart postfix
```

### 9.7 DKIM 설정 검증

```bash
# DNS에 공개키 등록 후 확인
dig mail._domainkey.example.com TXT +short

# 테스트
sudo opendkim-testkey -d example.com -s mail -vvv
# "key OK" 출력되면 정상
```

---

## 10. 방화벽 설정 (ufw)

```bash
# ufw 활성화
sudo ufw enable

# SSH (기존 접속 유지 위해 먼저 허용)
sudo ufw allow 22/tcp comment 'SSH'

# SMTP (포트 25) - 외부 메일 수신 및 서버 간 통신
sudo ufw allow 25/tcp comment 'SMTP'

# Submission (포트 587) - 인증된 클라이언트 발신
sudo ufw allow 587/tcp comment 'SMTP Submission'

# IMAP (메일 클라이언트 수신용, 필요시)
sudo ufw allow 993/tcp comment 'IMAPS'

# HTTP/HTTPS (Let's Encrypt 인증서 발급/갱신용)
sudo ufw allow 80/tcp comment 'HTTP for Certbot'
sudo ufw allow 443/tcp comment 'HTTPS'

# 상태 확인
sudo ufw status verbose
```

**출력 예시**:

```
Status: active

To                         Action      From
--                         ------      ----
22/tcp                     ALLOW       Anywhere    # SSH
25/tcp                     ALLOW       Anywhere    # SMTP
587/tcp                    ALLOW       Anywhere    # SMTP Submission
993/tcp                    ALLOW       Anywhere    # IMAPS
80/tcp                     ALLOW       Anywhere    # HTTP for Certbot
```

---

## 11. 테스트

### 11.1 오픈 릴레이 테스트 (필수!)

```bash
# 외부에서 릴레이 시도 - 반드시 거부되어야 함
# 외부 서버 또는 다른 네트워크에서 실행
telnet mail.example.com 25
EHLO test.com
MAIL FROM:<spammer@evil.com>
RCPT TO:<victim@gmail.com>
# 예상 응답: 554 5.7.1 <victim@gmail.com>: Relay access denied
QUIT
```

> **오픈 릴레이가 확인되면 즉시 서버를 중지하고 설정을 검토하세요.** 오픈 릴레이 서버는 수 시간 내에 블랙리스트에 등록됩니다.

### 11.2 swaks를 이용한 발신 테스트

```bash
# swaks 설치
sudo apt install -y swaks

# 테스트 1: 내부 네트워크(192.168.0.x)에서 인증 없이 발신
swaks \
  --to recipient@gmail.com \
  --from sender@example.com \
  --server mail.example.com \
  --port 25 \
  --ehlo test.local

# 테스트 2: 587 포트 SASL 인증 발신
swaks \
  --to recipient@gmail.com \
  --from sender@example.com \
  --server mail.example.com \
  --port 587 \
  --auth LOGIN \
  --auth-user mailuser \
  --auth-password 'SecurePass123!' \
  --tls

# 테스트 3: 로컬에서 외부로 발신 (command line)
echo "테스트 메일 본문입니다." | mail -s "Postfix 테스트" recipient@gmail.com
```

### 11.3 telnet을 이용한 수동 테스트

```bash
telnet mail.example.com 25
```

```
220 mail.example.com ESMTP
EHLO test.local
250-mail.example.com
250-STARTTLS
250-AUTH PLAIN LOGIN
...
MAIL FROM:<sender@example.com>
250 2.1.0 Ok
RCPT TO:<recipient@gmail.com>
250 2.1.5 Ok
DATA
354 End data with <CR><LF>.<CR><LF>
Subject: Test Email
From: sender@example.com
To: recipient@gmail.com

This is a test email from Postfix.
.
250 2.0.0 Ok: queued as ABC123
QUIT
221 2.0.0 Bye
```

### 11.4 메일 수신 테스트

외부(Gmail 등)에서 `user@example.com`으로 메일을 보내고 서버에서 확인:

```bash
# Maildir 확인
ls -la /home/mailuser/Maildir/new/

# 또는 mail 명령어
sudo -u mailuser mail
```

### 11.5 DNS 레코드 검증

```bash
# MX 레코드 확인
dig example.com MX +short

# SPF 레코드 확인
dig example.com TXT +short

# DKIM 레코드 확인
dig mail._domainkey.example.com TXT +short

# DMARC 레코드 확인
dig _dmarc.example.com TXT +short

# PTR (Reverse DNS) 확인
dig -x 203.0.113.10 +short
```

### 11.6 외부 검증 도구

아래 사이트에서 종합 검증 가능:

- **MXToolbox**: https://mxtoolbox.com/ — MX, SPF, DKIM, DMARC, 블랙리스트 검사
- **mail-tester.com**: https://www.mail-tester.com/ — 메일 발송 후 스팸 점수 확인 (10점 만점)
- **DKIM Validator**: https://dkimvalidator.com/ — DKIM 서명 검증

---

## 12. 로그 확인 및 모니터링

### 12.1 주요 로그 파일

```bash
# Postfix 메일 로그 (발신/수신 모든 기록)
sudo tail -f /var/log/mail.log

# 에러 로그만 확인
sudo tail -f /var/log/mail.err

# syslog에서 postfix 관련만 필터링
sudo journalctl -u postfix -f
```

### 12.2 유용한 로그 분석 명령어

```bash
# 발신 성공 건수
grep "status=sent" /var/log/mail.log | wc -l

# 발신 실패(bounce) 건수
grep "status=bounced" /var/log/mail.log | wc -l

# 거부된 릴레이 시도 (오픈 릴레이 공격 시도)
grep "Relay access denied" /var/log/mail.log | wc -l

# 특정 메일 ID 추적
grep "ABC123" /var/log/mail.log

# 메일 큐 확인
sudo postqueue -p

# 큐에 쌓인 메일 강제 발송
sudo postqueue -f

# 큐 비우기 (주의: 모든 대기 메일 삭제)
sudo postsuper -d ALL
```

### 12.3 로그 로테이션 확인

```bash
cat /etc/logrotate.d/rsyslog
# mail.log는 기본적으로 주 단위 로테이션됨
```

---

## 13. 실무 트러블슈팅

### 13.1 Gmail로 보낸 메일이 스팸함에 들어가는 경우

**원인 및 해결**:

| 순서 | 확인 항목 | 해결 방법 |
|------|----------|----------|
| 1 | PTR 레코드 미설정 | 호스팅 업체에 PTR 설정 요청 |
| 2 | SPF 레코드 없음/오류 | `dig example.com TXT`로 확인 후 수정 |
| 3 | DKIM 미적용 | OpenDKIM 설정 확인, 헤더에 `DKIM-Signature` 존재 여부 확인 |
| 4 | DMARC 미설정 | `_dmarc.example.com` TXT 레코드 추가 |
| 5 | IP 블랙리스트 등록 | MXToolbox에서 확인, 해당 블랙리스트에 해제 요청 |
| 6 | 메일 내용 | HTML 메일에 단축 URL, 과도한 이미지, 특정 키워드 포함 여부 |

```bash
# Gmail에서 원본 보기 (메일 열기 → 점 세 개 → 원본 보기)
# SPF: PASS, DKIM: PASS, DMARC: PASS 모두 확인
```

### 13.2 IP 블랙리스트 등록 시

```bash
# 블랙리스트 확인
# MXToolbox (https://mxtoolbox.com/blacklists.aspx) 에서 서버 IP 조회

# 주요 블랙리스트 직접 조회
dig +short 10.113.0.203.zen.spamhaus.org
# NXDOMAIN이면 등록되지 않은 것 (정상)
# 127.0.0.x 형태 응답이면 블랙리스트에 등록된 것
```

**해제 절차**:
1. 스팸 발송 원인 제거 (오픈 릴레이, 감염된 계정 등)
2. 해당 블랙리스트 사이트에서 해제 요청 (Spamhaus, Barracuda 등)
3. 해제까지 24~72시간 소요

### 13.3 "Connection timed out" 발신 실패

```bash
# 포트 25 아웃바운드 차단 여부 확인
telnet gmail-smtp-in.l.google.com 25

# 클라우드 환경(AWS, GCP, Azure)은 기본적으로 포트 25 아웃바운드 차단
# AWS: SES 사용 권장 또는 포트 25 제한 해제 요청
# GCP: 포트 25 완전 차단, 릴레이 서비스 사용 필요
```

### 13.4 "Relay access denied" 오류

```bash
# mynetworks 확인
postconf mynetworks

# 발신 서버 IP가 mynetworks에 포함되어 있는지 확인
# 포함되어 있지 않으면 SASL 인증 필요
```

### 13.5 큐에 메일이 계속 쌓이는 경우

```bash
# 큐 상태 확인
sudo postqueue -p

# 특정 메일 상세 확인
sudo postcat -q <QUEUE_ID>

# 특정 수신자로의 메일만 삭제
sudo postqueue -p | grep "recipient@" | awk '{print $1}' | \
  xargs -I {} sudo postsuper -d {}

# deferred 큐 강제 재시도
sudo postqueue -f
```

### 13.6 새 서버 IP의 평판 쌓기 (IP Warming)

신규 IP에서 대량 메일을 바로 발송하면 스팸으로 분류됩니다.

**권장 순서**:
1. 첫 주: 하루 50통 이하
2. 둘째 주: 하루 200통 이하
3. 셋째 주: 하루 1,000통 이하
4. 넷째 주 이후: 점진적 증가

**추가 권장 사항**:
- Google Postmaster Tools 등록 (https://postmaster.google.com/)
- 발신 메일에 List-Unsubscribe 헤더 포함
- 바운스 메일 즉시 처리 (존재하지 않는 주소로 반복 발송 금지)

---

## 부록: 설정 요약 체크리스트

```
[ ] 호스트명 FQDN 설정 (hostnamectl)
[ ] Postfix 설치 및 main.cf 설정
[ ] master.cf에서 submission(587) 포트 활성화
[ ] Dovecot SASL 인증 설정
[ ] Let's Encrypt TLS 인증서 발급
[ ] DNS - A 레코드 (mail.example.com)
[ ] DNS - MX 레코드 (example.com → mail.example.com)
[ ] DNS - PTR 레코드 (호스팅 업체 요청)
[ ] DNS - SPF TXT 레코드
[ ] OpenDKIM 설치 및 키 생성
[ ] DNS - DKIM TXT 레코드 (공개키 등록)
[ ] DNS - DMARC TXT 레코드
[ ] 방화벽 (ufw) 포트 개방
[ ] 오픈 릴레이 테스트 (필수!)
[ ] 발신 테스트 (swaks)
[ ] 수신 테스트
[ ] mail-tester.com 스팸 점수 확인
[ ] Google Postmaster Tools 등록
```

---

## 부록: 주요 설정 파일 경로 정리

| 파일 | 용도 |
|------|------|
| `/etc/postfix/main.cf` | Postfix 핵심 설정 |
| `/etc/postfix/master.cf` | 포트/서비스 설정 |
| `/etc/opendkim.conf` | OpenDKIM 설정 |
| `/etc/opendkim/KeyTable` | DKIM 키 매핑 |
| `/etc/opendkim/SigningTable` | DKIM 서명 대상 도메인 |
| `/etc/opendkim/TrustedHosts` | 서명 대상 호스트 |
| `/etc/dovecot/conf.d/10-master.conf` | Dovecot 인증 소켓 |
| `/etc/letsencrypt/live/mail.example.com/` | TLS 인증서 |
| `/var/log/mail.log` | 메일 로그 |
| `/var/log/mail.err` | 메일 에러 로그 |
