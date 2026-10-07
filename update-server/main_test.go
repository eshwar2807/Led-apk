package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeRelease(t *testing.T, apk []byte, edit func(*Manifest)) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "ReelPlay-5.apk"), apk, 0o644); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(apk)
	m := Manifest{
		VersionCode: 5, VersionName: "1.4", APK: "ReelPlay-5.apk",
		SHA256: hex.EncodeToString(sum[:]), Size: int64(len(apk)),
		Notes: "Fixes <things>", PublishedAt: "2026-10-03",
	}
	if edit != nil {
		edit(&m)
	}
	raw, _ := json.Marshal(m)
	if err := os.WriteFile(filepath.Join(dir, "latest.json"), raw, 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

func TestServesManifestApkAndPage(t *testing.T) {
	apk := []byte(strings.Repeat("PK-apk-bytes-", 1000))
	s, err := load(writeRelease(t, apk, nil))
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(s.routes())
	defer ts.Close()

	res, _ := http.Get(ts.URL + "/reelplay/latest.json")
	var m Manifest
	json.NewDecoder(res.Body).Decode(&m)
	if res.StatusCode != 200 || m.VersionCode != 5 || res.Header.Get("Cache-Control") != "no-cache" {
		t.Fatalf("manifest: %d %+v %v", res.StatusCode, m, res.Header)
	}

	// Polling with the ETag is answered without a body.
	req, _ := http.NewRequest("GET", ts.URL+"/reelplay/latest.json", nil)
	req.Header.Set("If-None-Match", res.Header.Get("ETag"))
	res2, _ := http.DefaultClient.Do(req)
	if res2.StatusCode != http.StatusNotModified {
		t.Fatalf("conditional poll: %d", res2.StatusCode)
	}

	res3, _ := http.Get(ts.URL + "/reelplay/ReelPlay-5.apk")
	body, _ := io.ReadAll(res3.Body)
	if string(body) != string(apk) || res3.Header.Get("Content-Type") != "application/vnd.android.package-archive" {
		t.Fatalf("apk: %d bytes, %v", len(body), res3.Header)
	}

	// Resuming an interrupted download.
	req4, _ := http.NewRequest("GET", ts.URL+"/reelplay/ReelPlay-5.apk", nil)
	req4.Header.Set("Range", "bytes=100-")
	res4, _ := http.DefaultClient.Do(req4)
	rest, _ := io.ReadAll(res4.Body)
	if res4.StatusCode != http.StatusPartialContent || string(rest) != string(apk[100:]) {
		t.Fatalf("range: %d, %d bytes", res4.StatusCode, len(rest))
	}

	page, _ := http.Get(ts.URL + "/")
	html, _ := io.ReadAll(page.Body)
	if !strings.Contains(string(html), "All Media Player 1.4") || !strings.Contains(string(html), "Fixes &lt;things&gt;") {
		t.Fatalf("page not rendered/escaped: %s", html)
	}
}

func TestOnlyTheReleasedFileIsServed(t *testing.T) {
	s, err := load(writeRelease(t, []byte("apk"), nil))
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(s.routes())
	defer ts.Close()
	for _, p := range []string{"/reelplay/latest.json.bak", "/reelplay/other.apk", "/reelplay/..%2Flatest.json", "/etc/passwd"} {
		res, _ := http.Get(ts.URL + p)
		if res.StatusCode != http.StatusNotFound {
			t.Errorf("%s: got %d, want 404", p, res.StatusCode)
		}
	}
}

func TestRejectsBrokenReleases(t *testing.T) {
	cases := map[string]func(*Manifest){
		"wrong hash":     func(m *Manifest) { m.SHA256 = strings.Repeat("0", 64) },
		"wrong size":     func(m *Manifest) { m.Size++ },
		"path traversal": func(m *Manifest) { m.APK = "../ReelPlay-5.apk" },
		"no version":     func(m *Manifest) { m.VersionCode = 0 },
		"missing apk":    func(m *Manifest) { m.APK = "ReelPlay-6.apk" },
	}
	for name, edit := range cases {
		if _, err := load(writeRelease(t, []byte("apk"), edit)); err == nil {
			t.Errorf("%s: loaded, want error", name)
		}
	}
}
