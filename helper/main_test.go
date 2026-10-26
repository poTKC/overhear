package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
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
	if err := f.fetch(srv.URL+"/f", out); err != nil {
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
	if err := f.fetch(srv.URL+"/truncated", out); err == nil {
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
	err := f.fetch(srv.URL+"/missing", out)
	if err == nil || !strings.Contains(err.Error(), "404") {
		t.Fatalf("err = %v, want 404 error", err)
	}
	if _, err := os.Stat(out); !os.IsNotExist(err) {
		t.Errorf("output exists after failed fetch")
	}
	noLeftovers(t, dir)
	if err := f.fetchExtract(srv.URL+"/missing", filepath.Join(dir, "x")); err == nil {
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
	})
	srv := serve(t, map[string][]byte{"/a.tgz": tgz})
	parent := t.TempDir()
	dir := filepath.Join(parent, "pkg")
	// An existing install must be replaced entirely.
	os.MkdirAll(dir, 0o755)
	os.WriteFile(filepath.Join(dir, "stale"), []byte("x"), 0o644)

	f := &fetcher{client: srv.Client()}
	if err := f.fetchExtract(srv.URL+"/a.tgz", dir); err != nil {
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
	}
	for name, ents := range cases {
		t.Run(name, func(t *testing.T) {
			srv := serve(t, map[string][]byte{"/a.tgz": makeTarGz(t, ents)})
			parent := t.TempDir()
			f := &fetcher{client: srv.Client()}
			if err := f.fetchExtract(srv.URL+"/a.tgz", filepath.Join(parent, "pkg")); err == nil {
				t.Fatal("expected error")
			}
			noLeftovers(t, parent)
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
	got := parseDNS("8.8.8.8, 1.1.1.1:5353,,2001:4860:4860::8888")
	want := []string{"8.8.8.8:53", "1.1.1.1:5353", "[2001:4860:4860::8888]:53"}
	if strings.Join(got, " ") != strings.Join(want, " ") {
		t.Errorf("parseDNS = %v, want %v", got, want)
	}
}

func TestUsageErrors(t *testing.T) {
	for _, args := range [][]string{nil, {"get", "u"}, {"bogus", "a", "b"}, {"--ca"}} {
		if code := run(args, io.Discard, io.Discard); code == 0 {
			t.Errorf("run(%q) = 0, want failure", args)
		}
	}
}
