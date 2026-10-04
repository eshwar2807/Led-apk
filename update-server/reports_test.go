package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func reportServer(t *testing.T, dir, key string) *httptest.Server {
	t.Helper()
	s, err := load(writeRelease(t, []byte("apk"), nil))
	if err != nil {
		t.Fatal(err)
	}
	s.reports = newReports(dir, key)
	ts := httptest.NewServer(s.routes())
	t.Cleanup(ts.Close)
	return ts
}

func post(t *testing.T, url, body string) int {
	t.Helper()
	res, err := http.Post(url+"/reelplay/report", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	return res.StatusCode
}

func get(t *testing.T, url string) (int, string) {
	t.Helper()
	res, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return res.StatusCode, string(b)
}

func TestReportsAreStoredAndReadableWithTheKey(t *testing.T) {
	dir := t.TempDir()
	ts := reportServer(t, dir, "s3cret")

	if c := post(t, ts.URL, `{"kind":"crash","versionName":"1.9","versionCode":10,"summary":"<b>boom</b>","text":"trace here"}`); c != 201 {
		t.Fatalf("post: %d", c)
	}
	if c := post(t, ts.URL, `{"kind":"problem","text":"   "}`); c != 400 {
		t.Fatalf("empty report: %d", c)
	}
	if c := post(t, ts.URL, `not json`); c != 400 {
		t.Fatalf("bad json: %d", c)
	}
	files, _ := os.ReadDir(dir)
	if len(files) != 1 {
		t.Fatalf("stored %d files", len(files))
	}

	if c, _ := get(t, ts.URL+"/reelplay/reports"); c != 403 {
		t.Fatalf("no key: %d", c)
	}
	if c, _ := get(t, ts.URL+"/reelplay/reports?key=wrong"); c != 403 {
		t.Fatalf("wrong key: %d", c)
	}
	c, page := get(t, ts.URL+"/reelplay/reports?key=s3cret")
	if c != 200 || !strings.Contains(page, "&lt;b&gt;boom&lt;/b&gt;") || strings.Contains(page, "<b>boom") {
		t.Fatalf("list: %d %s", c, page)
	}
	id := strings.TrimSuffix(files[0].Name(), ".json")
	c, one := get(t, ts.URL+"/reelplay/reports/"+id+"?key=s3cret")
	if c != 200 || !strings.Contains(one, "trace here") || !strings.Contains(one, "1.9") {
		t.Fatalf("show: %d %s", c, one)
	}
	if c, _ := get(t, ts.URL+"/reelplay/reports/..%2Flatest?key=s3cret"); c != 404 {
		t.Fatalf("traversal: %d", c)
	}
	// The release itself is still served next to the report routes.
	if c, _ := get(t, ts.URL+"/reelplay/ReelPlay-5.apk"); c != 200 {
		t.Fatalf("apk: %d", c)
	}
}

func TestReadingIsOffWithoutAKey_andSendersAreRateLimited(t *testing.T) {
	ts := reportServer(t, t.TempDir(), "")
	if c, _ := get(t, ts.URL+"/reelplay/reports?key="); c != 404 {
		t.Fatalf("reading without a configured key: %d", c)
	}
	for i := 0; i < reportsPerHour; i++ {
		if c := post(t, ts.URL, `{"text":"x"}`); c != 201 {
			t.Fatalf("report %d: %d", i, c)
		}
	}
	if c := post(t, ts.URL, `{"text":"x"}`); c != 429 {
		t.Fatalf("over the limit: %d", c)
	}
}

func TestReportsWithoutStorageAreStillAccepted(t *testing.T) {
	ts := reportServer(t, "", "key")
	if c := post(t, ts.URL, `{"text":"logged only"}`); c != 201 {
		t.Fatalf("post: %d", c)
	}
	if c := post(t, ts.URL, `{"text":"`+strings.Repeat("a", maxReportBytes)+`"}`); c != 413 {
		t.Fatalf("too large: %d", c)
	}
}

func TestReportsCanBeDeleted(t *testing.T) {
	dir := t.TempDir()
	ts := reportServer(t, dir, "k")
	for i := 0; i < 3; i++ {
		post(t, ts.URL, `{"text":"x"}`)
	}
	files, _ := os.ReadDir(dir)
	id := strings.TrimSuffix(files[0].Name(), ".json")
	noRedirect := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	del := func(path string) int {
		res, err := noRedirect.Post(ts.URL+path, "", nil)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode
	}
	if c := del("/reelplay/reports/" + id + "/delete?key=wrong"); c != 403 {
		t.Fatalf("wrong key: %d", c)
	}
	if c := del("/reelplay/reports/" + id + "/delete?key=k"); c != 303 {
		t.Fatalf("delete one: %d", c)
	}
	if left, _ := os.ReadDir(dir); len(left) != 2 {
		t.Fatalf("after one delete: %d left", len(left))
	}
	if c := del("/reelplay/reports/all/delete?key=k"); c != 303 {
		t.Fatalf("delete all: %d", c)
	}
	if left, _ := os.ReadDir(dir); len(left) != 0 {
		t.Fatalf("after delete all: %d left", len(left))
	}
}
