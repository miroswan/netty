HEADER_DIR="headers/macos"
OUTPUT_DIR="src/main/java"
TARGET_PACKAGE="io.netty.ffm.macos.generated"

BINDINGS=(
  "socket.h BsdSocket
    --include-function socket
    --include-function bind
    --include-function listen
    --include-function accept
    --include-function connect
    --include-function shutdown
    --include-function getsockname
    --include-function getpeername
    --include-function getsockopt
    --include-function setsockopt
    --include-function sendto
    --include-function recvfrom
    --include-function sendfile
    --include-function connectx
    --include-struct sockaddr
    --include-struct sockaddr_in
    --include-struct sockaddr_in6
    --include-struct sockaddr_storage
    --include-struct sa_endpoints
    --include-constant AF_INET
    --include-constant AF_INET6
    --include-constant AF_UNIX
    --include-constant SOCK_STREAM
    --include-constant SOCK_DGRAM
    --include-constant SOL_SOCKET
    --include-constant SO_REUSEADDR
    --include-constant SO_REUSEPORT
    --include-constant SO_KEEPALIVE
    --include-constant SO_ERROR
    --include-constant SO_SNDLOWAT
    --include-constant SO_SNDBUF
    --include-constant SO_RCVBUF
    --include-constant SO_LINGER
    --include-constant SHUT_RD
    --include-constant SHUT_WR
    --include-constant SHUT_RDWR"

  "unistd.h Posix
    --include-function read
    --include-function write
    --include-function close
    --include-function pipe"

  "uio.h Uio
    --include-function writev
    --include-function readv
    --include-struct iovec"

  "in.h In
    --include-struct sockaddr_in
    --include-struct sockaddr_in6
    --include-struct in_addr
    --include-struct in6_addr
    --include-constant INADDR_ANY
    --include-constant INADDR_LOOPBACK
    --include-constant IPPROTO_TCP"

  "fcntl.h Fcntl
    --include-function fcntl
    --include-constant F_GETFL
    --include-constant F_SETFL
    --include-constant O_NONBLOCK"

  "tcp.h Tcp
    --include-constant TCP_NODELAY
    --include-constant TCP_NOPUSH"

  "un.h Un
    --include-struct sockaddr_un"
)
