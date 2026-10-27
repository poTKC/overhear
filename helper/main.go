// overhear-fetch downloads files and calls Spotify over HTTPS for Overhear.
//
// Android 4.3's Java TLS stack cannot reach many modern hosts and the system
// has no tar, so the app delegates downloads (the Soloist tarball, artwork)
// and token requests to this static helper.
//
//	overhear-fetch [--ca FILE] [--dns a,b] get URL OUT
//	overhear-fetch [--ca FILE] [--dns a,b] extract URL DIR
//	overhear-fetch [--ca FILE] [--dns a,b] token
//
// Only https URLs are accepted, on the command line and in redirects.
//
// For get and extract, stdout carries "progress N" lines (0-100, only when the
// size is known), "extracting" once an archive is downloaded, and a final
// "done".
//
// token POSTs the form read from stdin to accounts.spotify.com/api/token, so
// secrets never appear on the command line. Its stdout is "status N", an
// optional "retry-after V", a blank line and the reply body; any HTTP reply,
// whatever its status, exits 0.
//
// Errors go to stderr with exit status 1. SIGINT/SIGTERM abort cleanly (exit 1).
package main

import (
	"archive/tar"
	"bytes"
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
	"net/netip"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

const (
	userAgent          = "overhear/1"
	connectTimeout     = 15 * time.Second
	defaultIdleTimeout = 60 * time.Second
	progressEvery      = 100 * time.Millisecond
)

// Used when neither --dns nor OVERHEAR_DNS is given and the system has no
// /etc/resolv.conf (Android), where Go would otherwise query 127.0.0.1:53.
const fallbackDNS = "8.8.8.8,1.1.1.1"

// Spotify endpoints; tests point them at a local TLS server.
var (
	accountsBase = "https://accounts.spotify.com"
	apiBase      = "https://api.spotify.com/v1"
)

const (
	apiTimeout   = 30 * time.Second
	maxReplyBody = 4 << 20
)

func main() {
	os.Exit(runWithStdin(os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}

// run is runWithStdin with an empty stdin.
func run(args []string, stdout, stderr io.Writer) int {
	return runWithStdin(args, strings.NewReader(""), stdout, stderr)
}

// argCounts is the number of arguments each command takes, itself included.
var argCounts = map[string]int{"get": 3, "extract": 3, "token": 1}

func runWithStdin(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("overhear-fetch", flag.ContinueOnError)
	fs.SetOutput(stderr)
	caFile := fs.String("ca", "", "PEM bundle of trusted roots (default: system roots)")
	dns := fs.String("dns", "", "comma-separated DNS server IPs, optionally ip:port (default: $OVERHEAR_DNS)")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "usage: overhear-fetch [--ca FILE] [--dns a,b] get URL OUT | extract URL DIR | token")
		fs.PrintDefaults()
	}
	if err := fs.Parse(args); err != nil {
		return 1
	}
	rest := fs.Args()
	if len(rest) == 0 || argCounts[rest[0]] != len(rest) {
		fs.Usage()
		return 1
	}
	fail := func(err error) int {
		fmt.Fprintln(stderr, "overhear-fetch:", err)
		return 1
	}
	if rest[0] == "get" || rest[0] == "extract" {
		if err := checkHTTPS(rest[1]); err != nil {
			return fail(err)
		}
	}

	_, statErr := os.Stat("/etc/resolv.conf")
	servers, err := dnsServers(*dns, os.Getenv("OVERHEAR_DNS"), statErr == nil)
	if err != nil {
		return fail(err)
	}
	client, err := newClient(*caFile, servers)
	if err != nil {
		return fail(err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if rest[0] == "token" {
		ctx, cancel := context.WithTimeout(ctx, apiTimeout)
		defer cancel()
		if err := postToken(ctx, client, stdin, stdout); err != nil {
			return fail(err)
		}
		return 0
	}
	f := &fetcher{
		client:   client,
		progress: func(p int) { fmt.Fprintf(stdout, "progress %d\n", p) },
		status:   func(s string) { fmt.Fprintln(stdout, s) },
	}
	if rest[0] == "get" {
		err = f.fetch(ctx, rest[1], rest[2])
	} else {
		err = f.fetchExtract(ctx, rest[1], rest[2])
	}
	if err != nil {
		return fail(err)
	}
	fmt.Fprintln(stdout, "done")
	return 0
}

// postToken sends the form read from stdin to the token endpoint.
func postToken(ctx context.Context, client *http.Client, stdin io.Reader, stdout io.Writer) error {
	form, err := io.ReadAll(io.LimitReader(stdin, 64<<10))
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, "POST", accountsBase+"/api/token", bytes.NewReader(form))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	return doReply(client, req, stdout)
}

// doReply runs req and writes "status N", an optional "retry-after V", a
// blank line and the body (at most maxReplyBody bytes) to stdout.
func doReply(client *http.Client, req *http.Request, stdout io.Writer) error {
	req.Header.Set("User-Agent", userAgent)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxReplyBody+1))
	if err != nil {
		return err
	}
	if len(body) > maxReplyBody {
		return fmt.Errorf("reply larger than %d bytes", maxReplyBody)
	}
	fmt.Fprintf(stdout, "status %d\n", resp.StatusCode)
	if ra := resp.Header.Get("Retry-After"); ra != "" {
		fmt.Fprintf(stdout, "retry-after %s\n", strings.TrimSpace(ra))
	}
	fmt.Fprint(stdout, "\n")
	_, err = stdout.Write(body)
	return err
}

// dnsServers picks the resolver list: --dns, then $OVERHEAR_DNS, then a public
// fallback when the system has no resolv.conf. nil means the Go default.
func dnsServers(flagValue, env string, haveResolvConf bool) ([]string, error) {
	for _, s := range []string{flagValue, env} {
		if strings.TrimSpace(s) != "" {
			return parseDNS(s)
		}
	}
	if !haveResolvConf {
		return parseDNS(fallbackDNS)
	}
	return nil, nil
}

// parseDNS turns "ip,ip:port" into dialable ip:port addresses. Only literal
// IPs are accepted: resolving a DNS server's name would need DNS.
func parseDNS(s string) ([]string, error) {
	var out []string
	for _, item := range strings.Split(s, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		host, port := item, "53"
		if h, p, err := net.SplitHostPort(item); err == nil {
			host, port = h, p
		}
		ip, err := netip.ParseAddr(strings.Trim(host, "[]"))
		if err != nil {
			return nil, fmt.Errorf("DNS server %q is not an IP address", item)
		}
		if n, err := strconv.ParseUint(port, 10, 16); err != nil || n == 0 {
			return nil, fmt.Errorf("DNS server %q has an invalid port", item)
		}
		out = append(out, net.JoinHostPort(ip.String(), port))
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no DNS servers in %q", s)
	}
	return out, nil
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
		ResponseHeaderTimeout: defaultIdleTimeout,
		ForceAttemptHTTP2:     true,
	}
	return &http.Client{Transport: transport, CheckRedirect: httpsRedirectsOnly}, nil
}

// checkHTTPS accepts only absolute https URLs with a host: the helper runs
// with URLs from Soloist (artwork) and must never fall back to plain HTTP.
func checkHTTPS(raw string) error {
	u, err := url.Parse(raw)
	if err != nil {
		return fmt.Errorf("bad URL: %w", err)
	}
	if u.Scheme != "https" || u.Host == "" {
		return fmt.Errorf("not an https URL: %s", u.Redacted())
	}
	return nil
}

// httpsRedirectsOnly is the client's CheckRedirect: Go's default limit of 10
// redirects, and none to anything but https.
func httpsRedirectsOnly(req *http.Request, via []*http.Request) error {
	if len(via) >= 10 {
		return errors.New("stopped after 10 redirects")
	}
	if req.URL.Scheme != "https" {
		return fmt.Errorf("refusing redirect to non-https URL %s", req.URL.Redacted())
	}
	return nil
}

type fetcher struct {
	client      *http.Client
	progress    func(pct int)    // may be nil
	status      func(msg string) // may be nil
	idleTimeout time.Duration    // 0 means defaultIdleTimeout
}

// fetch downloads url to out through a unique temp file in out's directory
// and an atomic rename, so concurrent runs never see partial files.
func (f *fetcher) fetch(ctx context.Context, url, out string) error {
	tmp, err := os.CreateTemp(filepath.Dir(out), "."+filepath.Base(out)+".*.part")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name()) // no-op after the rename
	if err := f.downloadTo(ctx, url, tmp); err != nil {
		return err
	}
	if err := os.Chmod(tmp.Name(), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), out)
}

// fetchExtract downloads a .tar.gz and atomically replaces dir with its
// contents. Extracts into the same parent are serialized with a lock on the
// parent directory, which also makes cleaning up after killed runs safe.
func (f *fetcher) fetchExtract(ctx context.Context, url, dir string) error {
	dir = filepath.Clean(dir)
	parent, base := filepath.Dir(dir), filepath.Base(dir)
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return err
	}
	unlock, err := lockDir(parent)
	if err != nil {
		return err
	}
	defer unlock()

	old := filepath.Join(parent, "."+base+".old")
	if err := recoverInterrupted(parent, base, dir, old); err != nil {
		return err
	}

	part, err := os.CreateTemp(parent, "."+base+".*.tgz.part")
	if err != nil {
		return err
	}
	defer os.Remove(part.Name())
	if err := f.downloadTo(ctx, url, part); err != nil {
		return err
	}
	if f.status != nil {
		f.status("extracting")
	}

	tmp, err := os.MkdirTemp(parent, "."+base+".new-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp) // no-op after a successful rename
	if err := os.Chmod(tmp, 0o755); err != nil {
		return err
	}
	if err := extractTarGz(ctx, part.Name(), tmp); err != nil {
		return fmt.Errorf("extract: %w", err)
	}

	if _, err := os.Lstat(dir); err == nil {
		if err := os.Rename(dir, old); err != nil {
			return err
		}
	}
	if err := os.Rename(tmp, dir); err != nil {
		os.Rename(old, dir) // best effort restore
		return err
	}
	syncDir(parent)
	os.RemoveAll(old)
	return nil
}

// recoverInterrupted repairs what a killed extract may leave behind: a swap
// stopped between its two renames (dir missing, .old present), work dirs and
// partial downloads. The caller holds the parent lock.
func recoverInterrupted(parent, base, dir, old string) error {
	if _, err := os.Lstat(dir); os.IsNotExist(err) {
		if _, err := os.Lstat(old); err == nil {
			if err := os.Rename(old, dir); err != nil {
				return fmt.Errorf("restore %s: %w", old, err)
			}
		}
	}
	os.RemoveAll(old)
	for _, pattern := range []string{"." + base + ".new-*", "." + base + ".*tgz.part"} {
		stale, _ := filepath.Glob(filepath.Join(parent, globEscape(pattern)))
		for _, p := range stale {
			os.RemoveAll(p)
		}
	}
	return nil
}

// globEscape escapes glob metacharacters except the '*' wildcards we add.
func globEscape(pattern string) string {
	r := strings.NewReplacer(`\`, `\\`, `?`, `\?`, `[`, `\[`)
	return r.Replace(pattern)
}

func lockDir(dir string) (func(), error) {
	d, err := os.Open(dir)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(d.Fd()), syscall.LOCK_EX); err != nil {
		d.Close()
		return nil, fmt.Errorf("lock %s: %w", dir, err)
	}
	return func() { d.Close() }, nil // closing releases the lock
}

func syncDir(dir string) {
	if d, err := os.Open(dir); err == nil {
		d.Sync()
		d.Close()
	}
}

// downloadTo streams url into out (which it closes), reporting progress and
// syncing the data to disk.
func (f *fetcher) downloadTo(ctx context.Context, url string, out *os.File) (err error) {
	defer func() {
		if cerr := out.Close(); err == nil {
			err = cerr
		}
	}()
	idle := f.idleTimeout
	if idle == 0 {
		idle = defaultIdleTimeout
	}
	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)
	errIdle := fmt.Errorf("no data for %v", idle)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", userAgent)
	timer := time.AfterFunc(idle, func() { cancel(errIdle) })
	defer timer.Stop()
	resp, err := f.client.Do(req)
	if err != nil {
		return withCause(ctx, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("GET %s: %s", url, resp.Status)
	}

	body := &idleReader{r: resp.Body, timer: timer, idle: idle}
	pw := &progressWriter{total: resp.ContentLength, report: f.progress, last: -1}
	pw.emit(true)
	if _, err := io.Copy(io.MultiWriter(out, pw), body); err != nil {
		return withCause(ctx, err)
	}
	if err := out.Sync(); err != nil {
		return err
	}
	pw.emit(true)
	return nil
}

// withCause explains a transfer error caused by the idle timer or a signal.
func withCause(ctx context.Context, err error) error {
	if cause := context.Cause(ctx); cause != nil {
		return fmt.Errorf("%w: %v", cause, err)
	}
	return err
}

// idleReader pushes back the idle deadline on every successful read.
type idleReader struct {
	r     io.Reader
	timer *time.Timer
	idle  time.Duration
}

func (r *idleReader) Read(p []byte) (int, error) {
	n, err := r.r.Read(p)
	if n > 0 {
		r.timer.Reset(r.idle)
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

// ctxReader stops a long copy once ctx is cancelled.
type ctxReader struct {
	ctx context.Context
	r   io.Reader
}

func (r ctxReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.r.Read(p)
}

// extractTarGz unpacks archive into root, keeping permission bits and
// refusing any entry or link target that would land outside root.
func extractTarGz(ctx context.Context, archive, root string) error {
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
	tr := tar.NewReader(ctxReader{ctx, gz})
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			syncDir(root)
			return nil
		}
		if err != nil {
			return err
		}
		switch h.Typeflag {
		case tar.TypeReg, tar.TypeRegA, tar.TypeDir, tar.TypeSymlink, tar.TypeLink:
		case tar.TypeXHeader, tar.TypeXGlobalHeader:
			continue // metadata only
		default:
			// Devices, fifos and sparse files have no place in a runtime bundle.
			return fmt.Errorf("%s: unsupported entry type %q", h.Name, h.Typeflag)
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
		case tar.TypeReg, tar.TypeRegA:
			removeNonDir(target)
			out, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
			if err != nil {
				return err
			}
			_, err = io.Copy(out, tr)
			if err == nil {
				err = out.Sync()
			}
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
