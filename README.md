# AI Agent Platform

Spring Boot 4.1 + React 19 기반의 개인 AI Agent 프로젝트입니다.

Ollama 로컬 LLM을 중심으로 실시간 스트리밍 채팅, 멀티 채팅방, 파일
분석/RAG, Tool Calling, 영상 분석, Shorts 생성, Agent Memory 등을
구현하고 있습니다.

## Tech Stack

### Backend

-   Java 21
-   Spring Boot 4.1
-   Spring MVC / WebFlux
-   Spring Data JPA
-   MariaDB
-   Redis
-   Ollama
-   Apache PDFBox
-   Apache POI
-   FFmpeg / Whisper

### Frontend

-   React 19
-   TypeScript
-   Vite
-   Zustand
-   React Query
-   React Router
-   React Markdown
-   remark-gfm
-   Prism Syntax Highlighter

## Architecture

``` text
React 19
   │
   │ REST / SSE
   ▼
Spring Boot 4.1
   │
   ├─ Chat / Conversation
   │    ├─ Multi Room
   │    ├─ Streaming
   │    ├─ Conversation Summary
   │    └─ Agent Memory
   │
   ├─ Agent
   │    ├─ Tool Calling
   │    ├─ Web Search
   │    └─ Source / Execution Step
   │
   ├─ File / RAG
   │    ├─ TXT
   │    ├─ PDF
   │    ├─ DOCX
   │    └─ XLSX
   │
   ├─ Video
   │    ├─ Whisper
   │    ├─ Frame Analysis
   │    ├─ Video Summary
   │    └─ Shorts Generation
   │
   └─ Ollama
        ├─ Text Model
        ├─ Vision Model
        └─ Embedding Model
```

## Features

### Chat

-   [x] React + Spring Boot 연동
-   [x] Ollama 연동
-   [x] SSE 기반 실시간 응답 스트리밍
-   [x] 멀티 채팅방
-   [x] 활성 채팅방 복원
-   [x] 채팅방 이름 변경
-   [x] 채팅방 고정 / 고정 해제
-   [x] 채팅방 삭제
-   [x] 사용자 질문 수정 후 재생성
-   [x] 마지막 AI 답변 재생성
-   [x] 사용자 / AI 메시지 복사
-   [x] 생성 중인 채팅방 상태 관리
-   [x] 최신 메시지 이동
-   [x] 빈 채팅 Prompt Suggestion

### Markdown / Response UI

-   [x] Markdown 렌더링
-   [x] GFM 지원
-   [x] Syntax Highlighting
-   [x] 코드 블록 복사
-   [x] Markdown strong 보정
-   [x] 긴 URL / 코드 overflow 처리
-   [x] 넓은 Markdown Table 전용 가로 스크롤
-   [x] Source 표시
-   [x] Agent 실행 단계 표시

### File / RAG

-   [x] 파일 업로드
-   [x] TXT 분석
-   [x] PDF 분석
-   [x] DOCX 분석
-   [x] XLSX 분석
-   [x] RAG
-   [x] Embedding 기반 검색
-   [x] 다중 파일 첨부
-   [x] 파일 중복 선택 방지

### Agent / Tool Calling

-   [x] Tool Calling
-   [x] Tool Calling 후 최종 응답 생성
-   [x] Agent 실행 상태 UI
-   [x] 웹 검색 결과 Source 연동

### Video

-   [x] 영상 분석
-   [x] Whisper 음성 분석
-   [x] 영상 Frame 분석
-   [x] 영상 Summary
-   [x] Shorts 구간 자동 선정
-   [x] Shorts 다중 생성
-   [x] SRT 자막 생성
-   [x] FFmpeg 자막 Burn-in
-   [x] 생성 결과 다운로드

### Conversation Memory

-   [x] 채팅방 단위 Conversation Summary
-   [x] 장기 Agent Memory
-   [x] Embedding 기반 관련 Memory 검색
-   [x] 다른 채팅방에서 Memory 재사용
-   [x] Memory 자동 ADD / UPDATE
-   [x] Settings에서 Memory ON / OFF
-   [x] Memory 목록 조회
-   [x] Memory 직접 수정
-   [x] 수정 시 Embedding 재생성
-   [x] Memory 개별 삭제
-   [x] Memory 전체 삭제

### Settings

-   [x] Backend 상태 확인
-   [x] Ollama 상태 확인
-   [x] Ollama Endpoint 표시
-   [x] 설치된 Ollama Model 조회
-   [x] Text Model 런타임 변경
-   [x] Vision Model 런타임 변경
-   [x] Embedding Model 런타임 변경
-   [x] Agent Memory 관리

## Model Configuration

현재 Text / Vision / Embedding 모델은 Settings 화면에서 변경할 수 있으며
서버 재시작 없이 런타임에 반영됩니다.

``` text
TEXT_MODEL
VISION_MODEL
EMBEDDING_MODEL
MEMORY_ENABLED
```

설정값은 MariaDB의 `app_setting` 테이블에 저장됩니다.

## Agent Memory

Agent Memory는 채팅방별 Conversation Summary와 별도로 동작하는 장기 기억
기능입니다.

``` text
User Question
    │
    ▼
Embedding
    │
    ▼
Relevant Memory Search
    │
    ▼
Conversation Context
    │
    ├─ Agent Memory
    ├─ Conversation Summary
    └─ Recent Messages
    │
    ▼
LLM Response
    │
    ▼
Memory Extraction
    │
    ├─ ADD
    ├─ UPDATE
    └─ NONE
```

Memory를 OFF하면 기존 Memory 데이터는 유지되지만 관련 Memory 검색과
새로운 Memory 자동 저장은 수행하지 않습니다.

## Screenshots

추후 추가 예정입니다.

## Roadmap

-   [ ] Dify 연동
-   [ ] Agent Tool 확장
-   [ ] RAG / 파일 검색 고도화
-   [ ] 대화 검색 강화
-   [ ] 웹 검색 / Source UX 고도화
