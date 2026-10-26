// st15i-fetch downloads files over HTTPS for ST15i Player.
//
// Android 4.3's Java TLS stack cannot reach many modern hosts and the system
// has no tar, so the app delegates downloads (the Soloist tarball, artwork)
// to this static helper.
//
//	st15i-fetch [--ca FILE] [--dns a,b] get URL OUT
//	st15i-fetch [--ca FILE] [--dns a,b] extract URL DIR
//
// stdout carries "progress N" lines (0-100, only when the size is known) and a
// final "done". Errors go to stderr with exit status 1.
package main

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"
)

const (
	userAgent      = "st15i-player/1"
	connectTimeout = 15 * time.Second
	idleTimeout    = 60 * time.Second
	progressEvery  = 100 * time.Millisecond
)

// Used when neither --dns nor ST15I_DNS is given and the system has no
// /etc/resolv.conf (Android), where Go would otherwise query 127.0.0.1:53.
var fallbackDNS = []string{"8.8.8.8", "1.1.1.1"}

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

func run(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("st15i-fetch", flag.ContinueOnError)
	fs.SetOutput(stderr)
	caFile := fs.String("ca", "", "PEM bundle of trusted roots (default: system roots)")
	dns := fs.String("dns", "", "comma-separated DNS servers, host or host:port (default: $ST15I_DNS)")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "usage: st15i-fetch [--ca FILE] [--dns a,b] get URL OUT | extract URL DIR")
		fs.PrintDefaults()
	}
	if err := fs.Parse(args); err != nil {
		return 1
	}
	rest := fs.Args()
	if len(rest) != 3 || (rest[0] != "get" && rest[0] != "extract") {
		fs.Usage()
		return 1
	}

	client, err := newClient(*caFile, dnsServers(*dns))
	if err != nil {
		fmt.Fprintln(stderr, "st15i-fetch:", err)
		return 1
	}
	f := &fetcher{client: client, progress: func(p int) { fmt.Fprintf(stdout, "progress %d\n", p) }}
	if rest[0] == "get" {
		err = f.fetch(rest[1], rest[2])
	} else {
		err = f.fetchExtract(rest[1], rest[2])
	}
	if err != nil {
		fmt.Fprintln(stderr, "st15i-fetch:", err)
		return 1
	}
	fmt.Fprintln(stdout, "done")
	return 0
}

// dnsServers picks the resolver list: --dns, then $ST15I_DNS, then a public
// fallback when the system has no resolv.conf. nil means the Go default.
func dnsServers(flagValue string) []string {
	if s := parseDNS(flagValue); len(s) > 0 {
		return s
	}
	if s := parseDNS(os.Getenv("ST15I_DNS")); len(s) > 0 {
		return s
	}
	if _, err := os.Stat("/etc/resolv.conf"); err != nil {
		return parseDNS(strings.Join(fallbackDNS, ","))
	}
	return nil
}

// parseDNS turns "a,b:5353" into dialable host:port addresses.
func parseDNS(s string) []string {
	var out []string
	for _, item := range strings.Split(s, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if _, _, err := net.SplitHostPort(item); err != nil {
			item = net.JoinHostPort(strings.Trim(item, "[]"), "53")
		}
		out = append(out, item)
	}
	return out
}

func newClient(caFile string, dns []string) (*http.Client, error) {
	tlsConf := &tls.Config{}
	if caFile != "" {
		pemBytes, err := os.ReadFile(caFile)
		if err != nil {
			return nil, fmt.Errorf("read CA bundle: %w", err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pemBytes) {
			return nil, fmt.Errorf("no certificates found in %s", caFile)
		}
		tlsConf.RootCAs = pool
	}

	dialer := &net.Dialer{Timeout: connectTimeout, KeepAlive: 30 * time.Second}
	if len(dns) > 0 {
		var next atomic.Uint32
		dialer.Resolver = &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
				d := net.Dialer{Timeout: 5 * time.Second}
				addr := dns[int(next.Add(1)-1)%len(dns)]
				return d.DialContext(ctx, network, addr)
			},
		}
	}
	transport := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           dialer.DialContext,
		TLSClientConfig:       tlsConf,
		TLSHandshakeTimeout:   connectTimeout,
		ResponseHeaderTimeout: idleTimeout,
		ForceAttemptHTTP2:     true,
	}
	return &http.Client{Transport: transport}, nil
}

type fetcher struct {
	client   *http.Client
	progress func(pct int) // may be nil
}

// fetch downloads url to out via out.part and an atomic rename.
func (f *fetcher) fetch(url, out string) error {
	part := out + ".part"
	if err := f.download(url, part); err != nil {
		os.Remove(part)
		return err
	}
	if err := os.Rename(part, out); err != nil {
		os.Remove(part)
		return err
	}
	return nil
}

// fetchExtract downloads a .tar.gz and atomically replaces dir with its contents.
func (f *fetcher) fetchExtract(url, dir string) error {
	dir = filepath.Clean(dir)
	parent, base := filepath.Dir(dir), filepath.Base(dir)
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return err
	}
	part := filepath.Join(parent, "."+base+".tgz.part")
	defer os.Remove(part)
	if err := f.download(url, part); err != nil {
		return err
	}

	tmp, err := os.MkdirTemp(parent, "."+base+".new-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp) // no-op after a successful rename
	if err := os.Chmod(tmp, 0o755); err != nil {
		return err
	}
	if err := extractTarGz(part, tmp); err != nil {
		return fmt.Errorf("extract: %w", err)
	}

	old := filepath.Join(parent, "."+base+".old")
	os.RemoveAll(old)
	if _, err := os.Lstat(dir); err == nil {
		if err := os.Rename(dir, old); err != nil {
			return err
		}
	}
	if err := os.Rename(tmp, dir); err != nil {
		os.Rename(old, dir) // best effort restore
		return err
	}
	os.RemoveAll(old)
	return nil
}

// download streams url into path, reporting progress.
func (f *fetcher) download(url, path string) error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", userAgent)
	resp, err := f.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("GET %s: %s", url, resp.Status)
	}

	out, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	body := &idleReader{r: resp.Body, timer: time.AfterFunc(idleTimeout, cancel)}
	defer body.timer.Stop()
	pw := &progressWriter{total: resp.ContentLength, report: f.progress, last: -1}
	pw.emit(true)
	_, err = io.Copy(io.MultiWriter(out, pw), body)
	if err != nil && ctx.Err() != nil {
		err = fmt.Errorf("no data for %v: %w", idleTimeout, err)
	}
	if cerr := out.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return err
	}
	pw.emit(true)
	return nil
}

// idleReader pushes back the idle deadline on every successful read.
type idleReader struct {
	r     io.Reader
	timer *time.Timer
}

func (r *idleReader) Read(p []byte) (int, error) {
	n, err := r.r.Read(p)
	if n > 0 {
		r.timer.Reset(idleTimeout)
	}
	return n, err
}

type progressWriter struct {
	total, done int64
	report      func(int)
	last        int
	lastAt      time.Time
}

func (w *progressWriter) Write(p []byte) (int, error) {
	w.done += int64(len(p))
	w.emit(false)
	return len(p), nil
}

// emit reports the percentage when it changed, at most every progressEvery
// unless force is set.
func (w *progressWriter) emit(force bool) {
	if w.report == nil || w.total <= 0 {
		return
	}
	pct := int(w.done * 100 / w.total)
	if pct > 100 {
		pct = 100
	}
	if pct == w.last || (!force && time.Since(w.lastAt) < progressEvery) {
		return
	}
	w.last, w.lastAt = pct, time.Now()
	w.report(pct)
}

// extractTarGz unpacks archive into root, keeping permission bits and
// refusing any entry or link target that would land outside root.
func extractTarGz(archive, root string) error {
	file, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer file.Close()
	gz, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return err
		}
		rel, err := safeRel(h.Name)
		if err != nil {
			return err
		}
		if rel == "." {
			continue
		}
		if err := noSymlinkParents(root, rel); err != nil {
			return err
		}
		target := filepath.Join(root, rel)
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		mode := os.FileMode(h.Mode) & 0o777
		switch h.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			if err := os.Chmod(target, mode|0o700); err != nil {
				return err
			}
		case tar.TypeReg:
			removeNonDir(target)
			out, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
			if err != nil {
				return err
			}
			_, err = io.Copy(out, tr)
			if cerr := out.Close(); err == nil {
				err = cerr
			}
			if err != nil {
				return err
			}
			if err := os.Chmod(target, mode); err != nil {
				return err
			}
		case tar.TypeSymlink:
			if filepath.IsAbs(h.Linkname) {
				return fmt.Errorf("%s: absolute symlink target %q", h.Name, h.Linkname)
			}
			if err := checkLinkTarget(root, filepath.Dir(rel), h.Linkname); err != nil {
				return fmt.Errorf("%s: %w", h.Name, err)
			}
			removeNonDir(target)
			if err := os.Symlink(h.Linkname, target); err != nil {
				return err
			}
		case tar.TypeLink:
			linkRel, err := safeRel(h.Linkname)
			if err != nil {
				return fmt.Errorf("%s: hard link escapes: %w", h.Name, err)
			}
			if err := noSymlinkParents(root, linkRel); err != nil {
				return fmt.Errorf("%s: hard link: %w", h.Name, err)
			}
			if st, err := os.Lstat(filepath.Join(root, linkRel)); err != nil || !st.Mode().IsRegular() {
				return fmt.Errorf("%s: hard link target %q is not an extracted regular file", h.Name, h.Linkname)
			}
			removeNonDir(target)
			if err := os.Link(filepath.Join(root, linkRel), target); err != nil {
				return err
			}
		default:
			// Devices, fifos and pax metadata have no place in a runtime bundle.
		}
	}
}

// safeRel cleans an archive path and rejects absolute or parent-escaping ones.
func safeRel(name string) (string, error) {
	if name == "" || filepath.IsAbs(name) || strings.HasPrefix(name, "/") {
		return "", fmt.Errorf("unsafe path %q", name)
	}
	rel := filepath.Clean(name)
	if rel == ".." || strings.HasPrefix(rel, "../") {
		return "", fmt.Errorf("unsafe path %q", name)
	}
	return rel, nil
}

// checkLinkTarget walks a symlink target component by component against the
// tree extracted so far, starting from the link's directory (relative to
// root). Every component before the last must already be a real directory
// inside root; the walk may never climb above root. Directories are never
// replaced by later entries, so the result cannot change after this check,
// and since every symlink is checked this way, chains stay inside root too.
func checkLinkTarget(root, linkDir, linkname string) error {
	var stack []string
	if linkDir != "." {
		stack = strings.Split(linkDir, "/")
	}
	parts := strings.Split(linkname, "/")
	last := len(parts) - 1
	for last > 0 && (parts[last] == "" || parts[last] == ".") {
		last--
	}
	for i, part := range parts[:last+1] {
		switch part {
		case "", ".":
			continue
		case "..":
			if len(stack) == 0 {
				return fmt.Errorf("symlink target %q escapes", linkname)
			}
			stack = stack[:len(stack)-1]
			continue
		}
		stack = append(stack, part)
		if i == last {
			break
		}
		st, err := os.Lstat(filepath.Join(append([]string{root}, stack...)...))
		if err != nil || !st.IsDir() || st.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("symlink target %q: %s is not an extracted directory", linkname, strings.Join(stack, "/"))
		}
	}
	return nil
}

// noSymlinkParents rejects entries placed beneath an already extracted
// symlink, so lexical checks on link targets cannot be bypassed by chaining.
func noSymlinkParents(root, rel string) error {
	cur := root
	for _, part := range strings.Split(filepath.Dir(rel), "/") {
		if part == "." {
			continue
		}
		cur = filepath.Join(cur, part)
		st, err := os.Lstat(cur)
		if err != nil {
			return nil // not created yet
		}
		if st.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("%s: path goes through a symlink", rel)
		}
	}
	return nil
}

func removeNonDir(path string) {
	if st, err := os.Lstat(path); err == nil && !st.IsDir() {
		os.Remove(path)
	}
}
