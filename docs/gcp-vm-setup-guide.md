# GCP VM 기반 Spring Boot + Vector + Pub/Sub 파이프라인 구축 가이드

---

## 목차

1. [전체 구성도](#1-전체-구성도)
2. [사전 준비 (로컬 PC)](#2-사전-준비-로컬-pc)
3. [서비스 계정 생성 및 권한 부여](#3-서비스-계정-생성-및-권한-부여)
4. [GCP VM 생성](#4-gcp-vm-생성)
5. [VM 환경 설치](#5-vm-환경-설치)
6. [프로젝트 파일 VM 업로드](#6-프로젝트-파일-vm-업로드)
7. [Vector 설정 수정](#7-vector-설정-수정)
8. [ADC 인증 동작 확인](#8-adc-인증-동작-확인)
9. [Spring Boot 빌드 및 실행](#9-spring-boot-빌드-및-실행)
10. [Vector 실행](#10-vector-실행)
11. [API 호출 및 검증](#11-api-호출-및-검증)
12. [종료 및 정리](#12-종료-및-정리)
13. [포트 개방 (선택)](#13-포트-개방-선택)
14. [성공 체크리스트](#14-성공-체크리스트)
15. [트러블슈팅](#15-트러블슈팅)

---

## 1. 전체 구성도

```
[GCP VM - e2-medium]
  ├── Java 17 + Spring Boot  →  ~/demo-springboot/logs/app.log (JSON)
  └── Vector (file source)   →  remap (JSON parse)
                                        │
                            VM 메타데이터 서버 자동 ADC 인증
                            (서비스 계정 JSON 키 파일 불필요)
                                        │
                                        ▼
                            [GCP Pub/Sub Topic: vector-log-ingest]
                                        │
                                        ▼
                            [Subscription: vector-log-sub]
                                ← gcloud pull 로 검증
```

> **핵심:** VM에 서비스 계정을 붙이면 내부 메타데이터 서버(169.254.169.254)에서  
> 토큰을 자동 발급하므로 JSON 키 파일 관리가 불필요합니다.

---

## 2. 사전 준비 (로컬 PC)

> 이 섹션의 모든 명령은 **로컬 PC** 에서 실행합니다.  
> 먼저 로컬 PC에 `gcloud` CLI가 설치되어 있어야 합니다.

### 2-0. gcloud CLI 설치 (최초 1회)

**Windows:**
```powershell
# Google Cloud SDK 설치 (PowerShell)
(New-Object Net.WebClient).DownloadFile(
  "https://dl.google.com/dl/cloudsdk/channels/rapid/GoogleCloudSDKInstaller.exe",
  "$env:TEMP`GoogleCloudSDKInstaller.exe"
)
& $env:TEMP`GoogleCloudSDKInstaller.exe
# 설치 마법사 완료 후 새 터미널 열기
```

또는 공식 설치 페이지에서 직접 다운로드:  
https://cloud.google.com/sdk/docs/install

**macOS:**
```bash
brew install --cask google-cloud-sdk
```

**Linux:**
```bash
curl https://sdk.cloud.google.com | bash
exec -l $SHELL
```

### 2-0-1. gcloud 초기화 및 로그인 (최초 1회)

```bash
# 설치 확인
gcloud version

# Google 계정으로 인증
gcloud auth login
# 브라우저가 열리면 GCP 계정으로 로그인

# 기본 프로젝트 설정
gcloud config set project project-d0f933ee-5915-44a1-a01

# 설정 확인
gcloud config list
```

---

### 2-1. 프로젝트 및 리전 설정

```bash
gcloud config set project project-d0f933ee-5915-44a1-a01
gcloud config set compute/region asia-northeast3   # 서울 리전
gcloud config set compute/zone asia-northeast3-a
```

### 2-2. 필요 API 활성화

```bash
gcloud services enable compute.googleapis.com pubsub.googleapis.com iamcredentials.googleapis.com
```

### 2-3. Pub/Sub 토픽 생성

```bash
gcloud pubsub topics create vector-log-ingest
```

### 2-4. Pub/Sub 구독 생성

```bash
gcloud pubsub subscriptions create vector-log-sub --topic=vector-log-ingest --ack-deadline=60
```

### 2-5. 리소스 생성 확인

```bash
gcloud pubsub topics list
gcloud pubsub subscriptions list
```

---

## 3. 서비스 계정 생성 및 권한 부여

> 로컬 PC에서 실행합니다.

### 3-1. 서비스 계정 생성

```bash
gcloud iam service-accounts create vector-vm-sa --display-name="Vector VM Service Account"
```

### 3-2. Pub/Sub Publisher 권한 부여

```bash
gcloud projects add-iam-policy-binding project-d0f933ee-5915-44a1-a01 --member="serviceAccount:vector-vm-sa@project-d0f933ee-5915-44a1-a01.iam.gserviceaccount.com" --role="roles/pubsub.publisher"
```

### 3-3. 권한 부여 확인

```bash
gcloud projects get-iam-policy project-d0f933ee-5915-44a1-a01 `
  --flatten="bindings[].members" `
  --filter="bindings.members:vector-vm-sa@*" `
  --format="table(bindings.role)"
```

**필요한 IAM Role 정리**

| Role | 대상 | 용도 |
|------|------|------|
| `roles/pubsub.publisher` | Vector VM SA | 토픽에 메시지 publish |
| `roles/pubsub.subscriber` | 검증 시 사용자 계정 | subscription pull |

---

## 4. GCP VM 생성

> 로컬 PC에서 실행합니다.

### 4-1. VM 생성

```bash
gcloud compute instances create vector-test-vm `
  --zone=asia-northeast3-a `
  --machine-type=e2-medium `
  --image-family=debian-12 `
  --image-project=debian-cloud `
  --boot-disk-size=20GB `
  --service-account=vector-vm-sa@project-d0f933ee-5915-44a1-a01.iam.gserviceaccount.com `
  --scopes=https://www.googleapis.com/auth/pubsub `
  --tags=vector-test
```

> `--service-account` + `--scopes=pubsub` 조합이 핵심입니다.  
> JSON 키 파일 없이 VM 내부에서 Pub/Sub 접근이 가능합니다.

### 4-2. VM 생성 확인

```bash
gcloud compute instances list
# vector-test-vm 이 RUNNING 상태인지 확인
```

### 4-3. VM SSH 접속

```bash
gcloud compute ssh vector-test-vm --zone=asia-northeast3-a
```

> **이후 STEP 5~12는 모두 VM 내부 SSH 세션에서 실행합니다.**

---

## 5. VM 환경 설치

> VM 내부(SSH)에서 실행합니다.

### 5-1. Java 17 설치

```bash
sudo apt-get update -y
sudo apt-get install -y openjdk-17-jdk

# 설치 확인
java -version
# openjdk version "17.x.x" 출력 확인
```

### 5-2. Gradle 설치

```bash
sudo apt-get install -y unzip wget

wget https://services.gradle.org/distributions/gradle-8.7-bin.zip -P /tmp
sudo unzip -d /opt/gradle /tmp/gradle-8.7-bin.zip
sudo ln -s /opt/gradle/gradle-8.7/bin/gradle /usr/local/bin/gradle

# 설치 확인
gradle -v
```

### 5-3. Vector 설치

> 저장소 등록 방식(timber.io / sh.vector.dev)은 GCP VM 환경에서 접근이 막히는 경우가 있습니다.  
> **GitHub Releases 직접 다운로드 방식**이 가장 안정적입니다.

**STEP 1 - 의존 패키지 설치**
```bash
sudo apt-get install -y curl wget
```

**STEP 2 - 최신 버전 번호 자동 조회 후 설치**
```bash
VERSION=$(curl -s https://api.github.com/repos/vectordotdev/vector/releases/latest \
  | grep '"tag_name"' \
  | cut -d'"' -f4 \
  | sed 's/v//')

echo "설치할 Vector 버전: $VERSION"

wget "https://github.com/vectordotdev/vector/releases/download/v${VERSION}/vector_${VERSION}-1_amd64.deb"

sudo dpkg -i "vector_${VERSION}-1_amd64.deb"
```

**STEP 3 - 설치 확인**
```bash
vector --version
# vector 0.xx.x (x86_64-unknown-linux-gnu ...) 출력 확인
```

---

**버전 조회까지 안 될 경우 - 버전 직접 지정**

GitHub 에서 최신 버전 확인: https://github.com/vectordotdev/vector/releases

```bash
# 아래 버전 번호를 위 링크에서 확인한 최신 버전으로 교체
VECTOR_VERSION=0.43.1

wget "https://github.com/vectordotdev/vector/releases/download/v${VECTOR_VERSION}/vector_${VECTOR_VERSION}-1_amd64.deb"
sudo dpkg -i "vector_${VECTOR_VERSION}-1_amd64.deb"

vector --version
```

---

## 6. 프로젝트 파일 VM 업로드

> **GCP 콘솔 브라우저 SSH 사용 중인 경우:**  
> `gcloud compute scp` 는 로컬 SSH 연결이 필요하므로 사용 불가합니다.  
> 아래 방법 A(콘솔 파일 업로드) 또는 방법 B(Git)를 사용하세요.

### 방법 A: GCP 콘솔 브라우저 SSH 파일 업로드 (권장)

GCP 콘솔 브라우저 SSH 창 우측 상단 **톱니바퀴(⚙) → "파일 업로드"** 메뉴를 통해 파일을 개별 업로드할 수 있습니다.

업로드해야 할 파일 목록:

| 로컬 경로 | VM 업로드 후 이동 경로 |
|-----------|----------------------|
| `build.gradle` | `~/demo-springboot/build.gradle` |
| `settings.gradle` | `~/demo-springboot/settings.gradle` |
| `src/main/resources/application.yml` | `~/demo-springboot/src/main/resources/application.yml` |
| `src/main/resources/logback-spring.xml` | `~/demo-springboot/src/main/resources/logback-spring.xml` |
| `src/main/java/.../VectorTestApplication.java` | `~/demo-springboot/src/main/java/com/example/vectortest/VectorTestApplication.java` |
| `src/main/java/.../LogTestController.java` | `~/demo-springboot/src/main/java/com/example/vectortest/controller/LogTestController.java` |
| `vector/vector.yaml` | `~/demo-springboot/vector/vector.yaml` |

업로드 후 디렉토리 구조 생성 및 파일 이동:
```bash
# 디렉토리 구조 생성
mkdir -p ~/demo-springboot/src/main/java/com/example/vectortest/controller
mkdir -p ~/demo-springboot/src/main/resources
mkdir -p ~/demo-springboot/vector
mkdir -p ~/demo-springboot/logs

# 업로드된 파일은 기본적으로 홈 디렉토리(~/)에 위치함
# 각 파일을 올바른 경로로 이동
mv ~/build.gradle ~/demo-springboot/
mv ~/settings.gradle ~/demo-springboot/
mv ~/application.yml ~/demo-springboot/src/main/resources/
mv ~/logback-spring.xml ~/demo-springboot/src/main/resources/
mv ~/VectorTestApplication.java ~/demo-springboot/src/main/java/com/example/vectortest/
mv ~/LogTestController.java ~/demo-springboot/src/main/java/com/example/vectortest/controller/
mv ~/vector.yaml ~/demo-springboot/vector/
```

---

### 방법 B: Git 저장소 사용 ✅ (현재 완료)

```bash
sudo apt-get install -y git
git clone https://github.com/YOUR_REPO/demo-springboot.git ~/demo-springboot
```

> git clone 완료 후 아래 순서로 진행합니다.
>
> 1. **[섹션 7]** `vector.yaml` 편집 — 로그 파일 경로(whoami 결과 반영) + `credentials_path` 줄 제거
> 2. **[섹션 8]** ADC 인증 확인 — 서비스 계정 이메일 및 Pub/Sub publish 권한 테스트
> 3. **[섹션 9]** Spring Boot 빌드(`gradle build -x test`) 후 백그라운드 실행
> 4. **[섹션 10]** Vector 백그라운드 실행
> 5. **[섹션 11]** API 호출 → 로그 파일 확인 → Pub/Sub 메시지 수신 확인

---

### 방법 C: heredoc 으로 파일 직접 생성 (VM 내부에서 실행)

Git도 없고 파일 업로드도 번거로운 경우, 브라우저 SSH 창에서 내용을 직접 붙여넣어 파일을 생성합니다.

```bash
# 디렉토리 생성
mkdir -p ~/demo-springboot/src/main/java/com/example/vectortest/controller
mkdir -p ~/demo-springboot/src/main/resources
mkdir -p ~/demo-springboot/vector
mkdir -p ~/demo-springboot/logs

# 예시: vector.yaml 생성
cat > ~/demo-springboot/vector/vector.yaml << 'EOF'
# 로컬 PC의 vector/vector.yaml 내용을 여기에 붙여넣기
EOF

# 예시: build.gradle 생성
cat > ~/demo-springboot/build.gradle << 'EOF'
# 로컬 PC의 build.gradle 내용을 여기에 붙여넣기
EOF
```

> **팁:** 브라우저 SSH 창에서 긴 내용을 붙여넣을 때는 `nano 파일명` 으로 편집기를 열고  
> 붙여넣기(Ctrl+Shift+V) 후 Ctrl+O → Enter → Ctrl+X 로 저장하는 것이 더 편합니다.

---

## 7. Vector 설정 수정

> VM 내부에서 실행합니다.

### 7-1. 현재 VM 사용자명 및 경로 확인

```bash
whoami
# 예: user
pwd
# 예: /home/user
```

### 7-2. vector.yaml 편집

```bash
nano ~/demo-springboot/vector/vector.yaml
```

**수정 대상 항목 4가지:**

```yaml
# ① 체크포인트 저장 경로 (VM 로컬)
data_dir: /tmp/vector-data

# ② 로그 파일 경로 (VM 기준 절대 경로)
sources:
  app_logs:
    type: file
    include:
      - /home/gnkim907/demo-vector-springboot/logs/app.log   # ← whoami 결과로 user 부분 변경
    read_from: beginning

# ③ GCP 프로젝트 ID & 토픽
sinks:
  gcp_pubsub_out:
    project: project-d0f933ee-5915-44a1-a01
    topic: vector-log-ingest

    # ④ credentials_path 줄 삭제 또는 주석 처리 (ADC 자동 사용)
    # credentials_path: ...       # ← 이 줄 완전히 제거
```

### 7-3. 설정 파일 문법 검증

```bash
vector validate --config ~/demo-springboot/vector/vector.yaml
# ✓ Validated  출력 확인
```

---

## 8. ADC 인증 동작 확인

> VM 내부에서 실행합니다. 선택 사항이지만 트러블슈팅 이전에 실행 권장합니다.

### 8-1. VM에 붙은 서비스 계정 확인

```bash
curl -s -H "Metadata-Flavor: Google" `
  "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/email"
# vector-vm-sa@project-d0f933ee-5915-44a1-a01.iam.gserviceaccount.com 출력 확인
```

### 8-2. Pub/Sub Publish 권한 직접 테스트

```bash
TOKEN=$(curl -s -H "Metadata-Flavor: Google" \
  "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

curl -s -X POST \
  "https://pubsub.googleapis.com/v1/projects/project-d0f933ee-5915-44a1-a01/topics/vector-log-ingest:publish" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"data":"dGVzdA=="}]}'
# {"messageIds":["..."]} 출력 시 인증 및 권한 정상
```

---

## 9. Spring Boot 빌드 및 실행

> VM 내부에서 실행합니다.

### 9-1. 빌드

```bash
cd ~/demo-springboot
gradle build -x test
```

### 9-2. 로그 디렉토리 생성

```bash
mkdir -p ~/demo-springboot/logs
```

### 9-3. 백그라운드 실행

```bash
nohup java -jar build/libs/vector-test-app-0.0.1-SNAPSHOT.jar \
  > /tmp/springboot.out 2>&1 &

echo "Spring Boot PID: $!"
```

### 9-4. 실행 확인 (약 10초 대기)

```bash
sleep 10
tail -20 /tmp/springboot.out
# "Started VectorTestApplication" 출력 확인

curl http://localhost:8080/test/log
# {"requestId":"...","status":"logged","message":"Hello from Vector Test!"} 출력 확인
```

---

## 10. Vector 실행

> VM 내부에서 실행합니다.

### 10-1. data_dir 생성

```bash
mkdir -p /tmp/vector-data
```

### 10-2. 백그라운드 실행

```bash
nohup vector --config ~/demo-springboot/vector/vector.yaml \
  > /tmp/vector.out 2>&1 &

echo "Vector PID: $!"
```

### 10-3. 실행 로그 확인

```bash
tail -f /tmp/vector.out
```

**정상 시작 출력 예시:**

```
INFO vector::app: Log level is set to INFO
INFO vector::sources::file: Watching file  path="/home/user/demo-springboot/logs/app.log"
INFO vector: Vector has started.
```

---

## 11. API 호출 및 검증

> VM 내부에서 실행합니다.

### 11-1. API 호출

```bash
# 단건
curl "http://localhost:8080/test/log?message=VM-pipeline-test"

# 5회 연속
for i in $(seq 1 5); do
  curl -s "http://localhost:8080/test/log?message=log_${i}"
  sleep 0.5
done
```

### 11-2. 로그 파일 내용 확인

```bash
tail -f ~/demo-springboot/logs/app.log
```

**정상 출력 예시 (1줄 JSON):**
```json
{"@timestamp":"2024-01-15T10:30:00.000Z","@version":"1","message":"VM-pipeline-test","level":"INFO","app":"vector-test-app","requestId":"550e8400-e29b-41d4-a716-446655440000","endpoint":"/test/log"}
```

포함되어야 할 필드:

| 필드 | 예시 값 | 출처 |
|------|---------|------|
| `@timestamp` | `2024-01-15T10:30:00.000Z` | LogstashEncoder 자동 생성 |
| `level` | `INFO` | LogstashEncoder 자동 생성 |
| `message` | `VM-pipeline-test` | `log.info(message)` |
| `requestId` | `550e8400-...` | MDC 주입 |
| `endpoint` | `/test/log` | MDC 주입 |
| `app` | `vector-test-app` | logback-spring.xml customFields |

### 11-3. Vector 처리 로그 확인

```bash
tail -f /tmp/vector.out | grep -E "file|pubsub|error|sent"
```

### 11-4. Pub/Sub 메시지 수신 확인

```bash
gcloud pubsub subscriptions pull vector-log-sub \
  --auto-ack \
  --limit=5 \
  --format=json
```

**출력 예시:**
```json
[
  {
    "message": {
      "data": "eyJAdGltZXN0YW1wIjoiMjAyNC0wMS0xNVQxMDozMDowMC4wMDBaIiwibGV2ZWwiOiJJTkZPIn0=",
      "messageId": "12345678901",
      "publishTime": "2024-01-15T10:30:01.000Z"
    }
  }
]
```

**data 필드 base64 디코딩:**
```bash
echo "eyJAdGltZXN0YW1wIjoiMjAyNC0wMS0xNVQxMDozMDowMC4wMDBaIiwibGV2ZWwiOiJJTkZPIn0=" \
  | base64 -d | python3 -m json.tool
```

---

## 12. 종료 및 정리

### 12-1. 프로세스 종료 (VM 내부)

```bash
kill $(pgrep -f vector-test-app)
kill $(pgrep -f "vector --config")
```

### 12-2. VM 중지 (로컬 PC - 요금 절약)

```bash
gcloud compute instances stop vector-test-vm --zone=asia-northeast3-a
```

### 12-3. VM 삭제 (로컬 PC - 테스트 완료 후)

```bash
gcloud compute instances delete vector-test-vm --zone=asia-northeast3-a
```

### 12-4. Pub/Sub 리소스 삭제 (로컬 PC)

```bash
gcloud pubsub subscriptions delete vector-log-sub
gcloud pubsub topics delete vector-log-ingest
```

---

## 13. 포트 개방 (선택)

VM 외부(로컬 PC 등)에서 API를 직접 호출하고 싶을 때만 적용합니다.

### 13-1. 방화벽 규칙 추가 (로컬 PC)

```bash
# 본인 IP 확인
curl ifconfig.me

# 방화벽 규칙 추가 (본인 IP만 허용)
gcloud compute firewall-rules create allow-springboot `
  --direction=INGRESS `
  --action=ALLOW `
  --rules=tcp:8080 `
  --target-tags=vector-test `
  --source-ranges=YOUR_IP/32
```

### 13-2. VM 외부 IP 확인

```bash
gcloud compute instances describe vector-test-vm `
  --zone=asia-northeast3-a `
  --format="get(networkInterfaces[0].accessConfigs[0].natIP)"
```

### 13-3. 외부에서 API 호출

```bash
curl "http://VM_EXTERNAL_IP:8080/test/log?message=external-test"
```

---

## 14. 성공 체크리스트

| # | 항목 | 확인 방법 |
|---|------|-----------|
| 1 | VM에 `vector-vm-sa` 서비스 계정이 붙어 있음 | `curl metadata.google.internal/...email` |
| 2 | Pub/Sub publish API 호출 성공 | `{"messageIds":[...]}` 응답 확인 |
| 3 | `vector validate` 통과 | `✓ Validated` 출력 |
| 4 | Spring Boot 정상 기동 | `curl localhost:8080/test/log` → 200 OK |
| 5 | `logs/app.log` 에 one-line JSON 기록 | `tail logs/app.log` 후 JSON 구조 확인 |
| 6 | JSON 에 `requestId`, `endpoint` 필드 존재 | 위 로그 파일 내용 직접 확인 |
| 7 | Vector 가 파일 감시 중 | `tail /tmp/vector.out` 에서 `Watching file` 확인 |
| 8 | Pub/Sub 메시지 수신 | `gcloud subscriptions pull` 결과 확인 |
| 9 | 디코딩된 data 에 원본 JSON 필드 존재 | `base64 -d` 후 JSON 파싱 확인 |

---

## 15. 트러블슈팅

### ❌ VM에 서비스 계정이 없거나 잘못 붙은 경우

**증상:** `curl metadata.google.internal/...email` 에서 `default` 계정 또는 빈 응답

**해결:**
```bash
# 로컬 PC에서 VM에 서비스 계정 재설정
gcloud compute instances set-service-account vector-test-vm `
  --zone=asia-northeast3-a `
  --service-account=vector-vm-sa@project-d0f933ee-5915-44a1-a01.iam.gserviceaccount.com `
  --scopes=https://www.googleapis.com/auth/pubsub
```

---

### ❌ GCP 인증 실패 (403 / PERMISSION_DENIED)

**증상:** Vector 로그에 `403` 또는 `PERMISSION_DENIED`

**해결:**
```bash
# Publisher 권한 재부여
gcloud pubsub topics add-iam-policy-binding vector-log-ingest `
  --project=project-d0f933ee-5915-44a1-a01 `
  --member="serviceAccount:vector-vm-sa@project-d0f933ee-5915-44a1-a01.iam.gserviceaccount.com" `
  --role="roles/pubsub.publisher"
```

---

### ❌ Vector 가 로그 파일을 못 읽는 경우

**증상:** `/tmp/vector.out` 에 파일 관련 로그 없음

**해결:**
```bash
# 파일 경로 및 권한 확인
ls -la ~/demo-springboot/logs/app.log

# vector.yaml 의 include 경로를 절대 경로로 수정
# /home/user/demo-springboot/logs/app.log  (상대 경로 사용 금지)
```

---

### ❌ 로그 파일은 생기는데 Pub/Sub 에 메시지가 없는 경우

**증상:** `logs/app.log` 에 내용은 있는데 pull 결과 없음

**해결:**
```bash
# 1. Vector 상세 로그 확인
VECTOR_LOG=debug vector --config ~/demo-springboot/vector/vector.yaml 2>&1 | grep -E "error|abort|parse"

# 2. 체크포인트 초기화 후 재시작 (이미 읽은 파일로 인식된 경우)
rm -rf /tmp/vector-data
# vector.yaml: read_from: beginning 확인 후 Vector 재시작
```

---

### ❌ JSON 파싱 실패 (이벤트 drop)

**증상:** Vector 로그에 `Failed to parse` 또는 `abort`

**원인:** `logs/app.log` 에 JVM 시작 텍스트 등 non-JSON 라인이 섞임

**해결 (`vector.yaml` transforms 수정):**
```yaml
transforms:
  parse_and_enrich:
    type: remap
    inputs:
      - app_logs
    source: |
      parsed, err = parse_json(.message)
      if err != null {
        log("JSON parse skipped: " + to_string(err), level: "warn")
        abort
      }
      . = merge(., object!(parsed))
      .vector_ingested_at = format_timestamp!(now(), format: "%+")
      del(.source_type)
```

---

### ❌ 파일 append 후 Vector 가 반응 없는 경우

**증상:** API 호출 후 로그 파일에 내용은 추가되지만 Vector 가 미전송

**해결:**
```bash
# inotify 동작 확인
inotifywait -m ~/demo-springboot/logs/app.log

# 감지 안 되면 glob_minimum_cooldown_ms 를 vector.yaml 에 추가
sources:
  app_logs:
    type: file
    include:
      - /home/user/demo-springboot/logs/app.log
    glob_minimum_cooldown_ms: 1000   # 1초 poll 방식으로 fallback
```
