package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"html/template"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Report is a problem report sent from the app ("Report a problem", crash, playback error).
type Report struct {
	ID          string    `json:"id"`
	Received    time.Time `json:"received"`
	Kind        string    `json:"kind"`
	VersionName string    `json:"versionName"`
	VersionCode int       `json:"versionCode"`
	Summary     string    `json:"summary"`
	Text        string    `json:"text"`
}

const (
	maxReportBytes  = 256 << 10
	reportsPerHour  = 20
	reportsListSize = 300
)

// reports stores reports as one JSON file each in dir (a fly.io volume), and lets whoever
// holds key read them. Without a dir they are still logged, so `fly logs` shows them.
type reports struct {
	dir string
	key string

	mu   sync.Mutex
	seen map[string][]time.Time // client IP -> recent report times, for rate limiting
}

func newReports(dir, key string) *reports {
	if dir != "" {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			log.Printf("reports: can't use %s (%v); reports will only be logged", dir, err)
			dir = ""
		}
	}
	return &reports{dir: dir, key: key, seen: map[string][]time.Time{}}
}

func (rs *reports) routes(mux *http.ServeMux) {
	mux.HandleFunc("POST /reelplay/report", rs.receive)
	mux.HandleFunc("GET /reelplay/reports", rs.list)
	mux.HandleFunc("GET /reelplay/reports/{id}", rs.show)
	mux.HandleFunc("POST /reelplay/reports/{id}/delete", rs.delete)
}

func clientIP(r *http.Request) string {
	if ip := r.Header.Get("Fly-Client-IP"); ip != "" {
		return ip
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (rs *reports) allow(ip string, now time.Time) bool {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	recent := rs.seen[ip][:0]
	for _, t := range rs.seen[ip] {
		if now.Sub(t) < time.Hour {
			recent = append(recent, t)
		}
	}
	if len(recent) >= reportsPerHour {
		rs.seen[ip] = recent
		return false
	}
	rs.seen[ip] = append(recent, now)
	return true
}

func (rs *reports) receive(w http.ResponseWriter, r *http.Request) {
	if !rs.allow(clientIP(r), time.Now()) {
		http.Error(w, "too many reports", http.StatusTooManyRequests)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxReportBytes))
	if err != nil {
		http.Error(w, "report too large", http.StatusRequestEntityTooLarge)
		return
	}
	var rep Report
	if err := json.Unmarshal(body, &rep); err != nil || strings.TrimSpace(rep.Text) == "" {
		http.Error(w, "bad report", http.StatusBadRequest)
		return
	}
	id := make([]byte, 4)
	rand.Read(id)
	rep.Received = time.Now().UTC()
	rep.ID = rep.Received.Format("20060102-150405") + "-" + hex.EncodeToString(id)
	rep.Kind = clip(rep.Kind, 20)
	rep.VersionName = clip(rep.VersionName, 20)
	rep.Summary = clip(rep.Summary, 200)

	log.Printf("report %s: %s v%s: %s", rep.ID, rep.Kind, rep.VersionName, rep.Summary)
	if rs.dir == "" {
		log.Printf("report %s text:\n%s", rep.ID, clip(rep.Text, 8000))
	} else {
		data, _ := json.Marshal(rep)
		if err := os.WriteFile(filepath.Join(rs.dir, rep.ID+".json"), data, 0o600); err != nil {
			log.Printf("report %s not saved: %v\n%s", rep.ID, err, clip(rep.Text, 8000))
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]string{"id": rep.ID})
}

// authorized checks ?key= against REPORTS_KEY. With no key configured, reading is off.
func (rs *reports) authorized(w http.ResponseWriter, r *http.Request) bool {
	if rs.key == "" || rs.dir == "" {
		http.Error(w, "Reading reports isn't enabled on this server (set REPORTS_KEY).", http.StatusNotFound)
		return false
	}
	if subtle.ConstantTimeCompare([]byte(r.URL.Query().Get("key")), []byte(rs.key)) != 1 {
		http.Error(w, "wrong key", http.StatusForbidden)
		return false
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	return true
}

func (rs *reports) load(name string) (Report, error) {
	var rep Report
	data, err := os.ReadFile(filepath.Join(rs.dir, name))
	if err == nil {
		err = json.Unmarshal(data, &rep)
	}
	return rep, err
}

var listTmpl = template.Must(template.New("list").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>All Media Player reports</title>
<style>
:root{color-scheme:light dark;--bg:#fff;--fg:#14171f;--muted:#5b6475;--accent:#3d8bff;--line:#dde1ea}
@media (prefers-color-scheme:dark){:root{--bg:#0b0e16;--fg:#e4e7f0;--muted:#a3abbb;--line:#262e3e}}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif}
main{max-width:760px;margin:0 auto;padding:32px 16px}
a{color:var(--accent);text-decoration:none} li{padding:10px 0;border-bottom:1px solid var(--line);list-style:none}
ul{padding:0} .muted{color:var(--muted);font-size:13px} pre{white-space:pre-wrap;word-break:break-word;font:13px/1.45 ui-monospace,monospace}
</style></head><body><main>
{{if .One}}<p><a href="/reelplay/reports?key={{.Key}}">← All reports</a></p>
<h1>{{.One.Kind}}: {{.One.Summary}}</h1>
<p class="muted">{{.One.Received.Format "2006-01-02 15:04 MST"}} · v{{.One.VersionName}} ({{.One.VersionCode}}) · {{.One.ID}}</p>
<pre>{{.One.Text}}</pre>
<form method="post" action="/reelplay/reports/{{.One.ID}}/delete?key={{.Key}}"><button>Delete this report</button></form>
{{else}}<h1>All Media Player reports</h1><p class="muted">{{len .All}} newest first</p>
<ul>{{range .All}}<li><a href="/reelplay/reports/{{.ID}}?key={{$.Key}}">{{.Kind}}: {{.Summary}}</a>
<div class="muted">{{.Received.Format "2006-01-02 15:04 MST"}} · v{{.VersionName}}</div></li>{{else}}<li>No reports yet.</li>{{end}}</ul>
{{if .All}}<form method="post" action="/reelplay/reports/all/delete?key={{.Key}}" onsubmit="return confirm('Delete every report?')"><button>Delete all reports</button></form>{{end}}{{end}}
</main></body></html>`))

func (rs *reports) list(w http.ResponseWriter, r *http.Request) {
	if !rs.authorized(w, r) {
		return
	}
	entries, _ := os.ReadDir(rs.dir)
	names := []string{}
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".json") {
			names = append(names, e.Name())
		}
	}
	// IDs start with the time, so name order is time order.
	sort.Sort(sort.Reverse(sort.StringSlice(names)))
	if len(names) > reportsListSize {
		names = names[:reportsListSize]
	}
	all := []Report{}
	for _, n := range names {
		if rep, err := rs.load(n); err == nil {
			all = append(all, rep)
		}
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	listTmpl.Execute(w, map[string]any{"All": all, "Key": rs.key})
}

func (rs *reports) show(w http.ResponseWriter, r *http.Request) {
	if !rs.authorized(w, r) {
		return
	}
	id := r.PathValue("id")
	if id != filepath.Base(id) || strings.ContainsAny(id, `/\`) {
		http.NotFound(w, r)
		return
	}
	rep, err := rs.load(id + ".json")
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	listTmpl.Execute(w, map[string]any{"One": rep, "Key": rs.key})
}

// delete removes one report, or every report when the id is "all".
func (rs *reports) delete(w http.ResponseWriter, r *http.Request) {
	if !rs.authorized(w, r) {
		return
	}
	id := r.PathValue("id")
	if id == "all" {
		entries, _ := os.ReadDir(rs.dir)
		for _, e := range entries {
			if strings.HasSuffix(e.Name(), ".json") {
				os.Remove(filepath.Join(rs.dir, e.Name()))
			}
		}
		log.Printf("reports: all deleted")
	} else {
		if id != filepath.Base(id) || strings.ContainsAny(id, `/\.`) {
			http.NotFound(w, r)
			return
		}
		if err := os.Remove(filepath.Join(rs.dir, id+".json")); err != nil {
			http.NotFound(w, r)
			return
		}
		log.Printf("report %s deleted", id)
	}
	http.Redirect(w, r, "/reelplay/reports?key="+url.QueryEscape(rs.key), http.StatusSeeOther)
}

func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) > n {
		return s[:n]
	}
	return s
}
