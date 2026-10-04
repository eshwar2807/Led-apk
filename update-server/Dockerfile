# Build a static binary, then ship it with the one release it serves.
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /update-server .

# Runs as root: fly.io volumes are mounted root-owned, and reports are written to one.
FROM gcr.io/distroless/static-debian12
COPY --from=build /update-server /update-server
# Filled in by the release workflow: latest.json and the APK it names.
COPY release/ /release/
ENV RELEASE_DIR=/release PORT=8080
EXPOSE 8080
ENTRYPOINT ["/update-server"]
