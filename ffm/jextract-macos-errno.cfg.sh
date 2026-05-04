HEADER_DIR="headers/macos"
OUTPUT_DIR="src/main/java"
TARGET_PACKAGE="io.netty.ffm.macos.generated"

BINDINGS=(
  "errno.h Errno
    --include-function __error
    --include-constant EAGAIN
    --include-constant EINTR
    --include-constant EWOULDBLOCK
    --include-constant EINVAL
    --include-constant EINPROGRESS
    --include-constant EPIPE
    --include-constant ECONNABORTED
    --include-constant ECONNRESET
    --include-constant ENOTCONN
    --include-constant ESHUTDOWN
    --include-constant ENOENT
    --include-constant EBADF
    --include-constant ECONNREFUSED"
)
