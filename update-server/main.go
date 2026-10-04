// Command update-server publishes ReelPlay releases: a small JSON manifest the app polls,
// the APK it points to, and a plain download page for people who don't have the app yet.
//
// Each deploy carries exactly one release, baked into the image by the release workflow:
//
//	release/latest.json    the manifest (see Manifest)
//	release/ReelPlay-N.apk the APK it names
//
// On startup the server checks the APK against the manifest's size and SHA-256, so a broken
// release fails its health check instead of handing out a corrupt download.
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Manifest is what the app reads from /reelplay/latest.json.
type Manifest struct {
	VersionCode int    `json:"versionCode"`
	VersionName string `json:"versionName"`
	// APK file name inside the release directory; served under /reelplay/.
	APK         string `json:"apk"`
	SHA256      string `json:"sha256"`
	Size        int64  `json:"size"`
	Notes       string `json:"notes"`
	PublishedAt string `json:"publishedAt"`
}

type server struct {
	reports  *reports
	dir      string
	manifest Manifest
	raw      []byte // manifest bytes exactly as served
	etag     string
	modified time.Time
}

func load(dir string) (*server, error) {
	raw, err := os.ReadFile(filepath.Join(dir, "latest.json"))
	if err != nil {
		return nil, fmt.Errorf("reading manifest: %w", err)
	}
	var m Manifest
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("parsing manifest: %w", err)
	}
	if m.VersionCode <= 0 || m.VersionName == "" {
		return nil, errors.New("manifest needs versionCode and versionName")
	}
	if m.APK == "" || m.APK != filepath.Base(m.APK) || !strings.HasSuffix(m.APK, ".apk") {
		return nil, fmt.Errorf("manifest apk %q must be a plain .apk file name", m.APK)
	}
	f, err := os.Open(filepath.Join(dir, m.APK))
	if err != nil {
		return nil, fmt.Errorf("opening apk: %w", err)
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return nil, fmt.Errorf("hashing apk: %w", err)
	}
	if n != m.Size {
		return nil, fmt.Errorf("apk is %d bytes, manifest says %d", n, m.Size)
	}
	if sum := hex.EncodeToString(h.Sum(nil)); !strings.EqualFold(sum, m.SHA256) {
		return nil, fmt.Errorf("apk sha256 is %s, manifest says %s", sum, m.SHA256)
	}
	st, _ := f.Stat()
	sum := sha256.Sum256(raw)
	return &server{
		dir:      dir,
		manifest: m,
		raw:      raw,
		etag:     `"` + hex.EncodeToString(sum[:8]) + `"`,
		modified: st.ModTime(),
	}, nil
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		io.WriteString(w, "ok\n")
	})
	mux.HandleFunc("GET /reelplay/latest.json", s.latest)
	mux.HandleFunc("GET /reelplay/{file}", s.apk)
	mux.HandleFunc("GET /{$}", s.page)
	if s.reports != nil {
		s.reports.routes(mux)
	}
	return logRequests(mux)
}

// latest is polled by every install, so it is small, revalidated each time, and cheap to
// answer with 304 when nothing changed.
func (s *server) latest(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("ETag", s.etag)
	if r.Header.Get("If-None-Match") == s.etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Write(s.raw)
}

// apk serves only the file the manifest names. Range requests work (http.ServeContent), so
// an interrupted download on a phone can resume.
func (s *server) apk(w http.ResponseWriter, r *http.Request) {
	if r.PathValue("file") != s.manifest.APK {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(filepath.Join(s.dir, s.manifest.APK))
	if err != nil {
		http.Error(w, "release missing", http.StatusInternalServerError)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	// The name carries the version, so its content never changes.
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	w.Header().Set("Content-Disposition", `attachment; filename="`+s.manifest.APK+`"`)
	http.ServeContent(w, r, s.manifest.APK, s.modified, f)
}

var pageTmpl = template.Must(template.New("page").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ReelPlay {{.VersionName}}</title>
<style>
:root{color-scheme:light dark;--bg:#fff;--fg:#14171f;--muted:#5b6475;--accent:#3d8bff}
@media (prefers-color-scheme:dark){:root{--bg:#0b0e16;--fg:#e4e7f0;--muted:#a3abbb}}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif}
main{max-width:560px;margin:0 auto;padding:48px 16px}
a.btn{display:inline-block;background:var(--accent);color:#fff;padding:12px 20px;border-radius:10px;text-decoration:none;font-weight:600}
p.muted{color:var(--muted);font-size:14px} pre{white-space:pre-wrap;font:inherit}
</style></head><body><main>
<h1>ReelPlay {{.VersionName}}</h1>
<p><a class="btn" href="/reelplay/{{.APK}}">Download APK</a></p>
<p class="muted">{{printf "%.1f" .SizeMB}} MB · released {{.PublishedAt}}. Already installed? The app updates itself.</p>
{{if .Notes}}<h2>What's new</h2><pre>{{.Notes}}</pre>{{end}}
</main></body></html>`))

func (s *server) page(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	pageTmpl.Execute(w, struct {
		Manifest
		SizeMB float64
	}{s.manifest, float64(s.manifest.Size) / (1 << 20)})
}

func logRequests(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		h.ServeHTTP(w, r)
		if r.URL.Path != "/healthz" {
			log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
		}
	})
}

func main() {
	dir := envOr("RELEASE_DIR", "/release")
	addr := ":" + envOr("PORT", "8080")
	s, err := load(dir)
	if err != nil {
		log.Fatalf("bad release in %s: %v", dir, err)
	}
	s.reports = newReports(os.Getenv("REPORTS_DIR"), os.Getenv("REPORTS_KEY"))
	log.Printf("serving ReelPlay %s (%d) on %s", s.manifest.VersionName, s.manifest.VersionCode, addr)
	srv := &http.Server{
		Addr:              addr,
		Handler:           s.routes(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Fatal(srv.ListenAndServe())
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
