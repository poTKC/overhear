package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type entry struct {
	name, body, link string
	mode             int64
	typ              byte
}

func makeTarGz(t *testing.T, entries []entry) []byte {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for _, e := range entries {
		h := &tar.Header{Name: e.name, Mode: e.mode, Typeflag: e.typ, Linkname: e.link}
		if e.typ == tar.TypeReg {
			h.Size = int64(len(e.body))
		}
		if err := tw.WriteHeader(h); err != nil {
			t.Fatal(err)
		}
		if e.typ == tar.TypeReg {
			if _, err := tw.Write([]byte(e.body)); err != nil {
				t.Fatal(err)
			}
		}
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func serve(t *testing.T, routes map[string][]byte) *httptest.Server {
	t.Helper()
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/stall":
			w.Header().Set("Content-Length", "1000")
			w.Write([]byte("start"))
			w.(http.Flusher).Flush()
			select {
			case <-r.Context().Done():
			case <-time.After(10 * time.Second):
			}
			return
		case "/truncated":
			w.Header().Set("Content-Length", "1000")
			w.Write([]byte("short"))
			return
		}
		b, ok := routes[r.URL.Path]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Write(b)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func noLeftovers(t *testing.T, dir string, allowed ...string) {
	t.Helper()
	ents, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range ents {
		ok := false
		for _, a := range allowed {
			if e.Name() == a {
				ok = true
			}
		}
		if !ok {
			t.Errorf("unexpected leftover %s in %s", e.Name(), dir)
		}
	}
}

func TestFetchWritesAtomically(t *testing.T) {
	srv := serve(t, map[string][]byte{"/f": []byte("hello world")})
	dir := t.TempDir()
	out := filepath.Join(dir, "out.bin")
	var progress []int
	f := &fetcher{client: srv.Client(), progress: func(p int) { progress = append(progress, p) }}
	if err := f.fetch(t.Context(), srv.URL+"/f", out); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(out)
	if err != nil || string(b) != "hello world" {
		t.Fatalf("got %q, %v", b, err)
	}
	noLeftovers(t, dir, "out.bin")
	if len(progress) == 0 || progress[len(progress)-1] != 100 {
		t.Errorf("progress = %v, want ending in 100", progress)
	}
}

func TestFetchFailureKeepsExistingFile(t *testing.T) {
	srv := serve(t, nil)
	dir := t.TempDir()
	out := filepath.Join(dir, "out.bin")
	os.WriteFile(out, []byte("old"), 0o644)
	f := &fetcher{client: srv.Client()}
	if err := f.fetch(t.Context(), srv.URL+"/truncated", out); err == nil {
		t.Fatal("truncated body: expected error")
	}
	b, _ := os.ReadFile(out)
	if string(b) != "old" {
		t.Errorf("existing file clobbered: %q", b)
	}
	noLeftovers(t, dir, "out.bin")
}

func TestNon200IsError(t *testing.T) {
	srv := serve(t, nil)
	dir := t.TempDir()
	out := filepath.Join(dir, "out.bin")
	f := &fetcher{client: srv.Client()}
	err := f.fetch(t.Context(), srv.URL+"/missing", out)
	if err == nil || !strings.Contains(err.Error(), "404") {
		t.Fatalf("err = %v, want 404 error", err)
	}
	if _, err := os.Stat(out); !os.IsNotExist(err) {
		t.Errorf("output exists after failed fetch")
	}
	noLeftovers(t, dir)
	if err := f.fetchExtract(t.Context(), srv.URL+"/missing", filepath.Join(dir, "x")); err == nil {
		t.Fatal("extract of 404: expected error")
	}
	noLeftovers(t, dir)
}

func TestFetchExtractKeepsExecBits(t *testing.T) {
	tgz := makeTarGz(t, []entry{
		{name: "README", body: "read me", mode: 0o644, typ: tar.TypeReg},
		{name: "bin/", mode: 0o755, typ: tar.TypeDir},
		{name: "bin/tool", body: "#!/bin/sh\n", mode: 0o555, typ: tar.TypeReg},
		{name: "tool-link", link: "bin/tool", typ: tar.TypeSymlink},
		{name: "bin/tool-hard", link: "bin/tool", typ: tar.TypeLink},
	})
	srv := serve(t, map[string][]byte{"/a.tgz": tgz})
	parent := t.TempDir()
	dir := filepath.Join(parent, "pkg")
	// An existing install must be replaced entirely.
	os.MkdirAll(dir, 0o755)
	os.WriteFile(filepath.Join(dir, "stale"), []byte("x"), 0o644)

	f := &fetcher{client: srv.Client()}
	if err := f.fetchExtract(t.Context(), srv.URL+"/a.tgz", dir); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(filepath.Join(dir, "bin/tool"))
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm()&0o111 == 0 {
		t.Errorf("bin/tool mode %v lost exec bits", st.Mode())
	}
	st, _ = os.Stat(filepath.Join(dir, "README"))
	if st.Mode().Perm()&0o111 != 0 {
		t.Errorf("README mode %v unexpectedly executable", st.Mode())
	}
	if l, err := os.Readlink(filepath.Join(dir, "tool-link")); err != nil || l != "bin/tool" {
		t.Errorf("symlink = %q, %v", l, err)
	}
	if st, err := os.Lstat(filepath.Join(dir, "bin/tool-hard")); err != nil || !st.Mode().IsRegular() {
		t.Errorf("hard link: %v, %v", st, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "stale")); !os.IsNotExist(err) {
		t.Errorf("stale file survived re-extract")
	}
	noLeftovers(t, parent, "pkg")
}

func TestExtractRejectsEscapes(t *testing.T) {
	cases := map[string][]entry{
		"dotdot":   {{name: "../evil", body: "x", mode: 0o644, typ: tar.TypeReg}},
		"absolute": {{name: "/tmp/evil", body: "x", mode: 0o644, typ: tar.TypeReg}},
		"symlink":  {{name: "l", link: "../../etc", typ: tar.TypeSymlink}},
		"abslink":  {{name: "l", link: "/etc/passwd", typ: tar.TypeSymlink}},
		"chained": {
			{name: "d/", mode: 0o755, typ: tar.TypeDir},
			{name: "d/up", link: "..", typ: tar.TypeSymlink},
			{name: "d/up/esc", link: "../..", typ: tar.TypeSymlink},
		},
		"hardlink": {{name: "h", link: "../x", typ: tar.TypeLink}},
		"dotdot-through-symlink": {
			{name: "d/", mode: 0o755, typ: tar.TypeDir},
			{name: "d/up", link: "..", typ: tar.TypeSymlink},
			{name: "a", link: "d/up/..", typ: tar.TypeSymlink},
			{name: "h", link: "a/secret", typ: tar.TypeLink},
		},
		"hardlink-to-symlink": {
			{name: "f", body: "x", mode: 0o644, typ: tar.TypeReg},
			{name: "s", link: "f", typ: tar.TypeSymlink},
			{name: "h", link: "s", typ: tar.TypeLink},
		},
	}
	for name, ents := range cases {
		t.Run(name, func(t *testing.T) {
			srv := serve(t, map[string][]byte{"/a.tgz": makeTarGz(t, ents)})
			parent := t.TempDir()
			// A file outside DIR that an escaping link could reach.
			os.WriteFile(filepath.Join(parent, "secret"), []byte("s"), 0o644)
			f := &fetcher{client: srv.Client()}
			if err := f.fetchExtract(t.Context(), srv.URL+"/a.tgz", filepath.Join(parent, "pkg")); err == nil {
				t.Fatal("expected error")
			}
			noLeftovers(t, parent, "secret")
		})
	}
}

func TestCAFlagLoadsPEMBundle(t *testing.T) {
	srv := serve(t, map[string][]byte{"/f": []byte("trusted")})
	dir := t.TempDir()
	ca := filepath.Join(dir, "ca.pem")
	pemBytes := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: srv.Certificate().Raw})
	if err := os.WriteFile(ca, pemBytes, 0o644); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(dir, "out")

	var stdout, stderr bytes.Buffer
	if code := run([]string{"--ca", ca, "get", srv.URL + "/f", out}, &stdout, &stderr); code != 0 {
		t.Fatalf("exit %d, stderr: %s", code, stderr.String())
	}
	b, _ := os.ReadFile(out)
	if string(b) != "trusted" {
		t.Errorf("got %q", b)
	}
	if !strings.HasSuffix(stdout.String(), "done\n") {
		t.Errorf("stdout = %q, want trailing done", stdout.String())
	}

	// Without --ca the self-signed test cert must be rejected.
	stdout.Reset()
	stderr.Reset()
	if code := run([]string{"get", srv.URL + "/f", out + "2"}, &stdout, &stderr); code == 0 {
		t.Fatal("expected failure without --ca")
	}

	// A file with no certificates is an error.
	empty := filepath.Join(dir, "empty.pem")
	os.WriteFile(empty, []byte("nothing here"), 0o644)
	if code := run([]string{"--ca", empty, "get", srv.URL + "/f", out + "3"}, io.Discard, io.Discard); code == 0 {
		t.Fatal("expected failure for empty CA bundle")
	}
}

func TestParseDNS(t *testing.T) {
	got, err := parseDNS("8.8.8.8, 1.1.1.1:5353,,2001:4860:4860::8888,[::1]:53")
	want := []string{"8.8.8.8:53", "1.1.1.1:5353", "[2001:4860:4860::8888]:53", "[::1]:53"}
	if err != nil || strings.Join(got, " ") != strings.Join(want, " ") {
		t.Errorf("parseDNS = %v, %v, want %v", got, err, want)
	}
	for _, bad := range []string{"dns.google", "8.8.8.8:domain", "8.8.8.8:99999", "1.2.3"} {
		if _, err := parseDNS(bad); err == nil {
			t.Errorf("parseDNS(%q): expected error", bad)
		}
	}
}

func TestDNSServersPrecedence(t *testing.T) {
	cases := []struct {
		flag, env  string
		resolvConf bool
		want       string
	}{
		{"9.9.9.9", "1.0.0.1", false, "9.9.9.9:53"},
		{"", "1.0.0.1", false, "1.0.0.1:53"},
		{"", "", false, "8.8.8.8:53 1.1.1.1:53"},
		{"", "", true, ""},
	}
	for _, c := range cases {
		got, err := dnsServers(c.flag, c.env, c.resolvConf)
		if err != nil || strings.Join(got, " ") != c.want {
			t.Errorf("dnsServers(%q, %q, %v) = %v, %v; want %q", c.flag, c.env, c.resolvConf, got, err, c.want)
		}
	}
	if _, err := dnsServers("example.com", "", false); err == nil {
		t.Error("hostname DNS server: expected error")
	}
	if code := run([]string{"--dns", "nope", "get", "https://x/", filepath.Join(t.TempDir(), "o")}, io.Discard, io.Discard); code == 0 {
		t.Error("run with invalid --dns: expected failure")
	}
}

func TestStalledServerTimesOut(t *testing.T) {
	srv := serve(t, nil)
	dir := t.TempDir()
	f := &fetcher{client: srv.Client(), idleTimeout: 200 * time.Millisecond}
	start := time.Now()
	err := f.fetch(t.Context(), srv.URL+"/stall", filepath.Join(dir, "out"))
	if err == nil || !strings.Contains(err.Error(), "no data") {
		t.Fatalf("err = %v, want idle timeout", err)
	}
	if d := time.Since(start); d > 5*time.Second {
		t.Errorf("took %v", d)
	}
	noLeftovers(t, dir)
}

func TestCancelCleansUp(t *testing.T) {
	srv := serve(t, nil)
	dir := t.TempDir()
	f := &fetcher{client: srv.Client()}
	ctx, cancel := context.WithTimeout(t.Context(), 200*time.Millisecond)
	defer cancel()
	if err := f.fetch(ctx, srv.URL+"/stall", filepath.Join(dir, "out")); err == nil {
		t.Fatal("expected error after cancel")
	}
	ctx2, cancel2 := context.WithTimeout(t.Context(), 200*time.Millisecond)
	defer cancel2()
	if err := f.fetchExtract(ctx2, srv.URL+"/stall", filepath.Join(dir, "pkg")); err == nil {
		t.Fatal("expected error after cancel")
	}
	noLeftovers(t, dir)
}

func TestConcurrentGetsSameOutput(t *testing.T) {
	body := bytes.Repeat([]byte("0123456789abcdef"), 1<<16) // 1 MiB
	srv := serve(t, map[string][]byte{"/big": body})
	dir := t.TempDir()
	out := filepath.Join(dir, "out")
	f := &fetcher{client: srv.Client()}
	var wg sync.WaitGroup
	errs := make([]error, 4)
	for i := range errs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs[i] = f.fetch(t.Context(), srv.URL+"/big", out)
		}()
	}
	wg.Wait()
	for i, err := range errs {
		if err != nil {
			t.Errorf("fetch %d: %v", i, err)
		}
	}
	b, _ := os.ReadFile(out)
	if !bytes.Equal(b, body) {
		t.Errorf("result corrupted: %d bytes", len(b))
	}
	noLeftovers(t, dir, "out")
}

func TestExtractCleansStaleWork(t *testing.T) {
	tgz := makeTarGz(t, []entry{{name: "f", body: "new", mode: 0o644, typ: tar.TypeReg}})
	srv := serve(t, map[string][]byte{"/a.tgz": tgz})
	parent := t.TempDir()
	os.MkdirAll(filepath.Join(parent, ".pkg.new-123/sub"), 0o755)
	os.WriteFile(filepath.Join(parent, ".pkg.tgz.part"), []byte("x"), 0o644)
	os.WriteFile(filepath.Join(parent, ".pkg.456.tgz.part"), []byte("x"), 0o644)
	os.MkdirAll(filepath.Join(parent, ".other.new-1"), 0o755) // not ours
	f := &fetcher{client: srv.Client()}
	if err := f.fetchExtract(t.Context(), srv.URL+"/a.tgz", filepath.Join(parent, "pkg")); err != nil {
		t.Fatal(err)
	}
	noLeftovers(t, parent, "pkg", ".other.new-1")
}

func TestExtractRestoresOld(t *testing.T) {
	srv := serve(t, nil)
	parent := t.TempDir()
	os.MkdirAll(filepath.Join(parent, ".pkg.old"), 0o755)
	os.WriteFile(filepath.Join(parent, ".pkg.old/soloist"), []byte("prev"), 0o755)
	f := &fetcher{client: srv.Client()}
	// The download fails, but the interrupted swap is repaired first.
	if err := f.fetchExtract(t.Context(), srv.URL+"/missing", filepath.Join(parent, "pkg")); err == nil {
		t.Fatal("expected 404 error")
	}
	b, err := os.ReadFile(filepath.Join(parent, "pkg/soloist"))
	if err != nil || string(b) != "prev" {
		t.Fatalf("pkg not restored: %q, %v", b, err)
	}
	noLeftovers(t, parent, "pkg")
}

func TestExtractRejectsSpecialFiles(t *testing.T) {
	for name, typ := range map[string]byte{"fifo": tar.TypeFifo, "char": tar.TypeChar, "block": tar.TypeBlock} {
		t.Run(name, func(t *testing.T) {
			srv := serve(t, map[string][]byte{"/a.tgz": makeTarGz(t, []entry{{name: "x", mode: 0o644, typ: typ}})})
			parent := t.TempDir()
			f := &fetcher{client: srv.Client()}
			if err := f.fetchExtract(t.Context(), srv.URL+"/a.tgz", filepath.Join(parent, "pkg")); err == nil {
				t.Fatal("expected error")
			}
			noLeftovers(t, parent)
		})
	}
}

func TestExtractPrintsExtracting(t *testing.T) {
	tgz := makeTarGz(t, []entry{{name: "f", body: "x", mode: 0o644, typ: tar.TypeReg}})
	srv := serve(t, map[string][]byte{"/a.tgz": tgz})
	ca := filepath.Join(t.TempDir(), "ca.pem")
	os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: srv.Certificate().Raw}), 0o644)
	var stdout, stderr bytes.Buffer
	if code := run([]string{"--ca", ca, "extract", srv.URL + "/a.tgz", filepath.Join(t.TempDir(), "pkg")}, &stdout, &stderr); code != 0 {
		t.Fatalf("exit %d: %s", code, stderr.String())
	}
	if !strings.HasSuffix(stdout.String(), "progress 100\nextracting\ndone\n") {
		t.Errorf("stdout = %q", stdout.String())
	}
}

func TestUsageErrors(t *testing.T) {
	for _, args := range [][]string{nil, {"get", "u"}, {"bogus", "a", "b"}, {"--ca"}} {
		if code := run(args, io.Discard, io.Discard); code == 0 {
			t.Errorf("run(%q) = 0, want failure", args)
		}
	}
}

func TestRejectsNonHTTPSURLs(t *testing.T) {
	dir := t.TempDir()
	for _, u := range []string{"http://example.com/f", "ftp://example.com/f", "file:///etc/passwd", "example.com/f", "https:///nohost", "://bad"} {
		var stderr bytes.Buffer
		if code := run([]string{"get", u, filepath.Join(dir, "out")}, io.Discard, &stderr); code == 0 {
			t.Errorf("get %q succeeded, want failure", u)
		}
		if code := run([]string{"extract", u, filepath.Join(dir, "x")}, io.Discard, io.Discard); code == 0 {
			t.Errorf("extract %q succeeded, want failure", u)
		}
	}
	noLeftovers(t, dir)
}

func TestRedirectsMustStayHTTPS(t *testing.T) {
	plain := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("plain"))
	}))
	t.Cleanup(plain.Close)
	var tlsURL string
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/to-http":
			http.Redirect(w, r, plain.URL+"/f", http.StatusFound)
		case "/to-https":
			http.Redirect(w, r, tlsURL+"/f", http.StatusFound)
		case "/f":
			w.Write([]byte("secure"))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	tlsURL = srv.URL
	dir := t.TempDir()
	ca := filepath.Join(dir, "ca.pem")
	if err := os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: srv.Certificate().Raw}), 0o644); err != nil {
		t.Fatal(err)
	}

	var stderr bytes.Buffer
	if code := run([]string{"--ca", ca, "get", srv.URL + "/to-http", filepath.Join(dir, "bad")}, io.Discard, &stderr); code == 0 {
		t.Fatal("redirect to http succeeded, want failure")
	}
	if !strings.Contains(stderr.String(), "non-https") {
		t.Errorf("stderr = %q, want the non-https reason", stderr.String())
	}
	if _, err := os.Stat(filepath.Join(dir, "bad")); !os.IsNotExist(err) {
		t.Errorf("output exists after a refused redirect: %v", err)
	}

	out := filepath.Join(dir, "good")
	stderr.Reset()
	if code := run([]string{"--ca", ca, "get", srv.URL + "/to-https", out}, io.Discard, &stderr); code != 0 {
		t.Fatalf("https redirect: exit %d, stderr: %s", code, stderr.String())
	}
	if b, _ := os.ReadFile(out); string(b) != "secure" {
		t.Errorf("got %q", b)
	}
}
