HEADER_DIR="headers/macos"
OUTPUT_DIR="src/main/java"
TARGET_PACKAGE="io.netty.ffm.macos.generated"

BINDINGS=(
  "event.h Event
    --include-function kqueue
    --include-function kevent
    --include-struct kevent
    --include-constant EVFILT_READ
    --include-constant EVFILT_WRITE
    --include-constant EVFILT_TIMER
    --include-constant EVFILT_USER
    --include-constant EVFILT_SOCK
    --include-constant EV_ADD
    --include-constant EV_DELETE
    --include-constant EV_ENABLE
    --include-constant EV_DISABLE
    --include-constant EV_CLEAR
    --include-constant EV_EOF
    --include-constant EV_ERROR
    --include-constant EV_ONESHOT
    --include-constant NOTE_READCLOSED
    --include-constant NOTE_CONNRESET
    --include-constant NOTE_DISCONNECTED
    --include-constant NOTE_TRIGGER"
)
