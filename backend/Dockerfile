# Multi-stage build for ultra-lightweight, zero-disk deployment (<20MB)
FROM golang:1.22-alpine AS builder

WORKDIR /app

COPY go.mod go.sum* ./
RUN go mod download || true

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o nexa-relay ./cmd/server
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o nexa-swarm ./cmd/swarm
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o nexa-blind-relay ./cmd/relay
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o nexa-signaling ./cmd/signaling
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o nexa-handshake ./cmd/handshake
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o nexa-qr-sync ./cmd/qr-sync
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o nexa-security-rules ./cmd/security-rules

# Final static minimal container
FROM alpine:3.20

RUN apk --no-cache add ca-certificates tzdata

WORKDIR /root/
COPY --from=builder /app/nexa-relay .
COPY --from=builder /app/nexa-swarm .
COPY --from=builder /app/nexa-blind-relay .
COPY --from=builder /app/nexa-signaling .
COPY --from=builder /app/nexa-handshake .
COPY --from=builder /app/nexa-qr-sync .
COPY --from=builder /app/nexa-security-rules .

EXPOSE 8080 8081 8082 8083 8084 8085 8086

CMD ["./nexa-relay"]
